package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.*;

/**
 * Strict, deterministic metadata wire format. Rejects duplicate/unknown keys and noncanonical
 * bytes.
 */
public final class CatalogCodec {
    private CatalogCodec() {}

    public static String digest(String value) {
        return digest(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static final class Fields {
        private final Map<String, String> values = new TreeMap<>();

        void put(String key, Object value) {
            values.put(key, String.valueOf(value));
        }

        String take(String key) {
            String value = values.remove(key);
            if (value == null) throw new IllegalArgumentException("Missing key: " + key);
            return value;
        }

        int number(String key) {
            return Integer.parseInt(take(key));
        }

        boolean bool(String key) {
            String s = take(key);
            if (!s.equals("true") && !s.equals("false"))
                throw new IllegalArgumentException("Boolean required");
            return Boolean.parseBoolean(s);
        }

        <T> void list(String key, List<T> list, BiConsumer<String, T> writer) {
            put(key + ".count", list.size());
            for (int i = 0; i < list.size(); i++) writer.accept(key + "." + i, list.get(i));
        }

        <T> List<T> list(String key, Function<String, T> reader) {
            int count = number(key + ".count");
            if (count < 0 || count > 128) throw new IllegalArgumentException("Invalid list count");
            List<T> result = new ArrayList<>();
            for (int i = 0; i < count; i++) result.add(reader.apply(key + "." + i));
            return List.copyOf(result);
        }

        void ref(String key, CatalogRef r) {
            put(key + ".id", r.id());
            put(key + ".version", r.version());
        }

        CatalogRef ref(String key) {
            return new CatalogRef(take(key + ".id"), number(key + ".version"));
        }

        void graph(GraphRef g) {
            put("graph.id", g.graphId());
            put("graph.version", g.graphVersion());
        }

        GraphRef graph() {
            return new GraphRef(take("graph.id"), number("graph.version"));
        }

        void skill(String key, SkillRef s) {
            put(key + ".id", s.skillId());
            put(key + ".version", s.definitionVersion());
        }

        SkillRef skill(String key) {
            return new SkillRef(take(key + ".id"), number(key + ".version"));
        }

        void targets(String key, List<BehaviorTarget> targets) {
            list(
                    key,
                    targets,
                    (p, t) -> {
                        skill(p, t.skill());
                        put(p + ".behavior", t.behavior());
                    });
        }

        List<BehaviorTarget> targets(String key) {
            return list(key, p -> new BehaviorTarget(skill(p), number(p + ".behavior")));
        }

        String encode() {
            StringBuilder out = new StringBuilder();
            values.forEach((k, v) -> out.append(k).append('=').append(escape(v)).append('\n'));
            return out.toString();
        }

        static String escape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }

        static Fields parse(String text) {
            if (text.length() > 200000) throw new IllegalArgumentException("Oversized manifest");
            Fields f = new Fields();
            Properties props =
                    new Properties() {
                        @Override
                        public synchronized Object put(Object k, Object v) {
                            if (containsKey(k))
                                throw new IllegalArgumentException("Duplicate manifest key: " + k);
                            return super.put(k, v);
                        }
                    };
            try {
                props.load(new StringReader(text));
            } catch (IOException impossible) {
                throw new IllegalArgumentException(impossible);
            }
            props.forEach((k, v) -> f.put((String) k, v));
            if (!f.encode().equals(text))
                throw new IllegalArgumentException("Noncanonical manifest");
            return f;
        }

        void end() {
            if (!values.isEmpty())
                throw new IllegalArgumentException("Unknown keys: " + values.keySet());
        }
    }

    private static void rubric(Fields f, ChallengeRubric r) {
        f.ref("rubric", r.ref());
        f.put("rubric.limitations", r.limitations());
        f.list(
                "checks",
                r.checks(),
                (p, c) -> {
                    f.put(p + ".id", c.id());
                    f.put(p + ".kind", c.kind());
                    f.put(p + ".required", c.required());
                    f.targets(p + ".targets", c.targets());
                    f.put(p + ".specification", c.specification());
                    f.put(p + ".verification", c.verification());
                    f.list(p + ".assets", c.assets(), f::put);
                    f.list(
                            p + ".cases",
                            c.cases(),
                            (k, t) -> {
                                f.put(k + ".id", t.id());
                                f.put(k + ".stdin", t.stdin());
                                f.put(k + ".stdout", t.expectedStdout());
                                f.put(k + ".exit", t.expectedExit());
                            });
                    f.put(p + ".timeout", c.timeoutSeconds());
                    f.put(p + ".visibility", c.visibility());
                    f.put(p + ".limitations", c.limitations());
                });
    }

    private static ChallengeRubric rubric(Fields f) {
        return new ChallengeRubric(
                f.ref("rubric"),
                f.list(
                        "checks",
                        p ->
                                new ChallengeRubric.Check(
                                        f.take(p + ".id"),
                                        CheckKind.valueOf(f.take(p + ".kind")),
                                        f.bool(p + ".required"),
                                        f.targets(p + ".targets"),
                                        f.take(p + ".specification"),
                                        f.take(p + ".verification"),
                                        f.list(p + ".assets", f::take),
                                        f.list(
                                                p + ".cases",
                                                k ->
                                                        new ChallengeRubric.IoCase(
                                                                f.take(k + ".id"),
                                                                f.take(k + ".stdin"),
                                                                f.take(k + ".stdout"),
                                                                f.number(k + ".exit"))),
                                        f.number(p + ".timeout"),
                                        Visibility.valueOf(f.take(p + ".visibility")),
                                        f.take(p + ".limitations"))),
                f.take("rubric.limitations"));
    }

    public static String encode(ChallengeRubric r) {
        Fields f = new Fields();
        rubric(f, r);
        return f.encode();
    }

    public static String encode(ChallengeSpec c) {
        Fields f = new Fields();
        f.ref("challenge", c.ref());
        f.put("domain", c.domainId());
        f.graph(c.graph());
        f.put("family", c.familyId());
        f.put("variant", c.variantId());
        f.put("cohort", c.cohort());
        f.targets("targets", c.targets());
        f.list("prerequisites", c.prerequisites(), f::skill);
        f.put("difficulty", c.difficulty());
        f.put("effort", c.effortMinutes());
        f.put("objective", c.objective());
        f.put("runner", c.runner());
        f.list(
                "assets",
                c.assets(),
                (p, a) -> {
                    f.put(p + ".id", a.id());
                    f.put(p + ".kind", a.kind());
                    f.put(p + ".path", a.path());
                    f.put(p + ".sha256", a.sha256());
                });
        f.list(
                "hints",
                c.hints(),
                (p, h) -> {
                    f.put(p + ".level", h.level());
                    f.put(p + ".asset", h.assetId());
                });
        f.put("retry.allowed", c.retry().allowed());
        f.put("retry.maximum", c.retry().maximumPresentations());
        f.put("retry.description", c.retry().description());
        f.put("author", c.attribution().author());
        f.put("license", c.attribution().license());
        f.put("source", c.attribution().source());
        rubric(f, c.rubric());
        return f.encode();
    }

    public static ChallengeSpec challenge(String text) {
        Fields f = Fields.parse(text);
        ChallengeSpec c =
                new ChallengeSpec(
                        f.ref("challenge"),
                        f.take("domain"),
                        f.graph(),
                        f.take("family"),
                        f.take("variant"),
                        Cohort.valueOf(f.take("cohort")),
                        f.targets("targets"),
                        f.list("prerequisites", f::skill),
                        f.number("difficulty"),
                        f.number("effort"),
                        f.take("objective"),
                        Runner.valueOf(f.take("runner")),
                        f.list(
                                "assets",
                                p ->
                                        new AssetRef(
                                                f.take(p + ".id"),
                                                AssetKind.valueOf(f.take(p + ".kind")),
                                                f.take(p + ".path"),
                                                f.take(p + ".sha256"))),
                        f.list(
                                "hints",
                                p ->
                                        new ChallengeSpec.Hint(
                                                f.number(p + ".level"), f.take(p + ".asset"))),
                        new ChallengeSpec.RetryPolicy(
                                f.bool("retry.allowed"),
                                f.number("retry.maximum"),
                                f.take("retry.description")),
                        new ChallengeSpec.Attribution(
                                f.take("author"), f.take("license"), f.take("source")),
                        rubric(f));
        f.end();
        if (!encode(c).equals(text)) throw new IllegalArgumentException("Unsorted manifest");
        return c;
    }

    public static String encode(EvolutionQuest q) {
        Fields f = new Fields();
        f.ref("quest", q.ref());
        f.put("domain", q.domainId());
        f.graph(q.graph());
        f.put("cohort", q.cohort());
        f.put("title", q.title());
        f.put("objective", q.objective());
        f.put("narrative", q.narrative());
        f.targets("targets", q.targets());
        f.list("prerequisites", q.prerequisites(), f::skill);
        f.put("difficulty", q.difficulty());
        f.put("effort", q.effortMinutes());
        f.list("challenges", q.challenges(), f::ref);
        f.put("completion.families", q.completion().independentFamilies());
        f.put("completion.unassisted", q.completion().unassistedRequired());
        f.put("completion.rule", q.completion().rule());
        f.put("milestone", q.futureMilestone() == null ? "" : q.futureMilestone());
        return f.encode();
    }

    public static EvolutionQuest quest(String text) {
        Fields f = Fields.parse(text);
        CatalogRef ref = f.ref("quest");
        String domain = f.take("domain");
        GraphRef graph = f.graph();
        Cohort cohort = Cohort.valueOf(f.take("cohort"));
        String title = f.take("title"),
                objective = f.take("objective"),
                narrative = f.take("narrative");
        var targets = f.targets("targets");
        var prerequisites = f.list("prerequisites", f::skill);
        int difficulty = f.number("difficulty"), effort = f.number("effort");
        var challenges = f.list("challenges", f::ref);
        var completion =
                new EvolutionQuest.Completion(
                        f.number("completion.families"),
                        f.bool("completion.unassisted"),
                        f.take("completion.rule"));
        String milestone = f.take("milestone");
        f.end();
        EvolutionQuest q =
                new EvolutionQuest(
                        ref,
                        domain,
                        graph,
                        cohort,
                        title,
                        objective,
                        narrative,
                        targets,
                        prerequisites,
                        difficulty,
                        effort,
                        challenges,
                        completion,
                        milestone.isEmpty() ? null : milestone);
        if (!encode(q).equals(text)) throw new IllegalArgumentException("Unsorted manifest");
        return q;
    }
}

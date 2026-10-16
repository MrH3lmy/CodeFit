package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.AdaptationInput.*;
import com.codefit.evolution.adaptation.NextQuestDecision.*;
import com.codefit.evolution.evidence.EvidenceTypes;
import com.codefit.evolution.mastery.MasteryTypes.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.Cohort;

import java.io.*;
import java.time.Instant;
import java.util.*;

/** Explicit bounded canonical V1 metadata schema. No reflection or Java object deserialization. */
final class AdaptationCodec {
    private AdaptationCodec() {}

    private static final int MAX_BYTES = 32 * 1024 * 1024, MAX_ITEMS = 10000;

    interface Write<T> {
        void write(DataOutputStream d, T value) throws IOException;
    }

    interface Read<T> {
        T read(DataInputStream d) throws IOException;
    }

    private static <T> byte[] encode(T v, Write<T> w) {
        try (var b = new ByteArrayOutputStream();
                var d = new DataOutputStream(b)) {
            d.writeInt(1);
            w.write(d, v);
            d.flush();
            if (b.size() > MAX_BYTES)
                throw new IllegalArgumentException("Oversized adaptation snapshot");
            return b.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid adaptation metadata", e);
        }
    }

    private static <T> T decode(byte[] b, Read<T> r, Write<T> w) {
        if (b == null || b.length > MAX_BYTES)
            throw new IllegalArgumentException("Oversized adaptation snapshot");
        try (var d = new DataInputStream(new ByteArrayInputStream(b))) {
            if (d.readInt() != 1)
                throw new IllegalArgumentException("Unsupported adaptation encoding version");
            T v = r.read(d);
            if (d.available() != 0 || !Arrays.equals(b, encode(v, w)))
                throw new IllegalArgumentException("Noncanonical adaptation snapshot");
            return v;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed adaptation snapshot", e);
        }
    }

    private static void string(DataOutputStream d, String v) throws IOException {
        d.writeBoolean(v != null);
        if (v != null) d.writeUTF(v);
    }

    private static String string(DataInputStream d) throws IOException {
        return d.readBoolean() ? d.readUTF() : null;
    }

    private static void instant(DataOutputStream d, Instant v) throws IOException {
        string(d, v == null ? null : v.toString());
    }

    private static Instant instant(DataInputStream d) throws IOException {
        String s = string(d);
        return s == null ? null : Instant.parse(s);
    }

    private static int count(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > MAX_ITEMS)
            throw new IllegalArgumentException("Invalid adaptation collection bound");
        return n;
    }

    private static <T> void list(DataOutputStream d, Collection<T> values, Write<T> w)
            throws IOException {
        if (values.size() > MAX_ITEMS)
            throw new IllegalArgumentException("Oversized adaptation collection");
        d.writeInt(values.size());
        for (T v : values) w.write(d, v);
    }

    private static <T> List<T> list(DataInputStream d, Read<T> r) throws IOException {
        int n = count(d);
        var v = new ArrayList<T>();
        for (int i = 0; i < n; i++) v.add(r.read(d));
        return List.copyOf(v);
    }

    private static <E extends Enum<E>> E enumeration(DataInputStream d, Class<E> type)
            throws IOException {
        String value = string(d);
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Map<String, Instant> map(DataInputStream d) throws IOException {
        int n = count(d);
        var r = new TreeMap<String, Instant>();
        for (int i = 0; i < n; i++) {
            String k = string(d);
            if (r.put(k, instant(d)) != null)
                throw new IllegalArgumentException("Duplicate defer identity");
        }
        return r;
    }

    private static void preferences(DataOutputStream d, Preferences v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        d.writeLong(v.version());
        string(d, v.track() == null ? null : v.track().name());
        skillRef(d, v.goal());
        d.writeInt(v.budgetMinutes());
        d.writeBoolean(v.paused());
        list(
                d,
                new TreeSet<>(v.declined()),
                (d2, v2) -> {
                    string(d2, v2);
                });
        list(
                d,
                new TreeMap<>(v.deferred()).entrySet(),
                (d2, v2) -> {
                    string(d2, v2.getKey());
                    instant(d2, v2.getValue());
                });
    }

    private static Preferences preferences(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new Preferences(
                d.readLong(),
                enumeration(d, Track.class),
                skillRef(d),
                d.readInt(),
                d.readBoolean(),
                new TreeSet<>(list(d, d2 -> string(d2))),
                map(d));
    }

    private static void observation(DataOutputStream d, Observation v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.evidenceId());
        string(d, v.submissionId());
        string(d, v.family());
        catalogRef(d, v.challenge());
        instant(d, v.at());
        string(d, v.outcome() == null ? null : v.outcome().name());
        string(d, v.assistance() == null ? null : v.assistance().name());
        string(d, v.exposure() == null ? null : v.exposure().name());
        d.writeBoolean(v.independent());
        d.writeBoolean(v.deliberateFailure());
        list(
                d,
                new TreeSet<>(v.passed()),
                (d2, v2) -> {
                    d2.writeBoolean(v2 != null);
                    if (v2 != null) d2.writeInt(v2);
                });
        list(
                d,
                new TreeSet<>(v.failed()),
                (d2, v2) -> {
                    d2.writeBoolean(v2 != null);
                    if (v2 != null) d2.writeInt(v2);
                });
        list(
                d,
                v.checks(),
                (d2, v2) -> {
                    string(d2, v2);
                });
        d.writeInt(v.difficulty());
        string(d, v.cohort() == null ? null : v.cohort().name());
    }

    private static Observation observation(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new Observation(
                string(d),
                string(d),
                string(d),
                catalogRef(d),
                instant(d),
                enumeration(d, EvidenceTypes.Outcome.class),
                enumeration(d, EvidenceTypes.Assistance.class),
                enumeration(d, EvidenceTypes.Exposure.class),
                d.readBoolean(),
                d.readBoolean(),
                new TreeSet<>(list(d, d2 -> (d2.readBoolean() ? d2.readInt() : null))),
                new TreeSet<>(list(d, d2 -> (d2.readBoolean() ? d2.readInt() : null))),
                list(d, d2 -> string(d2)),
                d.readInt(),
                enumeration(d, Cohort.class));
    }

    private static void activity(DataOutputStream d, Activity v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.id());
        instant(d, v.at());
        list(
                d,
                new TreeSet<>(v.behaviors()),
                (d2, v2) -> {
                    d2.writeBoolean(v2 != null);
                    if (v2 != null) d2.writeInt(v2);
                });
    }

    private static Activity activity(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new Activity(
                string(d),
                instant(d),
                new TreeSet<>(list(d, d2 -> (d2.readBoolean() ? d2.readInt() : null))));
    }

    private static void skillState(DataOutputStream d, SkillState v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        skillRef(d, v.skill());
        d.writeLong(v.revision());
        string(d, v.inputSha());
        string(d, v.estimatePolicy());
        string(d, v.epoch());
        string(d, v.band() == null ? null : v.band().name());
        string(d, v.support() == null ? null : v.support().name());
        string(d, v.freshness() == null ? null : v.freshness().name());
        d.writeBoolean(v.current());
        d.writeBoolean(v.contradicted());
        d.writeInt(v.independentFamilies());
        list(
                d,
                new TreeSet<>(v.requiredBehaviors()),
                (d2, v2) -> {
                    d2.writeBoolean(v2 != null);
                    if (v2 != null) d2.writeInt(v2);
                });
        list(
                d,
                new TreeSet<>(v.supportedBehaviors()),
                (d2, v2) -> {
                    d2.writeBoolean(v2 != null);
                    if (v2 != null) d2.writeInt(v2);
                });
        list(
                d,
                v.prerequisites(),
                (d2, v2) -> {
                    skillRef(d2, v2);
                });
        list(
                d,
                v.observations(),
                (d2, v2) -> {
                    observation(d2, v2);
                });
        list(
                d,
                v.activities(),
                (d2, v2) -> {
                    activity(d2, v2);
                });
        list(
                d,
                v.exclusions(),
                (d2, v2) -> {
                    string(d2, v2);
                });
    }

    private static SkillState skillState(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new SkillState(
                skillRef(d),
                d.readLong(),
                string(d),
                string(d),
                string(d),
                enumeration(d, Band.class),
                enumeration(d, Support.class),
                enumeration(d, Freshness.class),
                d.readBoolean(),
                d.readBoolean(),
                d.readInt(),
                new TreeSet<>(list(d, d2 -> (d2.readBoolean() ? d2.readInt() : null))),
                new TreeSet<>(list(d, d2 -> (d2.readBoolean() ? d2.readInt() : null))),
                list(d, d2 -> skillRef(d2)),
                list(d, d2 -> observation(d2)),
                list(d, d2 -> activity(d2)),
                list(d, d2 -> string(d2)));
    }

    private static void candidate(DataOutputStream d, Candidate v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        catalogRef(d, v.quest());
        catalogRef(d, v.challenge());
        graphRef(d, v.graph());
        string(d, v.family());
        string(d, v.variant());
        string(d, v.cohort() == null ? null : v.cohort().name());
        list(
                d,
                v.targets(),
                (d2, v2) -> {
                    behaviorTarget(d2, v2);
                });
        list(
                d,
                v.prerequisites(),
                (d2, v2) -> {
                    skillRef(d2, v2);
                });
        d.writeInt(v.difficulty());
        d.writeInt(v.effortMinutes());
        catalogRef(d, v.rubric());
        string(d, v.manifestSha());
        string(d, v.rubricSha());
        string(d, v.evaluator());
        string(d, v.runner());
        d.writeBoolean(v.available());
        string(d, v.limitation());
        d.writeBoolean(v.equivalent());
        d.writeInt(v.familyPresentations());
        d.writeInt(v.variantPresentations());
        d.writeInt(v.priorSubmissions());
        d.writeInt(v.highestHint());
        d.writeBoolean(v.solutionRevealed());
        d.writeBoolean(v.familiar());
        d.writeBoolean(v.baselineSkipped());
        d.writeBoolean(v.retryAllowed());
        d.writeInt(v.maximumPresentations());
    }

    private static Candidate candidate(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new Candidate(
                catalogRef(d),
                catalogRef(d),
                graphRef(d),
                string(d),
                string(d),
                enumeration(d, Cohort.class),
                list(d, d2 -> behaviorTarget(d2)),
                list(d, d2 -> skillRef(d2)),
                d.readInt(),
                d.readInt(),
                catalogRef(d),
                string(d),
                string(d),
                string(d),
                string(d),
                d.readBoolean(),
                string(d),
                d.readBoolean(),
                d.readInt(),
                d.readInt(),
                d.readInt(),
                d.readInt(),
                d.readBoolean(),
                d.readBoolean(),
                d.readBoolean(),
                d.readBoolean(),
                d.readInt());
    }

    private static void adaptationInput(DataOutputStream d, AdaptationInput v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        graphRef(d, v.graph());
        d.writeBoolean(v.graphAvailable());
        string(d, v.limitation());
        preferences(d, v.preferences());
        instant(d, v.asOf());
        string(d, v.policy());
        string(d, v.configuration());
        list(
                d,
                v.skills(),
                (d2, v2) -> {
                    skillState(d2, v2);
                });
        list(
                d,
                v.candidates(),
                (d2, v2) -> {
                    candidate(d2, v2);
                });
        d.writeBoolean(v.curriculumProblem() != null);
        if (v.curriculumProblem() != null) d.writeLong(v.curriculumProblem());
    }

    private static AdaptationInput adaptationInput(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new AdaptationInput(
                graphRef(d),
                d.readBoolean(),
                string(d),
                preferences(d),
                instant(d),
                string(d),
                string(d),
                list(d, d2 -> skillState(d2)),
                list(d, d2 -> candidate(d2)),
                (d.readBoolean() ? d.readLong() : null));
    }

    private static void learnerCommand(DataOutputStream d, LearnerCommand v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.id());
        string(d, v.kind() == null ? null : v.kind().name());
        string(d, v.decisionId());
        catalogRef(d, v.challenge());
        skillRef(d, v.goal());
        d.writeBoolean(v.budgetMinutes() != null);
        if (v.budgetMinutes() != null) d.writeInt(v.budgetMinutes());
        string(d, v.track() == null ? null : v.track().name());
        instant(d, v.deferUntil());
    }

    private static LearnerCommand learnerCommand(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new LearnerCommand(
                string(d),
                enumeration(d, CommandKind.class),
                string(d),
                catalogRef(d),
                skillRef(d),
                (d.readBoolean() ? d.readInt() : null),
                enumeration(d, Track.class),
                instant(d));
    }

    private static void selection(DataOutputStream d, Selection v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.action() == null ? null : v.action().name());
        catalogRef(d, v.quest());
        catalogRef(d, v.challenge());
        skillRef(d, v.skill());
        list(
                d,
                new TreeSet<>(v.behaviors()),
                (d2, v2) -> {
                    d2.writeBoolean(v2 != null);
                    if (v2 != null) d2.writeInt(v2);
                });
        d.writeInt(v.difficulty());
        d.writeInt(v.effortMinutes());
        instant(d, v.dueAt());
        d.writeBoolean(v.curriculumProblem() != null);
        if (v.curriculumProblem() != null) d.writeLong(v.curriculumProblem());
        d.writeBoolean(v.exploratory());
    }

    private static Selection selection(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new Selection(
                enumeration(d, Action.class),
                catalogRef(d),
                catalogRef(d),
                skillRef(d),
                new TreeSet<>(list(d, d2 -> (d2.readBoolean() ? d2.readInt() : null))),
                d.readInt(),
                d.readInt(),
                instant(d),
                (d.readBoolean() ? d.readLong() : null),
                d.readBoolean());
    }

    private static void explanation(DataOutputStream d, Explanation v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.code() == null ? null : v.code().name());
        string(d, v.message());
        list(
                d,
                v.evidenceIds(),
                (d2, v2) -> {
                    string(d2, v2);
                });
        list(
                d,
                v.estimateRevisions(),
                (d2, v2) -> {
                    d2.writeBoolean(v2 != null);
                    if (v2 != null) d2.writeLong(v2);
                });
    }

    private static Explanation explanation(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new Explanation(
                enumeration(d, Reason.class),
                string(d),
                list(d, d2 -> string(d2)),
                list(d, d2 -> (d2.readBoolean() ? d2.readLong() : null)));
    }

    private static void ranking(DataOutputStream d, Ranking v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.candidateKey());
        d.writeBoolean(v.eligible());
        d.writeBoolean(v.exploratoryEligible());
        d.writeInt(v.rank());
        list(
                d,
                v.exclusions(),
                (d2, v2) -> {
                    string(d2, v2 == null ? null : v2.name());
                });
        instant(d, v.dueAt());
    }

    private static Ranking ranking(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new Ranking(
                string(d),
                d.readBoolean(),
                d.readBoolean(),
                d.readInt(),
                list(d, d2 -> enumeration(d, Reason.class)),
                instant(d));
    }

    private static void nextQuestDecision(DataOutputStream d, NextQuestDecision v)
            throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.branch() == null ? null : v.branch().name());
        selection(d, v.primary());
        list(
                d,
                v.alternatives(),
                (d2, v2) -> {
                    selection(d2, v2);
                });
        list(
                d,
                v.candidates(),
                (d2, v2) -> {
                    ranking(d2, v2);
                });
        list(
                d,
                v.reasons(),
                (d2, v2) -> {
                    explanation(d2, v2);
                });
        list(
                d,
                v.permittedControls(),
                (d2, v2) -> {
                    string(d2, v2);
                });
    }

    private static NextQuestDecision nextQuestDecision(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new NextQuestDecision(
                enumeration(d, Branch.class),
                selection(d),
                list(d, d2 -> selection(d2)),
                list(d, d2 -> ranking(d2)),
                list(d, d2 -> explanation(d2)),
                list(d, d2 -> string(d2)));
    }

    private static void graphRef(DataOutputStream d, GraphRef v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.graphId());
        d.writeInt(v.graphVersion());
    }

    private static GraphRef graphRef(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new GraphRef(string(d), d.readInt());
    }

    private static void skillRef(DataOutputStream d, SkillRef v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.skillId());
        d.writeInt(v.definitionVersion());
    }

    private static SkillRef skillRef(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new SkillRef(string(d), d.readInt());
    }

    private static void catalogRef(DataOutputStream d, CatalogRef v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        string(d, v.id());
        d.writeInt(v.version());
    }

    private static CatalogRef catalogRef(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new CatalogRef(string(d), d.readInt());
    }

    private static void behaviorTarget(DataOutputStream d, BehaviorTarget v) throws IOException {
        d.writeBoolean(v != null);
        if (v == null) return;
        skillRef(d, v.skill());
        d.writeInt(v.behavior());
    }

    private static BehaviorTarget behaviorTarget(DataInputStream d) throws IOException {
        if (!d.readBoolean()) return null;
        return new BehaviorTarget(skillRef(d), d.readInt());
    }

    static byte[] encode(AdaptationInput v) {
        return encode(v, AdaptationCodec::adaptationInput);
    }

    static AdaptationInput decodeAdaptationInput(byte[] b) {
        return decode(b, AdaptationCodec::adaptationInput, AdaptationCodec::adaptationInput);
    }

    static byte[] encode(Preferences v) {
        return encode(v, AdaptationCodec::preferences);
    }

    static Preferences decodePreferences(byte[] b) {
        return decode(b, AdaptationCodec::preferences, AdaptationCodec::preferences);
    }

    static byte[] encode(LearnerCommand v) {
        return encode(v, AdaptationCodec::learnerCommand);
    }

    static LearnerCommand decodeLearnerCommand(byte[] b) {
        return decode(b, AdaptationCodec::learnerCommand, AdaptationCodec::learnerCommand);
    }

    static byte[] encode(NextQuestDecision v) {
        return encode(v, AdaptationCodec::nextQuestDecision);
    }

    static NextQuestDecision decodeNextQuestDecision(byte[] b) {
        return decode(b, AdaptationCodec::nextQuestDecision, AdaptationCodec::nextQuestDecision);
    }
}

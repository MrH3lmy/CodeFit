package com.codefit.evolution.mastery;

import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.DurableEvaluationRepository.CheckSummary;
import com.codefit.evolution.evaluation.EvaluationTypes.CheckStatus;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.mastery.EstimateInput.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.*;

import java.io.*;
import java.time.Instant;
import java.util.*;

/** Canonical, bounded metadata encoding. Historical policy inputs contain no learner artifact. */
public final class EstimateCodec {
    private EstimateCodec() {}

    private static final int MAX_BYTES = 32 * 1024 * 1024, MAX_ITEMS = 10000;

    public static byte[] encode(EstimateInput in) {
        try {
            var bytes = new ByteArrayOutputStream();
            var d = new DataOutputStream(bytes);
            d.writeInt(1);
            d.writeUTF(in.graph().graphId());
            d.writeInt(in.graph().graphVersion());
            skill(d, in.skill().ref());
            d.writeUTF(in.skill().domainId());
            d.writeUTF(in.skill().name());
            d.writeUTF(in.skill().description());
            strings(d, in.skill().observableBehaviors());
            d.writeUTF(in.skill().lifecycle().name());
            d.writeUTF(in.epoch().id());
            instant(d, in.epoch().startedAt());
            d.writeLong(in.epoch().evidenceThrough());
            instant(d, in.asOf());
            count(d, in.mappings().size());
            for (var m : in.mappings()) mapping(d, m);
            count(d, in.observations().size());
            for (var o : in.observations()) {
                d.writeUTF(o.id());
                d.writeLong(o.sequence());
                d.writeLong(o.firstSequence());
                mapping(d, o.mapping());
                byte[] facts = EvidenceCodec.encode(o.facts());
                d.writeInt(facts.length);
                d.write(facts);
                strings(d, o.exclusions());
                var c = o.context();
                d.writeBoolean(c != null);
                if (c != null) {
                    ref(d, c.quest());
                    ref(d, c.challenge());
                    d.writeUTF(c.family());
                    d.writeUTF(c.variant());
                    d.writeUTF(c.cohort().name());
                    d.writeBoolean(c.novel());
                    d.writeBoolean(c.heldOutEquivalent());
                    d.writeUTF(c.presentationId());
                    instant(d, c.presentedAt());
                    count(d, c.checks().size());
                    for (var ch : c.checks()) {
                        d.writeUTF(ch.id());
                        d.writeUTF(ch.kind().name());
                        d.writeBoolean(ch.required());
                        count(d, ch.targets().size());
                        for (var t : ch.targets()) {
                            skill(d, t.skill());
                            d.writeInt(t.behavior());
                        }
                        d.writeUTF(ch.status().name());
                        d.writeInt(ch.runCount());
                        d.writeUTF(ch.limitations());
                    }
                }
            }
            count(d, in.activities().size());
            for (var a : in.activities()) {
                d.writeUTF(a.id());
                d.writeBoolean(a.at() != null);
                if (a.at() != null) instant(d, a.at());
                count(d, a.behaviors().size());
                for (int b : new TreeSet<>(a.behaviors())) d.writeInt(b);
            }
            strings(d, in.unavailableMappings());
            d.flush();
            if (bytes.size() > MAX_BYTES)
                throw new IllegalArgumentException("Estimate input exceeds bounded snapshot size");
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid estimate metadata", e);
        }
    }

    public static EstimateInput decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid snapshot size");
        try {
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            if (d.readInt() != 1)
                throw new IllegalArgumentException("Unsupported estimate input version");
            var graph = new GraphRef(d.readUTF(), d.readInt());
            var skill =
                    new SkillDefinition(
                            skill(d),
                            d.readUTF(),
                            d.readUTF(),
                            d.readUTF(),
                            strings(d),
                            SkillLifecycle.valueOf(d.readUTF()));
            var epoch = new Epoch(d.readUTF(), instant(d), d.readLong());
            var asOf = instant(d);
            var mappings = new ArrayList<EvidenceMappingRef>();
            int n = count(d);
            for (int i = 0; i < n; i++) mappings.add(mapping(d));
            var obs = new ArrayList<Observation>();
            n = count(d);
            for (int i = 0; i < n; i++) {
                String id = d.readUTF();
                long seq = d.readLong();
                long firstSequence = d.readLong();
                var mapping = mapping(d);
                int len = d.readInt();
                if (len < 0 || len > 65536)
                    throw new IllegalArgumentException("Invalid observation size");
                byte[] fact = d.readNBytes(len);
                if (fact.length != len) throw new EOFException();
                var facts = EvidenceCodec.decode(fact);
                var exclusions = strings(d);
                Context context = null;
                if (d.readBoolean()) {
                    var quest = ref(d);
                    var challenge = ref(d);
                    String family = d.readUTF(), variant = d.readUTF();
                    var cohort = Cohort.valueOf(d.readUTF());
                    boolean novel = d.readBoolean(), equivalent = d.readBoolean();
                    String presentation = d.readUTF();
                    var presentedAt = instant(d);
                    var checks = new ArrayList<CheckSummary>();
                    int k = count(d);
                    for (int j = 0; j < k; j++) {
                        String checkId = d.readUTF();
                        var kind = CheckKind.valueOf(d.readUTF());
                        boolean required = d.readBoolean();
                        var targets = new ArrayList<BehaviorTarget>();
                        int m = count(d);
                        for (int t = 0; t < m; t++)
                            targets.add(new BehaviorTarget(skill(d), d.readInt()));
                        checks.add(
                                new CheckSummary(
                                        checkId,
                                        kind,
                                        required,
                                        targets,
                                        CheckStatus.valueOf(d.readUTF()),
                                        d.readInt(),
                                        d.readUTF()));
                    }
                    context =
                            new Context(
                                    quest,
                                    challenge,
                                    family,
                                    variant,
                                    cohort,
                                    novel,
                                    equivalent,
                                    presentation,
                                    presentedAt,
                                    checks);
                }
                obs.add(
                        new Observation(
                                id, seq, mapping, facts, exclusions, context, firstSequence));
            }
            var activities = new ArrayList<Activity>();
            n = count(d);
            for (int i = 0; i < n; i++) {
                String id = d.readUTF();
                var at = d.readBoolean() ? instant(d) : null;
                var behaviors = new TreeSet<Integer>();
                int k = count(d);
                for (int j = 0; j < k; j++) behaviors.add(d.readInt());
                activities.add(new Activity(id, at, behaviors));
            }
            var in =
                    new EstimateInput(
                            graph, skill, epoch, asOf, mappings, obs, activities, strings(d));
            if (d.available() != 0 || !Arrays.equals(bytes, encode(in)))
                throw new IllegalArgumentException("Noncanonical estimate metadata");
            return in;
        } catch (IOException | java.time.DateTimeException e) {
            throw new IllegalArgumentException("Invalid estimate metadata", e);
        }
    }

    private static void count(DataOutputStream d, int n) throws IOException {
        if (n < 0 || n > MAX_ITEMS)
            throw new IllegalArgumentException("Bounded input page set required");
        d.writeInt(n);
    }

    private static int count(DataInputStream d) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > MAX_ITEMS) throw new IllegalArgumentException("Invalid count");
        return n;
    }

    private static void strings(DataOutputStream d, List<String> list) throws IOException {
        count(d, list.size());
        for (String s : list) d.writeUTF(s);
    }

    private static List<String> strings(DataInputStream d) throws IOException {
        var l = new ArrayList<String>();
        int n = count(d);
        for (int i = 0; i < n; i++) l.add(d.readUTF());
        return l;
    }

    private static void instant(DataOutputStream d, Instant at) throws IOException {
        d.writeUTF(at.toString());
    }

    private static Instant instant(DataInputStream d) throws IOException {
        return Instant.parse(d.readUTF());
    }

    private static void skill(DataOutputStream d, SkillRef s) throws IOException {
        d.writeUTF(s.skillId());
        d.writeInt(s.definitionVersion());
    }

    private static SkillRef skill(DataInputStream d) throws IOException {
        return new SkillRef(d.readUTF(), d.readInt());
    }

    private static void ref(DataOutputStream d, CatalogRef r) throws IOException {
        d.writeUTF(r.id());
        d.writeInt(r.version());
    }

    private static CatalogRef ref(DataInputStream d) throws IOException {
        return new CatalogRef(d.readUTF(), d.readInt());
    }

    private static void mapping(DataOutputStream d, EvidenceMappingRef r) throws IOException {
        d.writeUTF(r.mappingId());
        d.writeInt(r.version());
    }

    private static EvidenceMappingRef mapping(DataInputStream d) throws IOException {
        return new EvidenceMappingRef(d.readUTF(), d.readInt());
    }
}

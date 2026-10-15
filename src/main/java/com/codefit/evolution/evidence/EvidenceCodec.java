package com.codefit.evolution.evidence;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import java.io.*;
import java.time.Instant;
import java.util.*;

/** Private bounded metadata encoding; never Java object deserialization or source content. */
public final class EvidenceCodec {
    private EvidenceCodec() {}

    public static byte[] encode(EvidenceObservation o) {
        try {
            var bytes = new ByteArrayOutputStream();
            var d = new DataOutputStream(bytes);
            d.writeInt(EvidenceObservation.CONTRACT_VERSION);
            d.writeUTF(o.source().type().name());
            d.writeLong(o.source().recordId());
            d.writeInt(o.source().revision());
            string(d, o.time().original());
            string(d, o.time().observedAt() == null ? null : o.time().observedAt().toString());
            d.writeUTF(o.time().basis().name());
            d.writeUTF(o.provenance().name());
            d.writeUTF(o.outcome().name());
            d.writeUTF(o.assistance().name());
            d.writeInt(o.hintLevel() == null ? -1 : o.hintLevel());
            d.writeUTF(o.exposure().name());
            var limits = o.limitations().stream().sorted().toList();
            d.writeInt(limits.size());
            for (var l : limits) d.writeUTF(l.name());
            d.writeUTF(o.availability().name());
            d.writeUTF(o.resultCode());
            d.writeUTF(o.originCode());
            d.writeUTF(o.correlationKey());
            var e = o.evaluation();
            d.writeBoolean(e != null);
            if (e != null) {
                d.writeUTF(e.submissionId());
                d.writeUTF(e.familyId());
                d.writeUTF(e.rubricVersion());
                d.writeUTF(e.evaluatorVersion());
                d.writeUTF(e.artifactSha256());
                d.writeInt(e.checkIds().size());
                for (var c : e.checkIds()) d.writeUTF(c);
                d.writeLong(e.problemAttemptId() == null ? 0 : e.problemAttemptId());
            }
            d.writeUTF(o.change().name());
            string(d, o.previousEvidenceId());
            d.writeUTF(o.reason().name());
            d.writeUTF(o.actor().name());
            d.flush();
            if (bytes.size() > 65536)
                throw new IllegalArgumentException("Evidence metadata too large");
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static EvidenceObservation decode(byte[] bytes) {
        if (bytes == null || bytes.length > 65536)
            throw new IllegalArgumentException("Invalid evidence metadata size");
        try {
            var d = new DataInputStream(new ByteArrayInputStream(bytes));
            if (d.readInt() != EvidenceObservation.CONTRACT_VERSION)
                throw new IllegalArgumentException("Unsupported evidence contract version");
            var source =
                    new EvidenceSourceRef(
                            SourceType.valueOf(d.readUTF()), d.readLong(), d.readInt());
            var original = string(d);
            var instant = string(d);
            var time =
                    new EvidenceTime(
                            original,
                            instant == null ? null : Instant.parse(instant),
                            TimeBasis.valueOf(d.readUTF()));
            var provenance = Provenance.valueOf(d.readUTF());
            var outcome = Outcome.valueOf(d.readUTF());
            var assistance = Assistance.valueOf(d.readUTF());
            int hint = d.readInt();
            if (hint < -1) throw new IllegalArgumentException("Invalid hint sentinel");
            var exposure = Exposure.valueOf(d.readUTF());
            int n = count(d, Limitation.values().length);
            var limits = EnumSet.noneOf(Limitation.class);
            for (int i = 0; i < n; i++)
                if (!limits.add(Limitation.valueOf(d.readUTF())))
                    throw new IllegalArgumentException("Duplicate limitation");
            var availability = Availability.valueOf(d.readUTF());
            var result = d.readUTF();
            var origin = d.readUTF();
            var correlation = d.readUTF();
            EvaluationReference eval = null;
            if (d.readBoolean()) {
                var submission = d.readUTF();
                var family = d.readUTF();
                var rubric = d.readUTF();
                var evaluator = d.readUTF();
                var hash = d.readUTF();
                n = count(d, 64);
                var checks = new ArrayList<String>();
                for (int i = 0; i < n; i++) checks.add(d.readUTF());
                long problem = d.readLong();
                eval =
                        new EvaluationReference(
                                submission,
                                family,
                                rubric,
                                evaluator,
                                hash,
                                checks,
                                problem == 0 ? null : problem);
            }
            var o =
                    new EvidenceObservation(
                            source,
                            time,
                            provenance,
                            outcome,
                            assistance,
                            hint < 0 ? null : hint,
                            exposure,
                            limits,
                            availability,
                            result,
                            origin,
                            correlation,
                            eval,
                            Change.valueOf(d.readUTF()),
                            string(d),
                            ChangeReason.valueOf(d.readUTF()),
                            Actor.valueOf(d.readUTF()));
            if (d.available() != 0) throw new IllegalArgumentException("Trailing evidence bytes");
            return o;
        } catch (IOException | java.time.DateTimeException e) {
            throw new IllegalArgumentException("Invalid evidence metadata", e);
        }
    }

    private static int count(DataInputStream d, int max) throws IOException {
        int n = d.readInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("Invalid count");
        return n;
    }

    private static void string(DataOutputStream d, String s) throws IOException {
        d.writeBoolean(s != null);
        if (s != null) d.writeUTF(s);
    }

    private static String string(DataInputStream d) throws IOException {
        return d.readBoolean() ? d.readUTF() : null;
    }
}

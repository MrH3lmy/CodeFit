package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.ChallengePresentation.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Explicit version-1 private snapshot format; bounded and canonical, never Java deserialization.
 */
public final class EvaluationCodec {
    private EvaluationCodec() {}

    private static final int MAX_BYTES = 2_000_000;

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static byte[] encode(Writer writer) {
        try (var bytes = new ByteArrayOutputStream();
                var out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            writer.write(out);
            out.flush();
            if (bytes.size() > MAX_BYTES)
                throw new IllegalArgumentException("Oversized evaluation payload");
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> T decode(byte[] bytes, Reader<T> reader) {
        if (bytes == null || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Oversized evaluation payload");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readInt() != 1)
                throw new IllegalArgumentException("Unsupported evaluation contract version");
            T result = reader.read(in);
            if (in.available() != 0)
                throw new IllegalArgumentException("Trailing evaluation bytes");
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed evaluation payload", e);
        }
    }

    private static void text(DataOutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > 400000) throw new IllegalArgumentException("Oversized field");
        o.writeInt(b.length);
        o.write(b);
    }

    private static String text(DataInputStream i) throws IOException {
        int n = i.readInt();
        if (n < 0 || n > 400000 || n > i.available())
            throw new IllegalArgumentException("Invalid field length");
        return new String(i.readNBytes(n), StandardCharsets.UTF_8);
    }

    private static int count(DataInputStream i, int max) throws IOException {
        int n = i.readInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("Invalid collection length");
        return n;
    }

    private static void number(DataOutputStream o, Integer n) throws IOException {
        o.writeInt(n == null ? -1 : n);
    }

    private static Integer number(DataInputStream i) throws IOException {
        int n = i.readInt();
        return n == -1 ? null : n;
    }

    private static void id(DataOutputStream o, Long n) throws IOException {
        o.writeLong(n == null ? 0 : n);
    }

    private static Long id(DataInputStream i) throws IOException {
        long n = i.readLong();
        if (n < 0) throw new IllegalArgumentException("Invalid identity");
        return n == 0 ? null : n;
    }

    private static void ref(DataOutputStream o, CatalogRef r) throws IOException {
        text(o, r.id());
        o.writeInt(r.version());
    }

    private static CatalogRef ref(DataInputStream i) throws IOException {
        return new CatalogRef(text(i), i.readInt());
    }

    private static void request(DataOutputStream o, SubmissionRequest r) throws IOException {
        text(o, r.id());
        text(o, r.presentationId());
        text(o, r.artifact());
        text(o, r.outsideAssistance().name());
        text(o, r.reflection());
    }

    private static SubmissionRequest request(DataInputStream i) throws IOException {
        return new SubmissionRequest(
                text(i), text(i), text(i), OutsideAssistance.valueOf(text(i)), text(i));
    }

    public static String requestDigest(SubmissionRequest r) {
        return CatalogCodec.digest(encode(o -> request(o, r)));
    }

    private static void exposure(DataOutputStream o, ExposureContext e) throws IOException {
        o.writeInt(e.familyPresentations());
        o.writeInt(e.variantPresentations());
        text(o, e.kind().name());
        o.writeInt(e.highestHint());
        o.writeBoolean(e.solutionRevealed());
        o.writeBoolean(e.knownFamiliar());
    }

    private static ExposureContext exposure(DataInputStream i) throws IOException {
        return new ExposureContext(
                i.readInt(),
                i.readInt(),
                PresentationKind.valueOf(text(i)),
                i.readInt(),
                i.readBoolean(),
                i.readBoolean());
    }

    private static void presentation(DataOutputStream o, ChallengePresentation p)
            throws IOException {
        text(o, p.id());
        ref(o, p.quest());
        ref(o, p.challenge());
        text(o, p.familyId());
        text(o, p.variantId());
        text(o, p.cohort().name());
        text(o, p.presentedAt().toString());
        text(o, p.declaration().name());
        exposure(o, p.prior());
    }

    private static ChallengePresentation presentation(DataInputStream i) throws IOException {
        return new ChallengePresentation(
                text(i),
                ref(i),
                ref(i),
                text(i),
                text(i),
                Cohort.valueOf(text(i)),
                Instant.parse(text(i)),
                Familiarity.valueOf(text(i)),
                exposure(i));
    }

    public static byte[] encode(SubmissionSnapshot s) {
        return encode(
                o -> {
                    request(o, s.request());
                    presentation(o, s.presentation());
                    text(o, s.questManifest());
                    text(o, s.challengeManifest());
                    id(o, s.problemId());
                    id(o, s.assessmentItemId());
                    exposure(o, s.exposureAtSubmission());
                    o.writeInt(s.priorFamilySubmissions());
                    o.writeInt(s.priorVariantSubmissions());
                    text(o, s.submittedAt().toString());
                    text(o, s.evaluatorVersion());
                    o.writeInt(s.profile().compileSeconds());
                    o.writeInt(s.profile().memoryMb());
                    o.writeInt(s.profile().outputBytes());
                    o.writeInt(s.profile().observedRepetitions());
                    text(o, s.executableSource());
                    number(o, s.readingSeconds());
                    number(o, s.thinkingSeconds());
                    number(o, s.codingSeconds());
                    number(o, s.debuggingSeconds());
                    text(o, s.legacyHint() == null ? "NONE" : s.legacyHint().name());
                });
    }

    public static SubmissionSnapshot submission(byte[] bytes) {
        var s =
                decode(
                        bytes,
                        i ->
                                new SubmissionSnapshot(
                                        request(i),
                                        presentation(i),
                                        text(i),
                                        text(i),
                                        id(i),
                                        id(i),
                                        exposure(i),
                                        i.readInt(),
                                        i.readInt(),
                                        Instant.parse(text(i)),
                                        text(i),
                                        new RunnerProfile(
                                                i.readInt(), i.readInt(), i.readInt(), i.readInt()),
                                        text(i),
                                        number(i),
                                        number(i),
                                        number(i),
                                        number(i),
                                        legacyHint(i)));
        if (!Arrays.equals(bytes, encode(s)))
            throw new IllegalArgumentException("Noncanonical submission bytes");
        return s;
    }

    private static com.codefit.model.HintLevel legacyHint(DataInputStream in) throws IOException {
        String tag = text(in);
        return tag.equals("NONE") ? null : com.codefit.model.HintLevel.valueOf(tag);
    }

    public static byte[] encode(EvaluationResult r) {
        return encode(
                o -> {
                    text(o, r.outcome().name());
                    o.writeInt(r.checks().size());
                    for (var c : r.checks()) {
                        text(o, c.checkId());
                        text(o, c.status().name());
                        text(o, c.detail());
                        o.writeInt(c.runs().size());
                        for (var run : c.runs()) {
                            text(o, run.caseId());
                            o.writeInt(run.repetition());
                            text(o, run.status().name());
                            number(o, run.exitCode());
                            text(o, run.stdoutSha256());
                            text(o, run.stderrSha256());
                            text(o, run.stdout());
                            text(o, run.stderr());
                            o.writeLong(run.elapsedMillis());
                        }
                    }
                    text(o, r.diagnostics());
                    text(o, r.startedAt().toString());
                    text(o, r.finishedAt().toString());
                    text(o, r.evaluatorVersion());
                    text(o, r.runtime());
                });
    }

    public static EvaluationResult result(byte[] bytes) {
        var r =
                decode(
                        bytes,
                        i -> {
                            Outcome outcome = Outcome.valueOf(text(i));
                            List<CheckResult> checks = new ArrayList<>();
                            int n = count(i, 64);
                            for (int a = 0; a < n; a++) {
                                String id = text(i);
                                CheckStatus status = CheckStatus.valueOf(text(i));
                                String detail = text(i);
                                List<CheckResult.RunObservation> runs = new ArrayList<>();
                                int size = count(i, 80);
                                for (int b = 0; b < size; b++)
                                    runs.add(
                                            new CheckResult.RunObservation(
                                                    text(i),
                                                    i.readInt(),
                                                    CheckStatus.valueOf(text(i)),
                                                    number(i),
                                                    text(i),
                                                    text(i),
                                                    text(i),
                                                    text(i),
                                                    i.readLong()));
                                checks.add(new CheckResult(id, status, detail, runs));
                            }
                            return new EvaluationResult(
                                    outcome,
                                    checks,
                                    text(i),
                                    Instant.parse(text(i)),
                                    Instant.parse(text(i)),
                                    text(i),
                                    text(i));
                        });
        if (!Arrays.equals(bytes, encode(r)))
            throw new IllegalArgumentException("Noncanonical result bytes");
        return r;
    }
}

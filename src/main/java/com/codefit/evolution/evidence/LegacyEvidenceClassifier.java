package com.codefit.evolution.evidence;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Projects only persisted facts. No legacy correctness helpers or mutable progress assistance. */
public final class LegacyEvidenceClassifier {
    private LegacyEvidenceClassifier() {}

    public static EvidenceObservation problem(
            long id, long problem, int attempt, String result, String origin, String timestamp) {
        var limits =
                EnumSet.of(
                        Limitation.MANUAL_JUDGE_RESULT,
                        Limitation.ASSISTANCE_UNRECORDED,
                        Limitation.EXPOSURE_UNRECORDED,
                        Limitation.CURRENT_TOPIC_METADATA);
        String safeOrigin =
                Set.of("FRESH_ATTEMPT", "PREVIOUSLY_SOLVED", "IMPORTED", "UNKNOWN")
                                .contains(Objects.toString(origin, ""))
                        ? origin
                        : "UNKNOWN";
        var basis =
                switch (safeOrigin) {
                    case "FRESH_ATTEMPT" -> TimeBasis.LEGACY_SQLITE_UTC;
                    case "IMPORTED" -> TimeBasis.IMPORT_TIME_NOT_EVENT_TIME;
                    default -> TimeBasis.UNKNOWN_COMPLETION_TIME;
                };
        String code =
                Set.of("AC", "ACX", "WA", "TLE", "MLE", "RTE", "CS", "SKIPPED")
                                .contains(Objects.toString(result, ""))
                        ? result
                        : "UNKNOWN";
        var outcome =
                switch (code) {
                    case "AC", "ACX" -> Outcome.PASS;
                    case "WA", "TLE", "MLE", "RTE", "CS" -> Outcome.FAIL;
                    default -> Outcome.UNAVAILABLE;
                };
        return observation(
                SourceType.PROBLEM_ATTEMPT,
                id,
                time(timestamp, basis, limits),
                Provenance.SELF_REPORTED,
                outcome,
                Assistance.UNKNOWN,
                attempt > 1 || "ACX".equals(code) ? Exposure.REPEAT : Exposure.UNKNOWN,
                limits,
                code,
                safeOrigin,
                "problem:" + problem);
    }

    public static EvidenceObservation review(
            long id,
            long card,
            String validation,
            String rating,
            boolean hint,
            boolean hintRecorded,
            boolean boss,
            String timestamp) {
        var limits =
                EnumSet.of(
                        Limitation.CURRENT_CATEGORY_METADATA,
                        Limitation.OUTSIDE_ASSISTANCE_UNKNOWN,
                        Limitation.NO_ARTIFACT_SNAPSHOT);
        var assistance =
                hintRecorded
                        ? (hint ? Assistance.HINT : Assistance.NO_IN_APP_HINT_RECORDED)
                        : Assistance.UNKNOWN;
        if (!hintRecorded) limits.add(Limitation.ASSISTANCE_UNRECORDED);
        if (boss) limits.add(Limitation.BOSS_FAMILIAR_REVIEW);
        String code = Objects.toString(validation, "");
        Provenance provenance;
        Outcome outcome;
        switch (code) {
            case "EXACT", "CLOSE_SPACING", "JAVA_CORRECT" -> {
                provenance = Provenance.OBJECTIVE_REVIEW;
                outcome = Outcome.PASS;
            }
            case "DIFFERENT",
                    "JAVA_WRONG_OUTPUT",
                    "JAVA_UNEXPECTED_EXCEPTION",
                    "JAVA_MISSING_EXPECTED_EXCEPTION" -> {
                provenance = Provenance.OBJECTIVE_REVIEW;
                outcome = Outcome.FAIL;
            }
            case "SUBJECTIVE" -> {
                provenance = Provenance.SELF_REPORTED;
                outcome = ratingOutcome(rating);
            }
            case "TIMED_OUT",
                    "TIMED_OUT_WITH_ATTEMPT",
                    "EMPTY",
                    "JAVA_PENDING",
                    "JAVA_COMPILE_ERROR",
                    "JAVA_TIMEOUT",
                    "JAVA_REJECTED_UNSAFE_SNIPPET",
                    "JAVA_SANDBOX_UNAVAILABLE" -> {
                provenance = Provenance.LEGACY_UNSPECIFIED;
                outcome = Outcome.UNAVAILABLE;
                limits.add(Limitation.INCONCLUSIVE_EXECUTION);
            }
            default -> {
                code = "UNKNOWN";
                provenance = Provenance.LEGACY_UNSPECIFIED;
                outcome = ratingOutcome(rating);
                limits.add(Limitation.LEGACY_RATING_FALLBACK);
            }
        }
        if (provenance == Provenance.OBJECTIVE_REVIEW)
            limits.add(Limitation.LEGACY_VALIDATOR_SCOPE);
        return observation(
                SourceType.REVIEW,
                id,
                time(timestamp, TimeBasis.LEGACY_SQLITE_UTC, limits),
                provenance,
                outcome,
                assistance,
                Exposure.FAMILIAR_RECALL,
                limits,
                code,
                "REVIEW",
                "card:" + card);
    }

    public static EvidenceObservation assessment(
            long id, long item, boolean correct, String timestamp) {
        var limits =
                EnumSet.of(
                        Limitation.ASSESSMENT_GRADE_UNSPECIFIED,
                        Limitation.ASSISTANCE_UNRECORDED,
                        Limitation.EXPOSURE_UNRECORDED);
        return observation(
                SourceType.ASSESSMENT_ATTEMPT,
                id,
                time(timestamp, TimeBasis.LEGACY_SQLITE_UTC, limits),
                Provenance.LEGACY_UNSPECIFIED,
                correct ? Outcome.PASS : Outcome.FAIL,
                Assistance.UNKNOWN,
                Exposure.UNKNOWN,
                limits,
                correct ? "CORRECT" : "INCORRECT",
                "ASSESSMENT",
                "assessment:" + item);
    }

    private static Outcome ratingOutcome(String rating) {
        return switch (Objects.toString(rating, "")) {
            case "GOOD", "EASY" -> Outcome.PASS;
            case "AGAIN" -> Outcome.FAIL;
            case "HARD" -> Outcome.UNAVAILABLE;
            default -> Outcome.UNAVAILABLE;
        };
    }

    private static EvidenceObservation observation(
            SourceType type,
            long id,
            EvidenceTime time,
            Provenance p,
            Outcome o,
            Assistance a,
            Exposure e,
            Set<Limitation> l,
            String result,
            String origin,
            String key) {
        return new EvidenceObservation(
                new EvidenceSourceRef(type, id, 1),
                time,
                p,
                o,
                a,
                null,
                e,
                l,
                Availability.AVAILABLE,
                result,
                origin,
                key,
                null,
                Change.ORIGINAL,
                null,
                ChangeReason.ORIGINAL_RECORD,
                Actor.SYSTEM);
    }

    private static EvidenceTime time(String raw, TimeBasis basis, Set<Limitation> limits) {
        // SQL CURRENT_TIMESTAMP is UTC; this is an explicit legacy assumption, not a reconstructed
        // event clock.
        if (basis != TimeBasis.LEGACY_SQLITE_UTC)
            return new EvidenceTime(bounded(raw), null, basis);
        limits.add(Limitation.LEGACY_TIMESTAMP_ASSUMPTION);
        try {
            return new EvidenceTime(
                    raw,
                    LocalDateTime.parse(
                                    raw,
                                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                                            .withResolverStyle(
                                                    java.time.format.ResolverStyle.STRICT))
                            .toInstant(ZoneOffset.UTC),
                    basis);
        } catch (RuntimeException invalid) {
            limits.add(Limitation.MALFORMED_TIMESTAMP);
            return new EvidenceTime(bounded(raw), null, TimeBasis.UNKNOWN);
        }
    }

    private static String bounded(String s) {
        return s == null ? null : s.substring(0, Math.min(s.length(), 80));
    }
}

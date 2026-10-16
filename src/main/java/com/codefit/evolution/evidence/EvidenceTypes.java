package com.codefit.evolution.evidence;

/** Independent dimensions; none of these values is a proficiency estimate. */
public final class EvidenceTypes {
    private EvidenceTypes() {}

    public enum SourceType {
        PROBLEM_ATTEMPT,
        REVIEW,
        ASSESSMENT_ATTEMPT,
        CHALLENGE_EVALUATION;

        /** A source row has one canonical event kind, never one event per check or adapter. */
        public String eventKind() {
            return this == CHALLENGE_EVALUATION ? "EVALUATION_RECORDED" : "ATTEMPT_RECORDED";
        }
    }

    public enum Provenance {
        LOCAL_CHECK,
        OBJECTIVE_REVIEW,
        SELF_REPORTED,
        LEGACY_UNSPECIFIED,
        AUTHENTICATED_EXTERNAL
    }

    public enum Outcome {
        PASS,
        FAIL,
        PARTIAL,
        UNAVAILABLE
    }

    public enum Assistance {
        UNKNOWN,
        HINT,
        NO_IN_APP_HINT_RECORDED,
        NONE_OBSERVED,
        EXPLANATION_OR_SOLUTION,
        DECLARED_EXTERNAL
    }

    public enum Exposure {
        UNKNOWN,
        FAMILIAR_RECALL,
        REPEAT,
        RELATED_VARIANT,
        FIRST_PRESENTATION,
        HELD_OUT_TRANSFER,
        DELAYED_RETENTION
    }

    public enum Availability {
        AVAILABLE,
        SOURCE_UNAVAILABLE,
        RETRACTED,
        UNSUPPORTED_VERSION
    }

    public enum TimeBasis {
        EXACT_UTC,
        LEGACY_SQLITE_UTC,
        IMPORT_TIME_NOT_EVENT_TIME,
        UNKNOWN_COMPLETION_TIME,
        UNKNOWN
    }

    public enum Change {
        ORIGINAL,
        SUPERSESSION,
        RETRACTION
    }

    public enum ChangeReason {
        ORIGINAL_RECORD,
        SOURCE_CORRECTED,
        PROVENANCE_CORRECTED,
        BROKEN_CHECK,
        LEARNER_RETRACTED
    }

    public enum Actor {
        SYSTEM,
        LEARNER
    }

    public enum Limitation {
        MANUAL_JUDGE_RESULT,
        LEGACY_RATING_FALLBACK,
        ASSESSMENT_GRADE_UNSPECIFIED,
        ASSISTANCE_UNRECORDED,
        OUTSIDE_ASSISTANCE_UNKNOWN,
        EXPOSURE_UNRECORDED,
        CURRENT_CATEGORY_METADATA,
        CURRENT_TOPIC_METADATA,
        LEGACY_VALIDATOR_SCOPE,
        NO_ARTIFACT_SNAPSHOT,
        LEGACY_TIMESTAMP_ASSUMPTION,
        INCONCLUSIVE_EXECUTION,
        MALFORMED_TIMESTAMP,
        BOUNDED_RUBRIC_SCOPE,
        FINITE_CONCURRENCY_OBSERVATIONS,
        BOSS_FAMILIAR_REVIEW
    }

    public enum Exclusion {
        SUPERSEDED,
        RETRACTED,
        MAPPING_SUPERSEDED,
        SOURCE_UNAVAILABLE,
        SOURCE_CHANGED,
        EVALUATION_AUTHORITATIVE
    }

    public enum AppendResult {
        INSERTED,
        DUPLICATE
    }
}

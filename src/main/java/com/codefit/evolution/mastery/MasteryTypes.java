package com.codefit.evolution.mastery;

/** Ordinal V1 vocabulary. Support describes evidence coverage, never a probability. */
public final class MasteryTypes {
    private MasteryTypes() {}

    public enum Band {
        UNKNOWN,
        DEVELOPING,
        INDEPENDENT,
        TRANSFER_SUPPORTED,
        RETENTION_SUPPORTED
    }

    public enum Support {
        INSUFFICIENT,
        LIMITED,
        DIVERSE
    }

    public enum Freshness {
        NO_OBSERVATION,
        CURRENT,
        STALE
    }

    public enum ReasonCode {
        NO_DIRECT_EVIDENCE,
        UNAVAILABLE_SOURCE,
        SUPERSEDED,
        MAPPING_UNAVAILABLE,
        UNKNOWN_TIME,
        FUTURE_EVENT,
        BEFORE_EPOCH,
        CORRELATED,
        UNCERTAIN_PROVENANCE,
        UNAVAILABLE_EVALUATION,
        INVALID_EVALUATION,
        ASSISTED,
        UNKNOWN_ASSISTANCE,
        FAMILIAR,
        UNKNOWN_EXPOSURE,
        DIAGNOSTIC_SCOPE,
        MISSING_BEHAVIORS,
        OBSERVED_PROGRESS,
        INDEPENDENT_OPPORTUNITY,
        NEED_DIVERSITY,
        INDEPENDENT_FAMILIES,
        TRANSFER,
        RETENTION,
        RETENTION_NOT_DELAYED,
        CONTRADICTED,
        REPEATED_FAILURE,
        RESTORATION_PENDING,
        STALE,
        INDEPENDENT_VALIDATION_DUE,
        CURRENT_SCOPE
    }
}

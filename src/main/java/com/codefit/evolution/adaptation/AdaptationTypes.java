package com.codefit.evolution.adaptation;

/** Ordered policy branches are a public UI/journal contract, never numeric ability scores. */
public final class AdaptationTypes {
    private AdaptationTypes() {}

    public enum Track {
        EVOLUTION,
        CURRICULUM
    }

    public enum Action {
        NONE,
        BASELINE,
        INTRODUCTION,
        DECOMPOSE,
        RETRY,
        CONSOLIDATE,
        FRESH_VALIDATION,
        RETENTION,
        TRANSFER,
        DIVERSIFY,
        PROGRESS,
        CURRICULUM,
        EXPLORATORY
    }

    public enum Branch {
        PAUSED,
        UNAVAILABLE,
        BASELINE,
        REPEATED_FAILURE,
        SINGLE_FAILURE,
        ASSISTED,
        UNCERTAIN,
        RETENTION,
        TRANSFER,
        DIVERSITY,
        SUPPORTED,
        DEPLETED,
        CURRICULUM
    }

    public enum Reason {
        PAUSED,
        CONTEXT_UNAVAILABLE,
        UNKNOWN_BASELINE,
        REPEATED_FAILURE,
        SINGLE_FAILURE,
        ASSISTED_SUCCESS,
        UNCERTAIN_SUPPORT,
        RETENTION_DUE,
        RETENTION_NOT_DUE,
        TRANSFER_READY,
        NEED_DIVERSITY,
        SUPPORTED_SCOPE,
        CATALOG_DEPLETED,
        CURRICULUM_FRONTIER,
        WRONG_GRAPH,
        WRONG_BEHAVIOR,
        RESERVED_COHORT,
        PREREQUISITE,
        FAMILIAR,
        DECLINED,
        DEFERRED,
        BUDGET,
        UNAVAILABLE,
        DIFFICULTY,
        BASELINE_SKIPPED,
        WRONG_ROLE,
        RETRY_EXHAUSTED,
        NO_INDEPENDENT_SUPPORT,
        NO_EQUIVALENCE,
        REST_OR_CHANGE_DIRECTION,
        EXPLORATORY_ONLY,
        FUTURE_ACTIVITY
    }

    public enum CommandKind {
        ACCEPT,
        DECLINE,
        DEFER,
        RESUME_CANDIDATE,
        PAUSE,
        RESUME,
        CHANGE_GOAL,
        CHANGE_BUDGET,
        SWITCH_TRACK,
        CHOOSE_ALTERNATIVE,
        EXPLORE
    }
}

package com.codefit.evolution.quest;

public final class QuestTypes {
    private QuestTypes() {}

    public enum Cohort {
        BASELINE,
        PRACTICE,
        TRANSFER,
        RETENTION,
        PILOT_POST
    }

    public enum Runner {
        JAVA21_MAIN,
        KEYED_RESPONSE
    }

    public enum CheckKind {
        DETERMINISTIC_IO,
        KEYED_DIAGNOSTIC,
        CONTROLLED_TRACE,
        OBSERVED_CONCURRENCY,
        TERMINATION,
        REFLECTION
    }

    public enum Visibility {
        PUBLIC,
        AFTER_EVALUATION
    }

    public enum AssetKind {
        PROMPT,
        STARTER,
        HARNESS,
        REFERENCE,
        MUTANT,
        HINT,
        DATA
    }

    public enum Familiarity {
        UNKNOWN,
        DECLARED_UNFAMILIAR,
        DECLARED_FAMILIAR
    }

    public enum PresentationKind {
        FIRST_PRESENTATION,
        REPEAT,
        RELATED_VARIANT
    }

    public enum ExposureAction {
        PRACTICE_CHECK,
        HINT,
        SOLUTION,
        SKIP,
        FAMILIARITY_DECLARED
    }
}

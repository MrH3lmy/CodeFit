package com.codefit.evolution.evaluation;

/** Execution outcomes are independent of proficiency, rewards and external judge verdicts. */
public final class EvaluationTypes {
    private EvaluationTypes() {}

    public enum Stage {
        PREPARED,
        RUNNING,
        EVALUATED,
        FINALIZED
    }

    public enum Outcome {
        PASSED,
        FAILED,
        PARTIAL,
        COMPILE_ERROR,
        RUNTIME_ERROR,
        RESOURCE_EXHAUSTED,
        TIMED_OUT,
        CANCELLED,
        INCONCLUSIVE,
        UNSUPPORTED,
        EVALUATOR_ERROR,
        TOOL_UNAVAILABLE
    }

    public enum CheckStatus {
        NOT_RUN,
        PASSED,
        FAILED,
        RUNTIME_ERROR,
        RESOURCE_EXHAUSTED,
        TIMED_OUT,
        CANCELLED,
        INCONCLUSIVE,
        UNSUPPORTED,
        EVALUATOR_ERROR,
        UNGRADED
    }

    public enum OutsideAssistance {
        UNKNOWN,
        NONE_DECLARED,
        DECLARED
    }
}

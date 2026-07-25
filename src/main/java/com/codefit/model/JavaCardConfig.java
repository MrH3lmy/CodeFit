package com.codefit.model;

/**
 * Exercise definition for a {@link CardType#JAVA_CODE} card, decoded from the card's
 * {@code acceptedAnswers} column by {@code JavaExerciseCodec}. {@code template} is a full,
 * app-authored Java source file containing a single {@code __LEARNER_SOLUTION__} placeholder that
 * the learner's attempt is substituted into before compiling — the learner never controls the
 * class name, imports, or surrounding scaffolding, keeping the exercise a bounded fill-in-the-blank
 * rather than an arbitrary program.
 *
 * <p>Grading compares the sandboxed run's stdout against {@code expectedOutput} when
 * {@code expectedExceptionSimpleName} is blank, or checks that the run threw an exception whose
 * simple class name matches otherwise. Exactly one of the two is expected to apply per exercise.
 */
public record JavaCardConfig(String template, String expectedOutput, String expectedExceptionSimpleName,
                              int timeoutSeconds, int memoryLimitMb) {

    public static final String SOLUTION_PLACEHOLDER = "__LEARNER_SOLUTION__";
    public static final String MAIN_CLASS_NAME = "Solution";

    public JavaCardConfig {
        if (template == null || !template.contains(SOLUTION_PLACEHOLDER)) {
            throw new IllegalArgumentException("template must contain the " + SOLUTION_PLACEHOLDER + " placeholder.");
        }
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive.");
        }
        if (memoryLimitMb <= 0) {
            throw new IllegalArgumentException("memoryLimitMb must be positive.");
        }
    }

    public boolean expectsException() {
        return expectedExceptionSimpleName != null && !expectedExceptionSimpleName.isBlank();
    }

    public String assembleSource(String learnerAttempt) {
        return template.replace(SOLUTION_PLACEHOLDER, learnerAttempt == null ? "" : learnerAttempt);
    }
}

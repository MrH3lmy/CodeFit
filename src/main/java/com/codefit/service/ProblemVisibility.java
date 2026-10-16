package com.codefit.service;

import com.codefit.model.Problem;
import com.codefit.repository.ProblemRepository;

/** Ordinary practice routes never present Evolution content, including public practice variants. */
public final class ProblemVisibility {
    public static final String EVOLUTION_PLATFORM = "CODEFIT_EVOLUTION";

    private ProblemVisibility() {}

    public static boolean ordinary(Problem problem) {
        return !EVOLUTION_PLATFORM.equalsIgnoreCase(problem.getPlatform());
    }

    public static void requireOrdinary(long problemId) {
        new ProblemRepository()
                .findById(problemId)
                .ifPresent(
                        p -> {
                            if (!ordinary(p))
                                throw new IllegalArgumentException(
                                        "Use the Evolution presentation service before opening this"
                                            + " challenge");
                        });
    }
}

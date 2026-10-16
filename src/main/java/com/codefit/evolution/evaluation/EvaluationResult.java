package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;

import java.time.Instant;
import java.util.*;

/** Immutable execution report. No per-check result is an independent learner observation. */
public record EvaluationResult(
        Outcome outcome,
        List<CheckResult> checks,
        String diagnostics,
        Instant startedAt,
        Instant finishedAt,
        String evaluatorVersion,
        String runtime) {
    public EvaluationResult {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(finishedAt);
        Objects.requireNonNull(diagnostics);
        Objects.requireNonNull(runtime);
        SubmissionRequest.identity(evaluatorVersion);
        checks =
                List.copyOf(checks).stream()
                        .sorted(Comparator.comparing(CheckResult::checkId))
                        .toList();
        if (checks.size() > 64
                || checks.stream().map(CheckResult::checkId).distinct().count() != checks.size()
                || diagnostics.length() > 16000
                || runtime.length() > 1024)
            throw new IllegalArgumentException("Bounded unique results required");
    }
}

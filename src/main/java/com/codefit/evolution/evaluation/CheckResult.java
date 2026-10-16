package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;

import com.codefit.evolution.quest.CatalogRef;

import java.util.*;

/**
 * Private bounded diagnostics. Public feedback must be projected through the coordinator's view.
 */
public record CheckResult(
        String checkId, CheckStatus status, String detail, List<RunObservation> runs) {
    public CheckResult {
        CatalogRef.id(checkId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(detail);
        runs = List.copyOf(runs);
        if (detail.length() > 16000 || runs.size() > 80)
            throw new IllegalArgumentException("Bounded check result required");
    }

    public record RunObservation(
            String caseId,
            int repetition,
            CheckStatus status,
            Integer exitCode,
            String stdoutSha256,
            String stderrSha256,
            String stdout,
            String stderr,
            long elapsedMillis) {
        public RunObservation {
            CatalogRef.id(caseId);
            Objects.requireNonNull(status);
            if (repetition < 1
                    || repetition > 5
                    || elapsedMillis < 0
                    || stdout.length() > 4096
                    || stderr.length() > 4096
                    || !stdoutSha256.matches("[0-9a-f]{64}")
                    || !stderrSha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid bounded run observation");
        }
    }
}

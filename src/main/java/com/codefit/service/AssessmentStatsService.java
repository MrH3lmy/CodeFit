package com.codefit.service;

import com.codefit.model.AssessmentAttempt;
import com.codefit.repository.AssessmentAttemptRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reports transfer-assessment performance by skill/concept, kept entirely separate from
 * {@link StatsService}'s normal-review reporting so a learner can never mistake transfer accuracy
 * for retention accuracy (#104).
 */
public class AssessmentStatsService {
    private static final int RECENT_ATTEMPT_LIMIT = 300;

    private final AssessmentAttemptRepository assessmentAttemptRepository = new AssessmentAttemptRepository();

    public List<TransferSkillPerformance> getTransferPerformanceBySkill() {
        return buildTransferPerformanceBySkill(assessmentAttemptRepository.findRecent(RECENT_ATTEMPT_LIMIT));
    }

    public Optional<AssessmentRunSummary> getLatestRunSummary() {
        return buildLatestRunSummary(assessmentAttemptRepository.findRecent(RECENT_ATTEMPT_LIMIT));
    }

    /** Package-private/static so the by-skill aggregation is directly unit testable without a database. */
    static List<TransferSkillPerformance> buildTransferPerformanceBySkill(List<AssessmentAttempt> attempts) {
        Map<String, int[]> bySkill = new LinkedHashMap<>();
        for (AssessmentAttempt attempt : attempts) {
            int[] counts = bySkill.computeIfAbsent(attempt.skillCategory(), ignored -> new int[2]);
            counts[0]++;
            if (attempt.correct()) {
                counts[1]++;
            }
        }
        List<TransferSkillPerformance> performance = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : bySkill.entrySet()) {
            performance.add(new TransferSkillPerformance(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
        }
        return performance.stream().sorted(Comparator.comparing(TransferSkillPerformance::skillCategory)).toList();
    }

    /** The most recent weekly run, identified by the run id shared by every attempt made in that session. */
    static Optional<AssessmentRunSummary> buildLatestRunSummary(List<AssessmentAttempt> attempts) {
        List<AssessmentAttempt> withRun = attempts.stream()
                .filter(attempt -> attempt.runId() != null && !attempt.runId().isBlank())
                .toList();
        if (withRun.isEmpty()) {
            return Optional.empty();
        }
        String latestRunId = withRun.stream()
                .max(Comparator.comparing(AssessmentAttempt::attemptedAt).thenComparingLong(AssessmentAttempt::id))
                .orElseThrow()
                .runId();
        List<AssessmentAttempt> runAttempts = withRun.stream()
                .filter(attempt -> latestRunId.equals(attempt.runId()))
                .toList();
        int total = runAttempts.size();
        long correct = runAttempts.stream().filter(AssessmentAttempt::correct).count();
        List<TransferSkillPerformance> bySkill = buildTransferPerformanceBySkill(runAttempts);
        return Optional.of(new AssessmentRunSummary(latestRunId, runAttempts.get(0).attemptedAt().toLocalDate(),
                total, (int) correct, bySkill));
    }
}

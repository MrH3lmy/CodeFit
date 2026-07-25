package com.codefit.service;

import com.codefit.model.AssessmentAttempt;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssessmentStatsServiceTest {

    private AssessmentAttempt attempt(long itemId, String skill, boolean correct, LocalDateTime attemptedAt, String runId) {
        return new AssessmentAttempt(itemId * 100 + attemptedAt.getNano(), itemId, 0, skill, "Some Module", correct,
                "submitted", 1000, attemptedAt, runId);
    }

    @Test
    void transferPerformanceIsAggregatedPerSkillNotPooledTogether() {
        List<AssessmentAttempt> attempts = List.of(
                attempt(1, "SQL", true, LocalDateTime.now(), "run-1"),
                attempt(1, "SQL", false, LocalDateTime.now(), "run-1"),
                attempt(2, "Security", true, LocalDateTime.now(), "run-1"));

        List<TransferSkillPerformance> bySkill = AssessmentStatsService.buildTransferPerformanceBySkill(attempts);

        TransferSkillPerformance sql = bySkill.stream().filter(p -> p.skillCategory().equals("SQL")).findFirst().orElseThrow();
        TransferSkillPerformance security = bySkill.stream().filter(p -> p.skillCategory().equals("Security")).findFirst().orElseThrow();
        assertEquals(2, sql.attempts());
        assertEquals(1, sql.correctCount());
        assertEquals(50.0, sql.accuracyPercent());
        assertEquals(1, security.attempts());
        assertEquals(100.0, security.accuracyPercent());
    }

    @Test
    void latestRunSummaryOnlyIncludesAttemptsFromTheMostRecentRun() {
        LocalDateTime lastWeek = LocalDateTime.now().minusDays(7);
        LocalDateTime today = LocalDateTime.now();
        List<AssessmentAttempt> attempts = List.of(
                attempt(1, "SQL", true, lastWeek, "run-old"),
                attempt(2, "SQL", false, lastWeek, "run-old"),
                attempt(3, "Security", true, today, "run-new"),
                attempt(4, "Security", true, today, "run-new"));

        Optional<AssessmentRunSummary> summary = AssessmentStatsService.buildLatestRunSummary(attempts);

        assertTrue(summary.isPresent());
        assertEquals("run-new", summary.get().runId());
        assertEquals(2, summary.get().totalItems());
        assertEquals(2, summary.get().correctCount());
        assertEquals(100.0, summary.get().accuracyPercent());
    }

    @Test
    void noRunsProducesAnEmptySummary() {
        assertTrue(AssessmentStatsService.buildLatestRunSummary(List.of()).isEmpty());
    }
}

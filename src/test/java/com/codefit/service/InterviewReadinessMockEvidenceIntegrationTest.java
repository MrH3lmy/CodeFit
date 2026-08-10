package com.codefit.service;

import com.codefit.model.InterviewMaterialType;
import com.codefit.repository.InterviewMockRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class InterviewReadinessMockEvidenceIntegrationTest {

    private final InterviewMockRepository repository = new InterviewMockRepository();
    private final InterviewReadinessService readinessService = new InterviewReadinessService();

    @Test
    void mockEvidenceRequiresRepeatedSamplesAndFeedsCriticalReadiness() {
        saveDomainMock("system-design-1", "system-design", 90, LocalDateTime.of(2026, 8, 9, 10, 0));

        InterviewDomainReadiness systemDesignAfterOne = domain(
                readinessService.calculate(RevolutJavaInterviewProfile.ID).orElseThrow(), "system-design");
        InterviewRequirementReadiness mockAfterOne = requirement(systemDesignAfterOne, "mock-evidence-system-design");
        assertFalse(mockAfterOne.measurable(), "one lucky mock must not become readiness evidence");
        assertEquals(InterviewMaterialType.MOCK_INTERVIEW, mockAfterOne.sourceType());
        assertEquals(InterviewDomainReadinessStatus.NOT_MEASURED, systemDesignAfterOne.status());

        saveDomainMock("system-design-2", "system-design", 80, LocalDateTime.of(2026, 8, 9, 11, 0));

        InterviewDomainReadiness systemDesignAfterTwo = domain(
                readinessService.calculate(RevolutJavaInterviewProfile.ID).orElseThrow(), "system-design");
        InterviewRequirementReadiness mockAfterTwo = requirement(systemDesignAfterTwo, "mock-evidence-system-design");
        assertTrue(mockAfterTwo.measurable());
        assertEquals(85, mockAfterTwo.scorePercent());
        assertEquals(85, systemDesignAfterTwo.scorePercent());
        assertEquals(InterviewDomainReadinessStatus.PARTIAL, systemDesignAfterTwo.status(),
                "strong mock execution is real signal but cannot replace the still-planned RJ system-design content");

        saveDomainMock("concurrency-1", "java-concurrency-jmm", 40, LocalDateTime.of(2026, 8, 9, 12, 0));
        saveDomainMock("concurrency-2", "java-concurrency-jmm", 50, LocalDateTime.of(2026, 8, 9, 13, 0));

        InterviewReadinessResult readiness = readinessService.calculate(RevolutJavaInterviewProfile.ID).orElseThrow();
        InterviewDomainReadiness concurrency = domain(readiness, "java-concurrency-jmm");
        assertEquals(45, requirement(concurrency, "mock-evidence-java-concurrency-jmm").scorePercent());
        assertEquals(InterviewDomainReadinessStatus.FAIL, concurrency.status());
        assertEquals(InterviewReadinessStatus.NOT_READY, readiness.status());
        assertTrue(readiness.blockingCriticalDomainIds().contains("java-concurrency-jmm"));
    }

    private void saveDomainMock(String runId, String domainId, int score, LocalDateTime completedAt) {
        repository.save(new InterviewMockEvaluation(
                runId,
                RevolutJavaInterviewProfile.ID,
                InterviewMockMode.TECHNICAL_DEEP_DIVE,
                score,
                completedAt,
                null,
                List.of(new InterviewMockEvaluation.StageScore("stage", InterviewMockPlan.StageType.TECHNICAL_DEEP_DIVE, score)),
                List.of(new InterviewMockEvaluation.DomainScore(domainId, score))));
    }

    private InterviewDomainReadiness domain(InterviewReadinessResult result, String domainId) {
        return result.domains().stream().filter(domain -> domain.domainId().equals(domainId)).findFirst().orElseThrow();
    }

    private InterviewRequirementReadiness requirement(InterviewDomainReadiness domain, String requirementId) {
        return domain.requirements().stream().filter(requirement -> requirement.requirementId().equals(requirementId))
                .findFirst().orElseThrow();
    }
}

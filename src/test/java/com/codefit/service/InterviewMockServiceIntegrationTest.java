package com.codefit.service;

import com.codefit.repository.InterviewMockRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class InterviewMockServiceIntegrationTest {

    private final InterviewMockService service = new InterviewMockService();

    @Test
    void fullLoopContainsAllFourInterviewStagesAndAllEightReadinessDomains() {
        InterviewMockPlan plan = service.build(RevolutJavaInterviewProfile.ID,
                InterviewMockMode.FULL_INTERVIEW_LOOP).orElseThrow();

        assertEquals(4, plan.stages().size());
        assertEquals(165, plan.totalTargetMinutes());
        assertEquals(100, plan.stages().stream().mapToInt(InterviewMockPlan.Stage::weightPercent).sum());
        assertEquals(
                java.util.List.of(InterviewMockPlan.StageType.LIVE_CODING,
                        InterviewMockPlan.StageType.TECHNICAL_DEEP_DIVE,
                        InterviewMockPlan.StageType.SYSTEM_DESIGN,
                        InterviewMockPlan.StageType.TEAM_FIT),
                plan.stages().stream().map(InterviewMockPlan.Stage::type).toList());

        java.util.Set<String> coveredDomains = plan.stages().stream()
                .flatMap(stage -> stage.rubric().stream())
                .map(InterviewMockPlan.RubricCriterion::domainId)
                .collect(Collectors.toSet());
        java.util.Set<String> profileDomains = new InterviewProfileService().getRevolutJavaProfile().getDomains().stream()
                .map(com.codefit.model.InterviewDomain::getId)
                .collect(Collectors.toSet());
        assertEquals(profileDomains, coveredDomains);
    }

    @Test
    void standaloneModesContainExactlyOneHundredPercentWeightedStage() {
        for (InterviewMockMode mode : java.util.List.of(InterviewMockMode.LIVE_CODING,
                InterviewMockMode.TECHNICAL_DEEP_DIVE, InterviewMockMode.SYSTEM_DESIGN, InterviewMockMode.TEAM_FIT)) {
            InterviewMockPlan plan = service.build(RevolutJavaInterviewProfile.ID, mode).orElseThrow();
            assertEquals(1, plan.stages().size());
            assertEquals(100, plan.stages().getFirst().weightPercent());
        }
    }

    @Test
    void completingMockPersistsRunAndDomainEvidenceWithoutChangingThePlan() {
        InterviewMockPlan plan = service.build(RevolutJavaInterviewProfile.ID,
                InterviewMockMode.FULL_INTERVIEW_LOOP).orElseThrow();
        Map<String, Integer> scores = plan.stages().stream()
                .flatMap(stage -> stage.rubric().stream())
                .collect(Collectors.toMap(InterviewMockPlan.RubricCriterion::id, ignored -> 80));

        InterviewMockEvaluation evaluation = service.complete(plan, scores, "integration mock");

        assertEquals(80, evaluation.overallScorePercent());
        assertEquals(8, evaluation.domainScores().size());
        assertTrue(service.recentRuns(RevolutJavaInterviewProfile.ID, 5).stream()
                .anyMatch(run -> run.runId().equals(evaluation.runId())));

        InterviewMockRepository repository = new InterviewMockRepository();
        assertTrue(repository.findRecentDomainScores(RevolutJavaInterviewProfile.ID, "system-design", 5).stream()
                .anyMatch(score -> score.runId().equals(evaluation.runId()) && score.scorePercent() == 80));
    }
}

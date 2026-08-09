package com.codefit.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InterviewMockServiceTest {

    @Test
    void fullLoopEvaluationProducesWeightedStageOverallAndDomainScores() {
        InterviewMockPlan plan = new InterviewMockPlan(
                "profile", "Profile", InterviewMockMode.FULL_INTERVIEW_LOOP,
                List.of(
                        stage("live", InterviewMockPlan.StageType.LIVE_CODING, 50,
                                criterion("live-a", "live-domain", 60),
                                criterion("live-b", "live-domain", 40)),
                        stage("system", InterviewMockPlan.StageType.SYSTEM_DESIGN, 50,
                                criterion("design-a", "system-domain", 50),
                                criterion("design-db", "db-domain", 50))));

        InterviewMockEvaluation result = InterviewMockService.evaluate(plan,
                Map.of("live-a", 100, "live-b", 50, "design-a", 80, "design-db", 60),
                "notes", "run-1", LocalDateTime.of(2026, 8, 9, 18, 0));

        assertEquals(80, stageScore(result, "live"));
        assertEquals(70, stageScore(result, "system"));
        assertEquals(75, result.overallScorePercent());
        assertEquals(80, domainScore(result, "live-domain"));
        assertEquals(80, domainScore(result, "system-domain"));
        assertEquals(60, domainScore(result, "db-domain"));
    }

    @Test
    void evaluationRequiresExactlyEveryRubricCriterion() {
        InterviewMockPlan plan = new InterviewMockPlan(
                "profile", "Profile", InterviewMockMode.LIVE_CODING,
                List.of(stage("live", InterviewMockPlan.StageType.LIVE_CODING, 100,
                        criterion("a", "domain", 50), criterion("b", "domain", 50))));

        assertThrows(IllegalArgumentException.class,
                () -> InterviewMockService.evaluate(plan, Map.of("a", 80), null, "run", LocalDateTime.now()));
        assertThrows(IllegalArgumentException.class,
                () -> InterviewMockService.evaluate(plan, Map.of("a", 80, "b", 80, "extra", 80), null,
                        "run", LocalDateTime.now()));
    }

    @Test
    void evaluationRejectsOutOfRangeCriterionScores() {
        InterviewMockPlan plan = new InterviewMockPlan(
                "profile", "Profile", InterviewMockMode.LIVE_CODING,
                List.of(stage("live", InterviewMockPlan.StageType.LIVE_CODING, 100,
                        criterion("a", "domain", 100))));

        assertThrows(IllegalArgumentException.class,
                () -> InterviewMockService.evaluate(plan, Map.of("a", 101), null, "run", LocalDateTime.now()));
    }

    @Test
    void planRejectsStageWeightsThatDoNotSumToOneHundred() {
        assertThrows(IllegalArgumentException.class, () -> new InterviewMockPlan(
                "profile", "Profile", InterviewMockMode.FULL_INTERVIEW_LOOP,
                List.of(stage("a", InterviewMockPlan.StageType.LIVE_CODING, 40,
                                criterion("a-criterion", "a-domain", 100)),
                        stage("b", InterviewMockPlan.StageType.SYSTEM_DESIGN, 40,
                                criterion("b-criterion", "b-domain", 100)))));
    }

    private InterviewMockPlan.Stage stage(String id, InterviewMockPlan.StageType type, int weight,
                                          InterviewMockPlan.RubricCriterion... criteria) {
        return new InterviewMockPlan.Stage(id, type, id, "prompt", 30, weight, Optional.empty(), List.of(criteria));
    }

    private InterviewMockPlan.RubricCriterion criterion(String id, String domainId, int weight) {
        return new InterviewMockPlan.RubricCriterion(id, id, "description", domainId, weight);
    }

    private int stageScore(InterviewMockEvaluation evaluation, String stageId) {
        return evaluation.stageScores().stream().filter(stage -> stage.stageId().equals(stageId))
                .findFirst().orElseThrow().scorePercent();
    }

    private int domainScore(InterviewMockEvaluation evaluation, String domainId) {
        return evaluation.domainScores().stream().filter(domain -> domain.domainId().equals(domainId))
                .findFirst().orElseThrow().scorePercent();
    }
}

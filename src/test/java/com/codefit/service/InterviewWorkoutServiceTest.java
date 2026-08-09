package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewWorkoutServiceTest {
    private final InterviewPreparationProfile profile = new InterviewProfileService().getRevolutJavaProfile();
    private final InterviewWorkoutService.ProfileWorkoutConfiguration configuration =
            new InterviewWorkoutService.ProfileWorkoutConfiguration(
                    "system-design", "reliability-observability-jvm-role-stack", "abe-10");
    private final InterviewWorkoutService.WorkoutPolicy policy = new InterviewWorkoutService.WorkoutPolicy(45, 10, 25, 5);

    @Test
    void workoutComposesExistingReviewPlanCodingRecommendationAndInterviewPrompts() {
        InterviewReadinessResult readiness = readinessWithFailingLiveCoding();
        ReviewService.AdaptiveSessionPlan reviewPlan = new ReviewService.AdaptiveSessionPlan(List.of(), Map.of(), 0);
        GuidedTrainingPlan guidedPlan = new GuidedTrainingPlan(15, reviewPlan, false, true, 0, false, List.of());
        TodayPlan todayPlan = new TodayPlan(null, null, 10, 0, 2, 0, Optional.empty(),
                "No coding problem is currently available.", List.of(), null);

        InterviewWorkout workout = InterviewWorkoutService.compose(profile, readiness, guidedPlan, todayPlan,
                LocalDate.of(2026, 8, 9), configuration, policy);

        assertEquals(profile.getId(), workout.profileId());
        assertEquals(15, workout.reviewSessionMinutes());
        assertEquals(reviewPlan, workout.reviewPlan(), "the existing adaptive review plan must be reused verbatim");
        assertFalse(workout.hasCodingProblem(), "absence of a recommended problem must stay explicit, never fabricated");
        assertEquals("No coding problem is currently available.", workout.codingReason());
        assertEquals(InterviewWorkout.PromptType.TECHNICAL_DEEP_DIVE, workout.technicalDeepDive().type());
        assertEquals(InterviewWorkout.PromptType.REFLECTION, workout.reflection().type());
        assertEquals(100, workout.totalTargetMinutes(), "15 review + 45 coding + 10 technical + 25 scenario + 5 reflection");
    }

    @Test
    void technicalDeepDivePrioritizesARealFailingCriticalDomain() {
        InterviewWorkout.Prompt prompt = InterviewWorkoutService.selectTechnicalPrompt(
                profile, readinessWithFailingLiveCoding(), 10);

        assertEquals("live-java-coding-dsa-testing", prompt.domainId());
        assertEquals("java-be-06-testing", prompt.requirementId());
        assertTrue(prompt.backingMaterialAvailable());
        assertEquals("Java BE 06 - Testing with JUnit/Mockito", prompt.sourceReferenceKey());
    }

    @Test
    void consecutiveDatesAlternateSystemDesignAndFailureScenario() {
        LocalDate first = LocalDate.of(2026, 8, 9);
        InterviewWorkout.Prompt firstPrompt = InterviewWorkoutService.selectScenarioPrompt(profile, first, configuration, 25);
        InterviewWorkout.Prompt nextPrompt = InterviewWorkoutService.selectScenarioPrompt(profile, first.plusDays(1), configuration, 25);

        assertNotEquals(firstPrompt.type(), nextPrompt.type());
        assertTrue(List.of(InterviewWorkout.PromptType.SYSTEM_DESIGN, InterviewWorkout.PromptType.FAILURE_SCENARIO)
                .contains(firstPrompt.type()));
        assertTrue(List.of(InterviewWorkout.PromptType.SYSTEM_DESIGN, InterviewWorkout.PromptType.FAILURE_SCENARIO)
                .contains(nextPrompt.type()));
    }

    @Test
    void systemDesignDaysRotateAcrossTheDomainsRequirements() {
        LocalDate designDay = LocalDate.of(2026, 8, 9);
        while (Math.floorMod(designDay.toEpochDay(), 2) != 0) {
            designDay = designDay.plusDays(1);
        }

        InterviewWorkout.Prompt first = InterviewWorkoutService.selectScenarioPrompt(profile, designDay, configuration, 25);
        InterviewWorkout.Prompt second = InterviewWorkoutService.selectScenarioPrompt(profile, designDay.plusDays(2), configuration, 25);

        assertEquals(InterviewWorkout.PromptType.SYSTEM_DESIGN, first.type());
        assertEquals(InterviewWorkout.PromptType.SYSTEM_DESIGN, second.type());
        assertNotEquals(first.requirementId(), second.requirementId(),
                "the two Revolut system-design requirements should alternate across design days");
        assertTrue(first.backingMaterialAvailable(), "RJ system-design content is bundled and installable in Slice 5");
        assertTrue(first.sourceReferenceKey().startsWith("RJ 0"));
    }

    @Test
    void failureDrillStaysAnchoredToExistingAbeFailureMaterial() {
        LocalDate failureDay = LocalDate.of(2026, 8, 9);
        while (Math.floorMod(failureDay.toEpochDay(), 2) == 0) {
            failureDay = failureDay.plusDays(1);
        }

        InterviewWorkout.Prompt prompt = InterviewWorkoutService.selectScenarioPrompt(profile, failureDay, configuration, 25);

        assertEquals(InterviewWorkout.PromptType.FAILURE_SCENARIO, prompt.type());
        assertEquals("abe-10", prompt.requirementId());
        assertTrue(prompt.backingMaterialAvailable());
        assertEquals("ABE 10 - API & Database Failure Scenarios", prompt.sourceReferenceKey());
    }

    private InterviewReadinessResult readinessWithFailingLiveCoding() {
        List<InterviewDomainReadiness> domains = new ArrayList<>();
        List<String> blockers = new ArrayList<>();

        for (InterviewDomain domain : profile.getDomains()) {
            if (domain.getId().equals("live-java-coding-dsa-testing")) {
                List<InterviewRequirementReadiness> requirements = domain.getRequirements().stream()
                        .map(requirement -> {
                            if (requirement.getId().equals("java-be-06-testing")) {
                                return InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.DECK, 40, "weak testing signal");
                            }
                            if (requirement.isAvailable()) {
                                return InterviewRequirementReadiness.unmeasurable(requirement, requirement.getReference().type(), "not enough data");
                            }
                            return InterviewRequirementReadiness.planned(requirement);
                        })
                        .toList();
                domains.add(new InterviewDomainReadiness(domain.getId(), domain.getTitle(), domain.getWeightPercent(), true,
                        domain.getMinimumReadinessThresholdPercent(), 40, 33, 1, domain.getRequirements().size(),
                        InterviewDomainReadinessStatus.FAIL, requirements));
                blockers.add(domain.getId());
                continue;
            }

            List<InterviewRequirementReadiness> requirements = domain.getRequirements().stream()
                    .map(requirement -> requirement.isAvailable()
                            ? InterviewRequirementReadiness.unmeasurable(requirement, requirement.getReference().type(), "not enough data")
                            : InterviewRequirementReadiness.planned(requirement))
                    .toList();
            domains.add(new InterviewDomainReadiness(domain.getId(), domain.getTitle(), domain.getWeightPercent(),
                    domain.isCriticalGate(), domain.getMinimumReadinessThresholdPercent(), null, 0, 0,
                    domain.getRequirements().size(), InterviewDomainReadinessStatus.NOT_MEASURED, requirements));
            if (domain.isCriticalGate()) {
                blockers.add(domain.getId());
            }
        }

        return new InterviewReadinessResult(profile.getId(), profile.getTitle(), domains, 40, 6,
                InterviewReadinessStatus.NOT_READY, blockers);
    }
}

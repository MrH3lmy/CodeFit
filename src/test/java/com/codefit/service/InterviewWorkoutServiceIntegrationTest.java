package com.codefit.service;

import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the Slice 3 coordinator can build a real workout from CodeFit's seeded review/problem data. */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class InterviewWorkoutServiceIntegrationTest {

    @Test
    void buildsRevolutWorkoutFromExistingCodeFitSourcesWithoutNewPersistence() {
        InterviewWorkout workout = new InterviewWorkoutService()
                .build(RevolutJavaInterviewProfile.ID, LocalDate.of(2026, 8, 9))
                .orElseThrow();

        assertEquals(RevolutJavaInterviewProfile.ID, workout.profileId());
        assertEquals(8, workout.readiness().domains().size());
        assertTrue(workout.reviewSessionMinutes() >= GuidedTrainingService.MIN_SESSION_MINUTES);
        assertTrue(workout.reviewPlan().estimatedSeconds() >= 0);
        assertTrue(workout.hasCodingProblem(),
                "the isolated database seeds a pilot roadmap, so the existing guided recommendation should supply a coding problem");
        assertEquals(InterviewWorkout.PromptType.TECHNICAL_DEEP_DIVE, workout.technicalDeepDive().type());
        assertEquals(InterviewWorkout.PromptType.REFLECTION, workout.reflection().type());
        assertTrue(workout.totalTargetMinutes() > workout.reviewSessionMinutes());
    }
}

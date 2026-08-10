package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewReadinessCoverageTest {

    private InterviewRequirement requirement(String id) {
        return InterviewRequirement.available(id, "Requirement " + id, "description",
                InterviewMaterialType.DECK, "Deck " + id);
    }

    @Test
    void strongPartialCriticalDomainCannotPass() {
        InterviewRequirement measured = requirement("a");
        InterviewRequirement missing = requirement("b");
        InterviewDomain domain = new InterviewDomain("critical", "Critical", "description",
                100, true, 70, List.of(measured, missing));

        InterviewDomainReadiness readiness = InterviewReadinessService.buildDomainReadiness(domain, List.of(
                InterviewRequirementReadiness.measured(measured, InterviewMaterialType.DECK, 100.0, "strong"),
                InterviewRequirementReadiness.unmeasurable(missing, InterviewMaterialType.DECK, "missing")));

        assertEquals(100, readiness.scorePercent());
        assertEquals(50, readiness.coveragePercent());
        assertEquals(InterviewDomainReadinessStatus.PARTIAL, readiness.status(),
                "100% on half a critical domain must never become PASS");
    }

    @Test
    void weakPartialCriticalDomainStillFailsOnRealNegativeSignal() {
        InterviewRequirement measured = requirement("a");
        InterviewRequirement missing = requirement("b");
        InterviewDomain domain = new InterviewDomain("critical", "Critical", "description",
                100, true, 70, List.of(measured, missing));

        InterviewDomainReadiness readiness = InterviewReadinessService.buildDomainReadiness(domain, List.of(
                InterviewRequirementReadiness.measured(measured, InterviewMaterialType.DECK, 40.0, "weak"),
                InterviewRequirementReadiness.unmeasurable(missing, InterviewMaterialType.DECK, "missing")));

        assertEquals(InterviewDomainReadinessStatus.FAIL, readiness.status(),
                "real failing evidence should remain NOT_READY even when coverage is incomplete");
    }

    @Test
    void overallCoverageUsesEffectiveDomainCoverageNotWholeDomainWeight() {
        InterviewDomainReadiness halfMeasured = new InterviewDomainReadiness(
                "half", "Half", 60, false, null, 80, 50, 1, 2,
                InterviewDomainReadinessStatus.MEASURED, List.of());
        InterviewDomainReadiness fullyMeasured = new InterviewDomainReadiness(
                "full", "Full", 40, false, null, 60, 100, 1, 1,
                InterviewDomainReadinessStatus.MEASURED, List.of());
        InterviewPreparationProfile profile = new InterviewPreparationProfile(
                "profile", "Profile", "description", List.of(
                new InterviewDomain("half", "Half", "description", 60, false, null, List.of(requirement("h1"), requirement("h2"))),
                new InterviewDomain("full", "Full", "description", 40, false, null, List.of(requirement("f1")))));

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profile,
                List.of(halfMeasured, fullyMeasured), InterviewReadinessService.DEFAULT_POLICY);

        assertEquals(70, result.coveragePercent(), "60% domain at half coverage contributes 30 points, plus 40 full = 70");
        assertEquals(69, result.overallReadinessPercent(),
                "effective measured weights are 30 and 40: (80*30 + 60*40) / 70 = 68.57 -> 69");
    }

    @Test
    void partialCriticalGateBlocksReadyAsInsufficientDataWhenMeasuredSubsetPasses() {
        InterviewDomainReadiness partialCritical = new InterviewDomainReadiness(
                "critical", "Critical", 100, true, 70, 90, 50, 1, 2,
                InterviewDomainReadinessStatus.PARTIAL, List.of());
        InterviewPreparationProfile profile = new InterviewPreparationProfile(
                "profile", "Profile", "description", List.of(
                new InterviewDomain("critical", "Critical", "description", 100, true, 70,
                        List.of(requirement("a"), requirement("b")))));

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profile,
                List.of(partialCritical), InterviewReadinessService.DEFAULT_POLICY);

        assertEquals(90, result.overallReadinessPercent());
        assertEquals(50, result.coveragePercent());
        assertEquals(InterviewReadinessStatus.INSUFFICIENT_DATA, result.status());
        assertTrue(result.blockingCriticalDomainIds().contains("critical"));
    }
}

package com.codefit.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewPreparationProfileTest {

    private InterviewDomain domain(String id, int weightPercent, boolean criticalGate, Integer minimumThresholdPercent) {
        return new InterviewDomain(id, "Title " + id, "description", weightPercent, criticalGate,
                minimumThresholdPercent, List.of(InterviewRequirement.planned(id + "-req", "Req " + id, "description")));
    }

    @Test
    void validProfileHasNoViolations() {
        InterviewPreparationProfile profile = new InterviewPreparationProfile("p", "Profile", "description", List.of(
                domain("a", 60, true, 70),
                domain("b", 40, false, null)));

        assertTrue(profile.isValid());
        assertEquals(List.of(), profile.validate());
    }

    @Test
    void weightsThatDoNotSumToExactlyOneHundredAreReportedNotNormalized() {
        InterviewPreparationProfile tooLow = new InterviewPreparationProfile("p", "Profile", "description", List.of(
                domain("a", 60, false, null),
                domain("b", 30, false, null)));

        assertFalse(tooLow.isValid());
        assertTrue(tooLow.validate().stream().anyMatch(violation -> violation.contains("90%")),
                "a 90% total should be reported verbatim, not silently normalized to 100%");

        InterviewPreparationProfile tooHigh = new InterviewPreparationProfile("p", "Profile", "description", List.of(
                domain("a", 60, false, null),
                domain("b", 50, false, null)));

        assertFalse(tooHigh.isValid());
        assertTrue(tooHigh.validate().stream().anyMatch(violation -> violation.contains("110%")));
    }

    @Test
    void duplicateDomainIdsAreReported() {
        InterviewPreparationProfile profile = new InterviewPreparationProfile("p", "Profile", "description", List.of(
                domain("a", 50, false, null),
                domain("a", 50, false, null)));

        assertFalse(profile.isValid());
        assertTrue(profile.validate().stream().anyMatch(violation -> violation.contains("Duplicate domain id")));
    }

    @Test
    void nonCriticalDomainsDoNotRequireAThreshold() {
        InterviewDomain nonCritical = domain("a", 100, false, null);

        assertFalse(nonCritical.hasMinimumReadinessThreshold());
        assertEquals(null, nonCritical.getMinimumReadinessThresholdPercent());

        InterviewPreparationProfile profile = new InterviewPreparationProfile("p", "Profile", "description", List.of(nonCritical));
        assertTrue(profile.isValid());
    }

    @Test
    void constructingACriticalDomainWithoutAThresholdFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> new InterviewDomain("a", "Title", "description", 50, true,
                null, List.of()));
    }

    @Test
    void findDomainByIdReturnsEmptyForUnknownId() {
        InterviewPreparationProfile profile = new InterviewPreparationProfile("p", "Profile", "description",
                List.of(domain("a", 100, false, null)));

        assertEquals(Optional.empty(), profile.findDomainById("does-not-exist"));
        assertTrue(profile.findDomainById("a").isPresent());
    }

    @Test
    void availableRequirementMustReferenceExistingMaterial() {
        assertThrows(IllegalArgumentException.class,
                () -> InterviewRequirement.available("id", "title", "description", null));
        assertThrows(IllegalArgumentException.class,
                () -> InterviewRequirement.available("id", "title", "description", "  "));
    }

    @Test
    void plannedRequirementHasNoReferenceAndIsNotAvailable() {
        InterviewRequirement planned = InterviewRequirement.planned("id", "title", "description");

        assertFalse(planned.isAvailable());
        assertEquals(null, planned.getReference());
        assertEquals(InterviewRequirementStatus.PLANNED, planned.getStatus());
    }
}

package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import com.codefit.service.InterviewReadinessService.InterviewReadinessPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link InterviewReadinessService}'s aggregation/weighting/critical-gate logic entirely
 * against hand-built fixtures - mirrors {@code TrainingPathServiceTest}'s tests of
 * {@code TrainingPathService.recommend} and {@code MasteryServiceTest}'s tests of
 * {@code MasteryService.evaluate}, both deliberately independent of the database. Resolver behavior
 * against real deck/problem-solving data lives in {@link InterviewReadinessServiceIntegrationTest}.
 */
class InterviewReadinessServiceTest {

    private static final InterviewReadinessPolicy POLICY_75 = InterviewReadinessService.DEFAULT_POLICY;

    // ---- fixtures ----

    private InterviewRequirement deckRequirement(String id) {
        return InterviewRequirement.available(id, "Title " + id, "description", InterviewMaterialType.DECK, "Deck " + id);
    }

    private InterviewRequirement plannedRequirement(String id) {
        return InterviewRequirement.planned(id, "Title " + id, "description");
    }

    private InterviewDomain domain(String id, int weightPercent, boolean criticalGate, Integer thresholdPercent,
                                   List<InterviewRequirement> requirements) {
        return new InterviewDomain(id, "Domain " + id, "description", weightPercent, criticalGate, thresholdPercent, requirements);
    }

    private InterviewPreparationProfile profileWrapping(InterviewDomain... domains) {
        return new InterviewPreparationProfile("test-profile", "Test Profile", "description", List.of(domains));
    }

    private InterviewDomainReadiness measuredDomain(String id, int weightPercent, boolean criticalGate, Integer thresholdPercent, int scorePercent) {
        InterviewDomainReadinessStatus status = !criticalGate ? InterviewDomainReadinessStatus.MEASURED
                : scorePercent >= thresholdPercent ? InterviewDomainReadinessStatus.PASS : InterviewDomainReadinessStatus.FAIL;
        return new InterviewDomainReadiness(id, "Domain " + id, weightPercent, criticalGate, thresholdPercent,
                scorePercent, 100, 1, 1, status, List.of());
    }

    private InterviewDomainReadiness unmeasuredDomain(String id, int weightPercent, boolean criticalGate, Integer thresholdPercent) {
        return new InterviewDomainReadiness(id, "Domain " + id, weightPercent, criticalGate, thresholdPercent, null, 0,
                0, 1, InterviewDomainReadinessStatus.NOT_MEASURED, List.of());
    }

    // ==================================================================================
    // Domain aggregation (requirement -> domain)
    // ==================================================================================

    @Test
    void domainScoreAveragesOnlyMeasurableRequirements() {
        InterviewRequirement a = deckRequirement("a");
        InterviewRequirement b = deckRequirement("b");
        InterviewRequirement c = plannedRequirement("c");
        InterviewDomain domain = domain("d", 100, false, null, List.of(a, b, c));

        List<InterviewRequirementReadiness> readiness = List.of(
                InterviewRequirementReadiness.measured(a, InterviewMaterialType.DECK, 80.0, "note"),
                InterviewRequirementReadiness.measured(b, InterviewMaterialType.DECK, 60.0, "note"),
                InterviewRequirementReadiness.planned(c));

        InterviewDomainReadiness result = InterviewReadinessService.buildDomainReadiness(domain, readiness);

        assertEquals(70, result.scorePercent(), "average of only the two measured requirements, not counting the planned one as 0");
    }

    @Test
    void plannedRequirementsDoNotContributeZeroToTheAverage() {
        InterviewRequirement a = deckRequirement("a");
        InterviewRequirement b = plannedRequirement("b");
        InterviewDomain domain = domain("d", 100, false, null, List.of(a, b));

        List<InterviewRequirementReadiness> readiness = List.of(
                InterviewRequirementReadiness.measured(a, InterviewMaterialType.DECK, 90.0, "note"),
                InterviewRequirementReadiness.planned(b));

        InterviewDomainReadiness result = InterviewReadinessService.buildDomainReadiness(domain, readiness);

        assertEquals(90, result.scorePercent(), "a lone measured requirement at 90 should not be dragged toward 45 by the planned one");
    }

    @Test
    void domainWithNoMeasurableRequirementsReturnsNoScore() {
        InterviewRequirement a = plannedRequirement("a");
        InterviewRequirement b = plannedRequirement("b");
        InterviewDomain domain = domain("d", 100, false, null, List.of(a, b));

        List<InterviewRequirementReadiness> readiness = List.of(
                InterviewRequirementReadiness.planned(a),
                InterviewRequirementReadiness.planned(b));

        InterviewDomainReadiness result = InterviewReadinessService.buildDomainReadiness(domain, readiness);

        assertNull(result.scorePercent());
        assertEquals(InterviewDomainReadinessStatus.NOT_MEASURED, result.status());
        assertEquals(0, result.coveragePercent());
    }

    @Test
    void partialDomainCoverageIsExposedCorrectly() {
        InterviewRequirement a = deckRequirement("a");
        InterviewRequirement b = deckRequirement("b");
        InterviewRequirement c = plannedRequirement("c");
        InterviewDomain domain = domain("d", 100, false, null, List.of(a, b, c));

        List<InterviewRequirementReadiness> readiness = List.of(
                InterviewRequirementReadiness.measured(a, InterviewMaterialType.DECK, 80.0, "note"),
                InterviewRequirementReadiness.measured(b, InterviewMaterialType.DECK, 80.0, "note"),
                InterviewRequirementReadiness.planned(c));

        InterviewDomainReadiness result = InterviewReadinessService.buildDomainReadiness(domain, readiness);

        assertEquals(2, result.measuredRequirementCount());
        assertEquals(3, result.totalRequirementCount());
        assertEquals(67, result.coveragePercent(), "2 of 3 measured, rounded from 66.67%");
    }

    @Test
    void criticalDomainScoreExactlyAtItsThresholdPasses() {
        InterviewRequirement a = deckRequirement("a");
        InterviewDomain domain = domain("d", 100, true, 70, List.of(a));
        List<InterviewRequirementReadiness> readiness = List.of(
                InterviewRequirementReadiness.measured(a, InterviewMaterialType.DECK, 70.0, "note"));

        InterviewDomainReadiness result = InterviewReadinessService.buildDomainReadiness(domain, readiness);

        assertEquals(70, result.scorePercent());
        assertEquals(InterviewDomainReadinessStatus.PASS, result.status());
    }

    @Test
    void criticalDomainScoreOnePointBelowItsThresholdFails() {
        InterviewRequirement a = deckRequirement("a");
        InterviewDomain domain = domain("d", 100, true, 70, List.of(a));
        List<InterviewRequirementReadiness> readiness = List.of(
                InterviewRequirementReadiness.measured(a, InterviewMaterialType.DECK, 69.0, "note"));

        InterviewDomainReadiness result = InterviewReadinessService.buildDomainReadiness(domain, readiness);

        assertEquals(69, result.scorePercent());
        assertEquals(InterviewDomainReadinessStatus.FAIL, result.status());
    }

    @Test
    void nonCriticalMeasuredDomainIsMeasuredNotPassOrFail() {
        InterviewRequirement a = deckRequirement("a");
        InterviewDomain domain = domain("d", 100, false, null, List.of(a));
        List<InterviewRequirementReadiness> readiness = List.of(
                InterviewRequirementReadiness.measured(a, InterviewMaterialType.DECK, 10.0, "note"));

        InterviewDomainReadiness result = InterviewReadinessService.buildDomainReadiness(domain, readiness);

        assertEquals(InterviewDomainReadinessStatus.MEASURED, result.status(),
                "a non-critical domain has no threshold to grade a low score against");
    }

    // ==================================================================================
    // Overall weighting (domain -> result)
    // ==================================================================================

    @Test
    void allDomainsMeasurableProducesAPlainWeightedAverage() {
        InterviewDomainReadiness domainA = measuredDomain("a", 60, false, null, 80);
        InterviewDomainReadiness domainB = measuredDomain("b", 40, false, null, 60);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(domainA, domainB), POLICY_75);

        assertEquals(72, result.overallReadinessPercent(), "(80*60 + 60*40) / 100 = 72");
        assertEquals(100, result.coveragePercent());
    }

    @Test
    void unmeasuredDomainsAreExcludedFromTheWeightedDenominatorNotCountedAsZero() {
        InterviewDomainReadiness measured = measuredDomain("a", 70, false, null, 80);
        InterviewDomainReadiness unmeasured = unmeasuredDomain("b", 30, false, null);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(measured, unmeasured), POLICY_75);

        assertEquals(80, result.overallReadinessPercent(),
                "dividing by the measured weight (70) should return exactly the one measured domain's score");
        assertEquals(70, result.coveragePercent());
    }

    @Test
    void overallScoreIsNeverSilentlyNormalizedByTheFullProfileWeight() {
        InterviewDomainReadiness measured = measuredDomain("a", 70, false, null, 80);
        InterviewDomainReadiness unmeasured = unmeasuredDomain("b", 30, false, null);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(measured, unmeasured), POLICY_75);

        int wronglyNormalizedByFullWeight = (int) Math.round(80 * 70 / 100.0);
        assertTrue(result.overallReadinessPercent() != wronglyNormalizedByFullWeight,
                "a bug that divides by the full 100% weight instead of the measured 70% would silently deflate this to 56");
        assertEquals(80, result.overallReadinessPercent());
    }

    @Test
    void coveragePercentReflectsMeasuredWeightOverTotalWeight() {
        InterviewDomainReadiness measured = measuredDomain("a", 65, false, null, 50);
        InterviewDomainReadiness unmeasured = unmeasuredDomain("b", 35, false, null);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(measured, unmeasured), POLICY_75);

        assertEquals(65, result.coveragePercent());
    }

    // ==================================================================================
    // Critical gates
    // ==================================================================================

    @Test
    void highOverallScoreCannotHideAFailedCriticalDomain() {
        InterviewDomainReadiness failedCritical = measuredDomain("critical", 10, true, 70, 60);
        InterviewDomainReadiness strongNonCritical = measuredDomain("other", 90, false, null, 95);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(),
                List.of(failedCritical, strongNonCritical), POLICY_75);

        assertTrue(result.overallReadinessPercent() >= 90, "the weighted score itself should be high");
        assertEquals(InterviewReadinessStatus.NOT_READY, result.status());
        assertEquals(List.of("critical"), result.blockingCriticalDomainIds());
    }

    @Test
    void allCriticalGatesPassingAndOverallAtOrAboveSeventyFiveIsReady() {
        InterviewDomainReadiness critical = measuredDomain("critical", 100, true, 70, 80);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(critical), POLICY_75);

        assertEquals(InterviewReadinessStatus.READY, result.status());
        assertTrue(result.blockingCriticalDomainIds().isEmpty());
    }

    @Test
    void unmeasuredCriticalDomainMeansInsufficientDataNeverReady() {
        InterviewDomainReadiness unmeasuredCritical = unmeasuredDomain("critical", 20, true, 70);
        InterviewDomainReadiness strongMeasured = measuredDomain("other", 80, false, null, 95);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(),
                List.of(unmeasuredCritical, strongMeasured), POLICY_75);

        assertEquals(InterviewReadinessStatus.INSUFFICIENT_DATA, result.status());
        assertEquals(List.of("critical"), result.blockingCriticalDomainIds());
    }

    @Test
    void allCriticalGatesPassButOverallBelowSeventyFiveIsNotReady() {
        InterviewDomainReadiness critical = measuredDomain("critical", 100, true, 70, 74);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(critical), POLICY_75);

        assertEquals(InterviewReadinessStatus.NOT_READY, result.status());
        assertTrue(result.blockingCriticalDomainIds().isEmpty(), "the critical gate itself passed, only the overall bar was missed");
    }

    @Test
    void overallReadinessExactlyAtTheSeventyFivePercentPolicyThresholdPasses() {
        InterviewDomainReadiness critical = measuredDomain("critical", 100, true, 70, 75);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(critical), POLICY_75);

        assertEquals(75, result.overallReadinessPercent());
        assertEquals(InterviewReadinessStatus.READY, result.status());
    }

    @Test
    void overallReadinessThresholdIsConfigurableNotAMagicConstant() {
        InterviewDomainReadiness critical = measuredDomain("critical", 100, true, 70, 80);
        InterviewReadinessPolicy strictPolicy = new InterviewReadinessPolicy(90);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(critical), strictPolicy);

        assertEquals(InterviewReadinessStatus.NOT_READY, result.status(), "80% overall should fail a policy that requires 90%");
    }

    @Test
    void readinessPolicyRejectsAnOutOfRangeThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new InterviewReadinessPolicy(101));
        assertThrows(IllegalArgumentException.class, () -> new InterviewReadinessPolicy(-1));
    }

    @Test
    void everythingUnmeasuredLeavesOverallReadinessAbsentAndInsufficientData() {
        InterviewDomainReadiness unmeasured = unmeasuredDomain("a", 100, false, null);

        InterviewReadinessResult result = InterviewReadinessService.buildResult(profileWrapping(), List.of(unmeasured), POLICY_75);

        assertNull(result.overallReadinessPercent());
        assertEquals(0, result.coveragePercent());
        assertEquals(InterviewReadinessStatus.INSUFFICIENT_DATA, result.status());
    }

    // ==================================================================================
    // Requirement resolution wiring (still database-free: PLANNED skips resolution entirely,
    // and an empty resolver list never reaches a repository)
    // ==================================================================================

    @Test
    void plannedRequirementIsReportedUnmeasurableWithNoFabricatedScore() {
        InterviewRequirement planned = plannedRequirement("rj-01");
        InterviewRequirementReadiness readiness = InterviewRequirementReadiness.planned(planned);

        assertFalse(readiness.measurable());
        assertNull(readiness.scorePercent());
    }

    @Test
    void endToEndCalculateNeverTouchesTheDatabaseWhenEveryRequirementIsPlanned() {
        InterviewDomain domain = domain("d", 100, false, null, List.of(plannedRequirement("rj-01")));
        InterviewPreparationProfile profile = profileWrapping(domain);
        InterviewReadinessService service = new InterviewReadinessService(new InterviewProfileService(), List.of(), POLICY_75);

        InterviewReadinessResult result = service.calculate(profile);

        assertNull(result.overallReadinessPercent());
        assertEquals(InterviewReadinessStatus.INSUFFICIENT_DATA, result.status());
    }

    @Test
    void anAvailableRequirementWithNoRegisteredResolverFailsSafelyRatherThanBecomingZero() {
        InterviewRequirement requirement = deckRequirement("a");
        InterviewDomain domain = domain("d", 100, true, 70, List.of(requirement));
        InterviewPreparationProfile profile = profileWrapping(domain);
        InterviewReadinessService service = new InterviewReadinessService(new InterviewProfileService(), List.of(), POLICY_75);

        InterviewReadinessResult result = service.calculate(profile);

        InterviewRequirementReadiness requirementReadiness = result.domains().get(0).requirements().get(0);
        assertFalse(requirementReadiness.measurable(), "no resolver supports DECK, so this must not silently become 0%");
        assertNull(requirementReadiness.scorePercent());
        assertTrue(requirementReadiness.note().toLowerCase().contains("resolver"));
        assertEquals(InterviewReadinessStatus.INSUFFICIENT_DATA, result.status());
    }

    @Test
    void unknownProfileIdReturnsEmptyRatherThanThrowing() {
        // findProfile("does-not-exist") short-circuits Optional.map before calculate(profile) - and
        // therefore any resolver/database access - ever runs, so this stays database-free.
        Optional<InterviewReadinessResult> result = new InterviewReadinessService().calculate("does-not-exist");

        assertEquals(Optional.empty(), result);
    }
}

package com.codefit.peer.protocol;

import com.codefit.model.InterviewPreparationProfile;
import com.codefit.service.InterviewDomainReadiness;
import com.codefit.service.InterviewDomainReadinessStatus;
import com.codefit.service.InterviewProfileService;
import com.codefit.service.InterviewReadinessResult;
import com.codefit.service.InterviewReadinessService;
import com.codefit.service.InterviewReadinessStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snapshots preserve readiness coverage and critical gates exactly as the readiness engine computed them. */
class PreparationSnapshotContractTest {
    private static final Instant CAPTURED = Instant.parse("2026-09-21T18:00:00Z");
    private static final byte[] FP = new byte[32];

    private static DomainSnapshot critical(DomainStatus status, Integer score, int measured, int total) {
        int coverage = (measured * 200 + total) / (2 * total);
        return new DomainSnapshot("concurrency", 60, true, 70, score, coverage, measured, total, status);
    }

    private static DomainSnapshot sql(Integer score, int measured) {
        return new DomainSnapshot("sql", 40, false, null, score, measured * 50,
                measured, 2, score == null ? DomainStatus.NOT_MEASURED : DomainStatus.MEASURED);
    }

    private static PreparationSnapshot snapshot(PreparationStatus status, Integer overall, int coverage,
                                                DomainSnapshot... domains) {
        return new PreparationSnapshot("synthetic-backend", FP, 1, 75, CAPTURED, overall, coverage, status, List.of(domains));
    }

    @Test
    void readyCannotBeClaimedWithAFailingOrPartialCriticalGate() {
        // 60% * 95 + 40% * 90 = 93, full coverage; the critical gate FAILs (e.g. mock evidence).
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(PreparationStatus.READY, 93, 100, critical(DomainStatus.FAIL, 95, 2, 2), sql(90, 2)));
        // Partial critical gate: effective weights 30 + 40 = 70 -> coverage 70, overall 92.
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(PreparationStatus.READY, 92, 70, critical(DomainStatus.PARTIAL, 95, 1, 2), sql(90, 2)));
        assertEquals(PreparationStatus.INSUFFICIENT_DATA,
                snapshot(PreparationStatus.INSUFFICIENT_DATA, 92, 70, critical(DomainStatus.PARTIAL, 95, 1, 2), sql(90, 2)).status());
    }

    @Test
    void failCanCarryAnAboveThresholdScoreBecauseMockEvidenceForcesFail() {
        PreparationSnapshot snapshot = snapshot(PreparationStatus.NOT_READY, 93, 100,
                critical(DomainStatus.FAIL, 95, 2, 2), sql(90, 2));
        assertEquals(List.of("concurrency"), snapshot.blockingCriticalDomainIds());
    }

    @Test
    void readyMayCoexistWithUnmeasuredNonCriticalDomainsSoCoverageTravelsWithIt() {
        PreparationSnapshot snapshot = snapshot(PreparationStatus.READY, 80, 60, critical(DomainStatus.PASS, 80, 2, 2), sql(null, 0));
        assertTrue(snapshot.blockingCriticalDomainIds().isEmpty());
        assertEquals(60, snapshot.coveragePercent());
    }

    @Test
    void declaredOverallAndCoverageMustMatchTheDomains() {
        DomainSnapshot gate = new DomainSnapshot("gate", 10, true, 70, 80, 100, 1, 1, DomainStatus.PASS);
        DomainSnapshot other = new DomainSnapshot("other", 90, false, null, 20, 100, 1, 1, DomainStatus.MEASURED);
        // Review counterexample: the engine computes overall 26, coverage 100 -> NOT_READY.
        assertThrows(IllegalArgumentException.class, () -> new PreparationSnapshot("p", FP, 1, 75, CAPTURED, 100, 0,
                PreparationStatus.READY, List.of(gate, other)));
        assertThrows(IllegalArgumentException.class, () -> new PreparationSnapshot("p", FP, 1, 75, CAPTURED, 26, 0,
                PreparationStatus.NOT_READY, List.of(gate, other)), "coverage alone wrong");
        assertThrows(IllegalArgumentException.class, () -> new PreparationSnapshot("p", FP, 1, 75, CAPTURED, 27, 100,
                PreparationStatus.NOT_READY, List.of(gate, other)), "overall off by one");
        assertThrows(IllegalArgumentException.class, () -> new PreparationSnapshot("p", FP, 1, 75, CAPTURED, null, 100,
                PreparationStatus.INSUFFICIENT_DATA, List.of(gate, other)), "overall omitted despite measured domains");
        PreparationSnapshot consistent = new PreparationSnapshot("p", FP, 1, 75, CAPTURED, 26, 100,
                PreparationStatus.NOT_READY, List.of(gate, other));
        assertEquals(PreparationStatus.NOT_READY, consistent.status());
    }

    @Test
    void nothingMeasuredMeansNoOverallAndZeroCoverage() {
        assertEquals(PreparationStatus.INSUFFICIENT_DATA, snapshot(PreparationStatus.INSUFFICIENT_DATA, null, 0,
                new DomainSnapshot("concurrency", 60, true, 70, null, 0, 0, 2, DomainStatus.NOT_MEASURED), sql(null, 0)).status());
        assertThrows(IllegalArgumentException.class, () -> snapshot(PreparationStatus.INSUFFICIENT_DATA, 0, 0,
                new DomainSnapshot("concurrency", 60, true, 70, null, 0, 0, 2, DomainStatus.NOT_MEASURED), sql(null, 0)));
    }

    @Test
    void aScoreRequiresMeasuredEvidence() {
        // Review counterexample: a PASS critical gate with 0/0 measured requirements and a score of 100.
        assertThrows(IllegalArgumentException.class,
                () -> new DomainSnapshot("gate", 100, true, 70, 100, 0, 0, 0, DomainStatus.PASS));
        assertThrows(IllegalArgumentException.class,
                () -> new DomainSnapshot("sql", 40, false, null, 50, 0, 0, 2, DomainStatus.MEASURED));
        assertThrows(IllegalArgumentException.class,
                () -> new DomainSnapshot("sql", 40, false, null, null, 50, 1, 2, DomainStatus.NOT_MEASURED));
    }

    @Test
    void domainInvariantsMatchTheReadinessEngine() {
        assertThrows(IllegalArgumentException.class, () -> critical(DomainStatus.PASS, 80, 1, 2)); // incomplete
        assertThrows(IllegalArgumentException.class, () -> critical(DomainStatus.PASS, 60, 2, 2)); // below threshold
        assertThrows(IllegalArgumentException.class, () -> critical(DomainStatus.PARTIAL, 60, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> critical(DomainStatus.MEASURED, 80, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> critical(DomainStatus.NOT_MEASURED, 80, 2, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new DomainSnapshot("sql", 40, false, null, 70, 50, 1, 2, DomainStatus.PASS));
        assertThrows(IllegalArgumentException.class, // coverage must equal round(measured/total)
                () -> new DomainSnapshot("sql", 40, false, null, 70, 60, 1, 2, DomainStatus.MEASURED));
        // 199 of 200 requirements rounds to 100% coverage but is still PARTIAL, as in the engine.
        assertEquals(100, critical(DomainStatus.PARTIAL, 80, 199, 200).coveragePercent());
    }

    @Test
    void weightsMustSumToOneHundredAndDomainIdsMustBeUnique() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(PreparationStatus.INSUFFICIENT_DATA, null, 0,
                new DomainSnapshot("concurrency", 50, true, 70, null, 0, 0, 2, DomainStatus.NOT_MEASURED), sql(null, 0)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(PreparationStatus.INSUFFICIENT_DATA, null, 0,
                new DomainSnapshot("sql", 60, false, null, null, 0, 0, 2, DomainStatus.NOT_MEASURED), sql(null, 0)));
        // Profile order is preserved (not sorted): it is part of the engine's arithmetic.
        PreparationSnapshot profileOrder = snapshot(PreparationStatus.READY, 80, 60, sql(null, 0), critical(DomainStatus.PASS, 80, 2, 2));
        assertEquals(List.of("sql", "concurrency"), profileOrder.domains().stream().map(DomainSnapshot::domainId).toList());
    }

    @Test
    void adapterCopiesARealReadinessResultFaithfully() {
        InterviewPreparationProfile profile = ProtocolFixtures.syntheticProfile(70);
        InterviewReadinessResult result = new InterviewReadinessResult(profile.getId(), profile.getTitle(), List.of(
                new InterviewDomainReadiness("sql", "SQL", 40, false, null, 75, 50, 1, 2, InterviewDomainReadinessStatus.MEASURED, List.of()),
                new InterviewDomainReadiness("concurrency", "Concurrency", 60, true, 70, 72, 50, 1, 2,
                        InterviewDomainReadinessStatus.PARTIAL, List.of())),
                73, 50, InterviewReadinessStatus.INSUFFICIENT_DATA, List.of("concurrency"));

        PreparationSnapshot snapshot = PreparationSnapshots.fromReadiness(profile, result,
                InterviewReadinessService.DEFAULT_POLICY.overallReadinessThresholdPercent(), CAPTURED);

        assertEquals(PreparationStatus.INSUFFICIENT_DATA, snapshot.status());
        assertEquals(73, snapshot.overallPercent());
        assertEquals(50, snapshot.coveragePercent());
        assertEquals(List.of("concurrency"), snapshot.blockingCriticalDomainIds());
        assertEquals(List.of("sql", "concurrency"), snapshot.domains().stream().map(DomainSnapshot::domainId).toList(),
                "engine (profile) order is preserved");
        assertArrayEquals(PreparationProfileFingerprint.of(profile), snapshot.profileFingerprint());
    }

    @Test
    void adapterRejectsAResultForAnotherProfile() {
        InterviewReadinessResult result = new InterviewReadinessResult("other", "Other", List.of(), null, 0,
                InterviewReadinessStatus.INSUFFICIENT_DATA, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> PreparationSnapshots.fromReadiness(ProtocolFixtures.syntheticProfile(70), result, 75, CAPTURED));
    }

    @Test
    void fingerprintTracksScoringRelevantDefinitionOnly() {
        byte[] base = PreparationProfileFingerprint.of(ProtocolFixtures.syntheticProfile(70));
        assertArrayEquals(base, PreparationProfileFingerprint.of(ProtocolFixtures.syntheticProfile(70)));
        assertFalse(java.util.Arrays.equals(base, PreparationProfileFingerprint.of(ProtocolFixtures.syntheticProfile(71))),
                "a threshold change must change comparability");
        InterviewPreparationProfile renamed = new InterviewPreparationProfile("synthetic-backend", "Renamed title",
                "Different description", ProtocolFixtures.syntheticProfile(70).getDomains());
        assertArrayEquals(base, PreparationProfileFingerprint.of(renamed), "titles are presentation, not definition");
    }

    @Test
    void comparabilityRequiresSameProfileFingerprintAndScoringVersion() {
        PreparationSnapshot a = ProtocolFixtures.snapshot();
        assertTrue(a.readinessComparableWith(ProtocolFixtures.snapshot()));
        PreparationSnapshot otherScoring = new PreparationSnapshot(a.profileId(), a.profileFingerprint(), 2,
                a.overallThresholdPercent(), a.capturedAt(), a.overallPercent(), a.coveragePercent(), a.status(), a.domains());
        assertFalse(a.scoresComparableWith(otherScoring));
        assertFalse(a.readinessComparableWith(otherScoring));
    }

    @Test
    void readinessVerdictsAreComparableOnlyUnderTheSameOverallThreshold() {
        DomainSnapshot gate = critical(DomainStatus.PASS, 90, 2, 2);
        DomainSnapshot measuredSql = sql(90, 2);
        PreparationSnapshot lenient = new PreparationSnapshot("synthetic-backend", FP, 1, 75, CAPTURED, 90, 100,
                PreparationStatus.READY, List.of(gate, measuredSql));
        PreparationSnapshot strict = new PreparationSnapshot("synthetic-backend", FP, 1, 95, CAPTURED, 90, 100,
                PreparationStatus.NOT_READY, List.of(gate, measuredSql));
        assertTrue(lenient.scoresComparableWith(strict), "the same 90% may be compared as a raw number");
        assertFalse(lenient.readinessComparableWith(strict), "READY at 75 and NOT_READY at 95 are different grades");
    }

    @Test
    void bundledProfileIdsFitTheProtocolIdentifierGrammar() {
        for (InterviewPreparationProfile profile : new InterviewProfileService().getProfiles()) {
            ProtocolText.identifier(profile.getId(), "profile id");
            profile.getDomains().forEach(domain -> ProtocolText.identifier(domain.getId(), "domain id"));
            assertEquals(32, PreparationProfileFingerprint.of(profile).length);
        }
    }
}

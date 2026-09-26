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

    private static PreparationSnapshot snapshot(PreparationStatus status, Integer overall, DomainSnapshot... domains) {
        return new PreparationSnapshot("synthetic-backend", FP, 1, 75, CAPTURED, overall, 80, status, List.of(domains));
    }

    @Test
    void readyCannotBeClaimedWithAFailingOrPartialCriticalGate() {
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(PreparationStatus.READY, 90, critical(DomainStatus.FAIL, 95, 2, 2), sql(90, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot(PreparationStatus.READY, 90, critical(DomainStatus.PARTIAL, 95, 1, 2), sql(90, 2)));
        assertEquals(PreparationStatus.INSUFFICIENT_DATA,
                snapshot(PreparationStatus.INSUFFICIENT_DATA, 90, critical(DomainStatus.PARTIAL, 95, 1, 2), sql(90, 2)).status());
    }

    @Test
    void failCanCarryAnAboveThresholdScoreBecauseMockEvidenceForcesFail() {
        PreparationSnapshot snapshot = snapshot(PreparationStatus.NOT_READY, 90, critical(DomainStatus.FAIL, 95, 2, 2), sql(90, 2));
        assertEquals(List.of("concurrency"), snapshot.blockingCriticalDomainIds());
    }

    @Test
    void readyMayCoexistWithUnmeasuredNonCriticalDomainsSoCoverageTravelsWithIt() {
        PreparationSnapshot snapshot = snapshot(PreparationStatus.READY, 80, critical(DomainStatus.PASS, 80, 2, 2), sql(null, 0));
        assertTrue(snapshot.blockingCriticalDomainIds().isEmpty());
        assertEquals(80, snapshot.coveragePercent());
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
    void weightsMustSumToOneHundredAndDomainsMustBeSorted() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(PreparationStatus.INSUFFICIENT_DATA, null,
                new DomainSnapshot("concurrency", 50, true, 70, null, 0, 0, 2, DomainStatus.NOT_MEASURED), sql(null, 0)));
        ProtocolException unsorted = assertThrows(ProtocolException.class,
                () -> snapshot(PreparationStatus.READY, 80, sql(null, 0), critical(DomainStatus.PASS, 80, 2, 2)));
        assertEquals(RejectionReason.NON_CANONICAL, unsorted.reason());
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
        assertEquals(List.of("concurrency", "sql"), snapshot.domains().stream().map(DomainSnapshot::domainId).toList());
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
        assertTrue(a.comparableWith(ProtocolFixtures.snapshot()));
        PreparationSnapshot otherScoring = new PreparationSnapshot(a.profileId(), a.profileFingerprint(), 2,
                a.overallThresholdPercent(), a.capturedAt(), a.overallPercent(), a.coveragePercent(), a.status(), a.domains());
        assertFalse(a.comparableWith(otherScoring));
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

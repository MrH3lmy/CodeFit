package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationSnapshots;
import com.codefit.peer.snapshot.LocalPreparationCheckpoint;
import com.codefit.repository.LocalPreparationCheckpointRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository-level revision semantics for {@link LocalPreparationCheckpointRepository}, isolated from
 * the real readiness engine's DB-backed evidence resolution (that parity is already covered by
 * {@code PreparationSnapshotEngineParityTest}). Fixture snapshots are built the same way that test
 * does — through the real {@code InterviewReadinessService} aggregation and
 * {@link PreparationSnapshots#fromReadiness} adapter on a tiny synthetic profile — purely so two
 * genuinely different, internally-consistent {@link PreparationSnapshot} values are easy to produce
 * without needing real mock-interview/deck fixtures in the database.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class LocalPreparationCheckpointRepositoryTest {
    private static final String PROFILE_ID = "repo-test-profile";

    private final LocalPreparationCheckpointRepository repository = new LocalPreparationCheckpointRepository();

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
    }

    private static PreparationSnapshot snapshotWithScore(int score, Instant capturedAt) {
        InterviewRequirement requirement = InterviewRequirement.available("r1", "R", null, InterviewMaterialType.DECK, "k1");
        InterviewDomain domain = new InterviewDomain("d1", "D", null, 100, false, null, List.of(requirement));
        InterviewRequirementReadiness readiness =
                InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.DECK, score, "s");
        InterviewDomainReadiness domainReadiness = InterviewReadinessService.buildDomainReadiness(domain, List.of(readiness));
        InterviewPreparationProfile profile = new InterviewPreparationProfile(PROFILE_ID, "Repo Test", null, List.of(domain));
        InterviewReadinessResult result =
                InterviewReadinessService.buildResult(profile, List.of(domainReadiness), InterviewReadinessService.DEFAULT_POLICY);
        return PreparationSnapshots.fromReadiness(profile, result,
                InterviewReadinessService.DEFAULT_POLICY.overallReadinessThresholdPercent(), capturedAt);
    }

    @Test
    void firstSaveForANewProfileDayIsRecordedAsRevisionOne() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        LocalPreparationCheckpointRepository.SaveResult result =
                repository.save(PROFILE_ID, day, snapshotWithScore(80, Instant.parse("2026-01-15T09:00:00Z")));

        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.RECORDED_FIRST, result.outcome());
        assertEquals(1, result.checkpoint().revision());
    }

    @Test
    void identicalContentResaveOnTheSameDayIsUnchangedAndKeepsTheRevision() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        PreparationSnapshot first = snapshotWithScore(80, Instant.parse("2026-01-15T09:00:00Z"));
        repository.save(PROFILE_ID, day, first);

        // Same score (same content) but a later capturedAt - a pure "nothing changed" resend.
        PreparationSnapshot resend = snapshotWithScore(80, Instant.parse("2026-01-15T18:00:00Z"));
        LocalPreparationCheckpointRepository.SaveResult result = repository.save(PROFILE_ID, day, resend);

        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.UNCHANGED, result.outcome());
        assertEquals(1, result.checkpoint().revision(), "an unchanged resend never bumps the revision");
    }

    @Test
    void genuineContentChangeOnTheSameDayReplacesAndBumpsTheRevisionEvenWithinTheSameWallClockSecond() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        Instant firstInstant = Instant.parse("2026-01-15T09:00:00.100Z");
        Instant secondInstant = Instant.parse("2026-01-15T09:00:00.900Z");
        assertEquals(firstInstant.getEpochSecond(), secondInstant.getEpochSecond(), "sanity check: same whole epoch second");

        LocalPreparationCheckpointRepository.SaveResult first = repository.save(PROFILE_ID, day, snapshotWithScore(80, firstInstant));
        assertEquals(1, first.checkpoint().revision());

        LocalPreparationCheckpointRepository.SaveResult second = repository.save(PROFILE_ID, day, snapshotWithScore(95, secondInstant));

        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.REPLACED, second.outcome());
        assertEquals(2, second.checkpoint().revision(), "a genuine same-second content change must still get a strictly higher revision");
        assertTrue(second.checkpoint().revision() > first.checkpoint().revision());

        LocalPreparationCheckpoint reloaded = repository.findByDate(PROFILE_ID, day).orElseThrow();
        assertEquals(2, reloaded.revision());
        assertEquals(95, reloaded.snapshot().overallPercent());
    }

    @Test
    void differentDaysAreIndependentRevisionStreamsEachStartingAtOne() {
        LocalDate day1 = LocalDate.of(2026, 1, 15);
        LocalDate day2 = LocalDate.of(2026, 1, 16);

        LocalPreparationCheckpointRepository.SaveResult first =
                repository.save(PROFILE_ID, day1, snapshotWithScore(80, Instant.parse("2026-01-15T09:00:00Z")));
        LocalPreparationCheckpointRepository.SaveResult secondDay =
                repository.save(PROFILE_ID, day2, snapshotWithScore(95, Instant.parse("2026-01-16T09:00:00Z")));

        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.RECORDED_FIRST, first.outcome());
        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.RECORDED_FIRST, secondDay.outcome());
        assertEquals(1, first.checkpoint().revision());
        assertEquals(1, secondDay.checkpoint().revision(), "a different day's first capture is its own revision-1 row, "
                + "independent of the previous day's local revision - this is exactly why the WIRE revision "
                + "(PreparationSnapshotWireStateRepository) cannot reuse this local, per-day number");

        Optional<LocalPreparationCheckpoint> loadedDay1 = repository.findByDate(PROFILE_ID, day1);
        Optional<LocalPreparationCheckpoint> loadedDay2 = repository.findByDate(PROFILE_ID, day2);
        assertEquals(80, loadedDay1.orElseThrow().snapshot().overallPercent());
        assertEquals(95, loadedDay2.orElseThrow().snapshot().overallPercent());
    }
}

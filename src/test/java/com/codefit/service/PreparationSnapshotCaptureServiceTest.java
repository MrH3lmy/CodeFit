package com.codefit.service;

import com.codefit.model.InterviewPreparationProfile;
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
 * #183's dated preparation checkpoints, exercised against the real, unmodified
 * {@link InterviewReadinessService} and the real registered profile - never a hand-rolled readiness
 * stand-in, so this only ever tests that the capture/persistence layer is honest about what the real
 * engine said, not a second copy of the engine's own scoring rules (already covered independently by
 * {@link PreparationSnapshotEngineParityTest}).
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PreparationSnapshotCaptureServiceTest {

    private PreparationSnapshotCaptureService service;
    private InterviewReadinessService readinessService;
    private InterviewPreparationProfile profile;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        service = new PreparationSnapshotCaptureService();
        readinessService = new InterviewReadinessService();
        profile = new InterviewProfileService().getRevolutJavaProfile();
    }

    @Test
    void captureStoresExactlyWhatTheRealEngineComputedRightNow() {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");

        PreparationSnapshotCaptureService.Capture capture = service.capture(profile.getId(), now).orElseThrow();

        InterviewReadinessResult directResult = readinessService.calculate(profile);
        PreparationSnapshot expected = PreparationSnapshots.fromReadiness(profile, directResult,
                InterviewReadinessService.DEFAULT_POLICY.overallReadinessThresholdPercent(), now);

        PreparationSnapshot actual = capture.checkpoint().snapshot();
        assertEquals(expected.overallPercent(), actual.overallPercent());
        assertEquals(expected.coveragePercent(), actual.coveragePercent());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.blockingCriticalDomainIds(), actual.blockingCriticalDomainIds());
        assertEquals(expected.domains(), actual.domains());
    }

    @Test
    void capturingForAnUnknownProfileReturnsEmpty() {
        Optional<PreparationSnapshotCaptureService.Capture> result =
                service.capture("no-such-profile", Instant.parse("2026-01-15T12:00:00Z"));
        assertTrue(result.isEmpty());
    }

    @Test
    void missingReadinessStaysUnavailableUntilACheckpointIsActuallyCaptured() {
        Optional<LocalPreparationCheckpoint> neverCaptured =
                service.findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 15));
        assertTrue(neverCaptured.isEmpty(), "no checkpoint was ever captured for this day - must be unavailable, not reconstructed");
    }

    @Test
    void historicalReadinessExistsOnlyAfterARealSnapshotWasCapturedForThatDay() {
        service.capture(profile.getId(), Instant.parse("2026-01-15T12:00:00Z"));

        Optional<LocalPreparationCheckpoint> capturedDay =
                service.findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 15));
        assertTrue(capturedDay.isPresent());

        Optional<LocalPreparationCheckpoint> neverCapturedDay =
                service.findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 16));
        assertTrue(neverCapturedDay.isEmpty(),
                "a day that was never actually captured must stay unavailable, never reconstructed from today's state");
    }

    @Test
    void recapturingTheSameUtcDayReplacesRatherThanDuplicates() {
        PreparationSnapshotCaptureService.Capture first =
                service.capture(profile.getId(), Instant.parse("2026-01-15T09:00:00Z")).orElseThrow();
        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.RECORDED_FIRST, first.outcome());

        PreparationSnapshotCaptureService.Capture second =
                service.capture(profile.getId(), Instant.parse("2026-01-15T18:00:00Z")).orElseThrow();
        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.REPLACED, second.outcome());

        List<LocalPreparationCheckpoint> history = service.history(profile.getId());
        assertEquals(1, history.size(), "the same UTC day is a correction, not a second historical row");
        assertEquals(second.checkpoint().snapshot().capturedAt(), history.get(0).snapshot().capturedAt());
    }

    @Test
    void captureOnADifferentUtcDayIsANewPermanentHistoricalRow() {
        service.capture(profile.getId(), Instant.parse("2026-01-15T09:00:00Z"));
        service.capture(profile.getId(), Instant.parse("2026-01-16T09:00:00Z"));

        List<LocalPreparationCheckpoint> history = service.history(profile.getId());
        assertEquals(2, history.size());
        assertEquals(LocalDate.of(2026, 1, 15), history.get(0).checkpointDateUtc());
        assertEquals(LocalDate.of(2026, 1, 16), history.get(1).checkpointDateUtc());
    }

    @Test
    void captureReloadPreservesLogicalIdRevisionAndTimestamps() {
        Instant now = Instant.parse("2026-01-15T09:30:00Z");
        PreparationSnapshotCaptureService.Capture captured = service.capture(profile.getId(), now).orElseThrow();

        LocalPreparationCheckpoint reloaded = service.findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 15)).orElseThrow();

        assertEquals(profile.getId(), reloaded.profileId());
        assertEquals(captured.checkpoint().revision(), reloaded.revision());
        assertEquals(captured.checkpoint().snapshot().capturedAt(), reloaded.snapshot().capturedAt());
        assertEquals(captured.checkpoint().snapshot().scoringVersion(), reloaded.snapshot().scoringVersion());
        assertEquals(captured.checkpoint().snapshot().domains(), reloaded.snapshot().domains());
    }
}

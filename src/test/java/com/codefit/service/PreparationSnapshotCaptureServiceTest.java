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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
 *
 * <p>{@link PreparationSnapshotCaptureService#capture(String)} takes no {@code Instant} parameter — it
 * always uses its own trusted {@link Clock} — so every test here builds its own service via
 * {@link #serviceAt} with a {@link Clock#fixed} at the instant the test wants to simulate "now" as.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PreparationSnapshotCaptureServiceTest {

    private InterviewReadinessService readinessService;
    private InterviewPreparationProfile profile;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        readinessService = new InterviewReadinessService();
        profile = new InterviewProfileService().getRevolutJavaProfile();
    }

    /** A fresh service whose trusted clock is fixed at {@code now} — never a caller-chosen {@code Instant} parameter. */
    private PreparationSnapshotCaptureService serviceAt(Instant now) {
        return new PreparationSnapshotCaptureService(new InterviewReadinessService(), new InterviewProfileService(),
                new LocalPreparationCheckpointRepository(), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void captureStoresExactlyWhatTheRealEngineComputedRightNow() {
        Instant now = Instant.parse("2026-01-15T12:00:00Z");

        PreparationSnapshotCaptureService.Capture capture = serviceAt(now).capture(profile.getId()).orElseThrow();

        InterviewReadinessResult directResult = readinessService.calculate(profile);
        PreparationSnapshot expected = PreparationSnapshots.fromReadiness(profile, directResult,
                InterviewReadinessService.DEFAULT_POLICY.overallReadinessThresholdPercent(), now);

        PreparationSnapshot actual = capture.checkpoint().snapshot();
        assertEquals(expected.overallPercent(), actual.overallPercent());
        assertEquals(expected.coveragePercent(), actual.coveragePercent());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.blockingCriticalDomainIds(), actual.blockingCriticalDomainIds());
        assertEquals(expected.domains(), actual.domains());
        assertEquals(now, capture.checkpoint().snapshot().capturedAt(), "capturedAt must be exactly this service's trusted clock instant");
    }

    @Test
    void capturingForAnUnknownProfileReturnsEmpty() {
        Optional<PreparationSnapshotCaptureService.Capture> result =
                serviceAt(Instant.parse("2026-01-15T12:00:00Z")).capture("no-such-profile");
        assertTrue(result.isEmpty());
    }

    @Test
    void missingReadinessStaysUnavailableUntilACheckpointIsActuallyCaptured() {
        Optional<LocalPreparationCheckpoint> neverCaptured =
                serviceAt(Instant.parse("2026-01-15T12:00:00Z")).findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 15));
        assertTrue(neverCaptured.isEmpty(), "no checkpoint was ever captured for this day - must be unavailable, not reconstructed");
    }

    @Test
    void historicalReadinessExistsOnlyAfterARealSnapshotWasCapturedForThatDay() {
        PreparationSnapshotCaptureService service = serviceAt(Instant.parse("2026-01-15T12:00:00Z"));
        service.capture(profile.getId());

        Optional<LocalPreparationCheckpoint> capturedDay = service.findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 15));
        assertTrue(capturedDay.isPresent());

        Optional<LocalPreparationCheckpoint> neverCapturedDay = service.findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 16));
        assertTrue(neverCapturedDay.isEmpty(),
                "a day that was never actually captured must stay unavailable, never reconstructed from today's state");
    }

    @Test
    void recapturingTheSameUtcDayWithUnchangedReadinessIsIdempotent() {
        // Real evidence doesn't change between the two captures (an empty, isolated test DB both times),
        // so the real readiness engine genuinely reports the same content both times - this must be an
        // idempotent no-op, not a manufactured "correction" of identical data.
        PreparationSnapshotCaptureService.Capture first =
                serviceAt(Instant.parse("2026-01-15T09:00:00Z")).capture(profile.getId()).orElseThrow();
        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.RECORDED_FIRST, first.outcome());

        PreparationSnapshotCaptureService.Capture second =
                serviceAt(Instant.parse("2026-01-15T18:00:00Z")).capture(profile.getId()).orElseThrow();
        assertEquals(LocalPreparationCheckpointRepository.SaveOutcome.UNCHANGED, second.outcome());
        assertEquals(first.checkpoint().revision(), second.checkpoint().revision(), "unchanged readiness never bumps the revision");

        List<LocalPreparationCheckpoint> history = serviceAt(Instant.parse("2026-01-15T18:00:00Z")).history(profile.getId());
        assertEquals(1, history.size(), "the same UTC day with unchanged content is still exactly one historical row");
    }

    @Test
    void captureOnADifferentUtcDayIsANewPermanentHistoricalRow() {
        serviceAt(Instant.parse("2026-01-15T09:00:00Z")).capture(profile.getId());
        PreparationSnapshotCaptureService serviceDay2 = serviceAt(Instant.parse("2026-01-16T09:00:00Z"));
        serviceDay2.capture(profile.getId());

        List<LocalPreparationCheckpoint> history = serviceDay2.history(profile.getId());
        assertEquals(2, history.size());
        assertEquals(LocalDate.of(2026, 1, 15), history.get(0).checkpointDateUtc());
        assertEquals(LocalDate.of(2026, 1, 16), history.get(1).checkpointDateUtc());
    }

    @Test
    void captureReloadPreservesLogicalIdRevisionAndTimestamps() {
        Instant now = Instant.parse("2026-01-15T09:30:00Z");
        PreparationSnapshotCaptureService service = serviceAt(now);
        PreparationSnapshotCaptureService.Capture captured = service.capture(profile.getId()).orElseThrow();

        LocalPreparationCheckpoint reloaded = service.findCheckpoint(profile.getId(), LocalDate.of(2026, 1, 15)).orElseThrow();

        assertEquals(profile.getId(), reloaded.profileId());
        assertEquals(captured.checkpoint().revision(), reloaded.revision());
        assertEquals(captured.checkpoint().snapshot().capturedAt(), reloaded.snapshot().capturedAt());
        assertEquals(captured.checkpoint().snapshot().scoringVersion(), reloaded.snapshot().scoringVersion());
        assertEquals(captured.checkpoint().snapshot().domains(), reloaded.snapshot().domains());
    }

    @Test
    void productionServiceUsesRealCurrentTimeNeverABackdatedCapture() {
        // The public, zero-argument constructor is what every real caller gets: it hardcodes
        // Clock.systemUTC(), and capture(String) has no Instant parameter anywhere in its signature, so
        // there is no argument a caller could pass to make this record anything other than genuinely now.
        // Both bounds are floored to millisecond precision the same way the service itself floors
        // capturedAt (PreparationSnapshot requires millisecond-precision wire timestamps), so a
        // same-millisecond "before" with a nonzero sub-millisecond remainder can't look later than a
        // floored capturedAt that is genuinely not earlier than it.
        Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        PreparationSnapshotCaptureService.Capture capture = new PreparationSnapshotCaptureService().capture(profile.getId()).orElseThrow();
        Instant after = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusMillis(1);

        Instant capturedAt = capture.checkpoint().snapshot().capturedAt();
        assertTrue(!capturedAt.isBefore(before) && !capturedAt.isAfter(after),
                "a production capture's timestamp must fall within the real wall-clock window it ran in: " + capturedAt);
    }
}

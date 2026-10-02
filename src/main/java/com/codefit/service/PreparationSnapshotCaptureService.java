package com.codefit.service;

import com.codefit.model.InterviewPreparationProfile;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationSnapshots;
import com.codefit.peer.snapshot.LocalPreparationCheckpoint;
import com.codefit.repository.LocalPreparationCheckpointRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Captures a dated, local {@link LocalPreparationCheckpoint} from the <em>existing, unchanged</em>
 * {@link InterviewReadinessService} (#183: "use the existing readiness implementation... do not
 * rewrite readiness scoring"). This class computes nothing about readiness itself; it only calls the
 * real engine, hands the result to the already-reviewed {@link PreparationSnapshots#fromReadiness}
 * adapter, and persists the dated result.
 *
 * <h2>Why there is no "capture as of a past date"</h2>
 * {@link InterviewReadinessService#calculate} has no "as of" parameter: it always scores against
 * whatever evidence exists right now. There is therefore no honest way to ask "what was readiness two
 * weeks ago" after the fact — doing so would just relabel <em>today's</em> result with an old date,
 * exactly the fabrication #183 forbids ("do not derive historical readiness today from the learner's
 * current readiness state"). This service always uses its own trusted {@link Clock} — never a
 * caller-supplied {@code Instant} — for both the engine's implicit "now" and the stored
 * {@code capturedAt}/{@code checkpointDateUtc}, so a genuine historical checkpoint can only ever come
 * from having actually called {@link #capture} on the date it describes. An earlier version of this
 * class took {@code capture(String profileId, Instant now)}: a caller could pass an arbitrary past
 * {@code Instant} and have today's real readiness recorded as if it were a checkpoint from that date —
 * exactly the backdating this class's own Javadoc already claimed was impossible. There is no
 * parameter anywhere in this class, production or test, that lets a caller choose that timestamp;
 * tests get determinism from {@link Clock#fixed} instead.
 */
public class PreparationSnapshotCaptureService {

    private final InterviewReadinessService readinessService;
    private final InterviewProfileService profileService;
    private final LocalPreparationCheckpointRepository checkpointRepository;
    private final Clock clock;

    public PreparationSnapshotCaptureService() {
        this(new InterviewReadinessService(), new InterviewProfileService(), new LocalPreparationCheckpointRepository(),
                Clock.systemUTC());
    }

    PreparationSnapshotCaptureService(InterviewReadinessService readinessService, InterviewProfileService profileService,
                                       LocalPreparationCheckpointRepository checkpointRepository, Clock clock) {
        this.readinessService = readinessService;
        this.profileService = profileService;
        this.checkpointRepository = checkpointRepository;
        this.clock = clock;
    }

    /**
     * Computes today's readiness for {@code profileId} right now (this service's own trusted clock)
     * and persists it as the checkpoint for that UTC calendar day, replacing any earlier checkpoint
     * already captured that same UTC day (a correction), never creating a second entry for it.
     *
     * @return empty when {@code profileId} does not resolve to a known profile; readiness being
     *         {@code INSUFFICIENT_DATA}/unmeasured is still captured — that is itself the real state
     */
    public Optional<Capture> capture(String profileId) {
        Optional<InterviewPreparationProfile> profile = profileService.findProfile(profileId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        // Truncated to millisecond precision: PreparationSnapshot requires wire timestamps at
        // millisecond precision, and Clock.systemUTC() reports nanoseconds on most JVMs.
        Instant capturedAt = Instant.ofEpochMilli(clock.instant().toEpochMilli());
        InterviewReadinessResult result = readinessService.calculate(profile.get());
        PreparationSnapshot snapshot = PreparationSnapshots.fromReadiness(profile.get(), result,
                InterviewReadinessService.DEFAULT_POLICY.overallReadinessThresholdPercent(), capturedAt);
        LocalDate checkpointDateUtc = capturedAt.atOffset(ZoneOffset.UTC).toLocalDate();
        LocalPreparationCheckpointRepository.SaveResult saveResult = checkpointRepository.save(profileId, checkpointDateUtc, snapshot);
        return Optional.of(new Capture(saveResult.checkpoint(), saveResult.outcome()));
    }

    /** The checkpoint for exactly this UTC day, if one was ever captured - never reconstructed from today's state. */
    public Optional<LocalPreparationCheckpoint> findCheckpoint(String profileId, LocalDate checkpointDateUtc) {
        return checkpointRepository.findByDate(profileId, checkpointDateUtc);
    }

    /** Every checkpoint ever captured for this profile, oldest first: the real, non-fabricated historical trend. */
    public List<LocalPreparationCheckpoint> history(String profileId) {
        return checkpointRepository.findAllForProfile(profileId);
    }

    public record Capture(LocalPreparationCheckpoint checkpoint, LocalPreparationCheckpointRepository.SaveOutcome outcome) {
    }
}

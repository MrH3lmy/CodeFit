package com.codefit.service;

import com.codefit.model.InterviewPreparationProfile;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationSnapshots;
import com.codefit.peer.snapshot.LocalPreparationCheckpoint;
import com.codefit.repository.LocalPreparationCheckpointRepository;

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
 * current readiness state"). This service only ever uses the caller's {@code now} for both the
 * engine's implicit "now" and the stored {@code capturedAt}/{@code checkpointDateUtc}, so a genuine
 * historical checkpoint can only ever come from having actually called {@link #capture} on the date it
 * describes. There is no parameter anywhere in this class that lets a caller backdate one.
 */
public class PreparationSnapshotCaptureService {

    private final InterviewReadinessService readinessService;
    private final InterviewProfileService profileService;
    private final LocalPreparationCheckpointRepository checkpointRepository;

    public PreparationSnapshotCaptureService() {
        this(new InterviewReadinessService(), new InterviewProfileService(), new LocalPreparationCheckpointRepository());
    }

    PreparationSnapshotCaptureService(InterviewReadinessService readinessService, InterviewProfileService profileService,
                                       LocalPreparationCheckpointRepository checkpointRepository) {
        this.readinessService = readinessService;
        this.profileService = profileService;
        this.checkpointRepository = checkpointRepository;
    }

    /**
     * Computes today's readiness for {@code profileId} right now and persists it as the checkpoint
     * for {@code now}'s UTC calendar day, replacing any earlier checkpoint already captured that same
     * UTC day (a correction), never creating a second entry for it.
     *
     * @return empty when {@code profileId} does not resolve to a known profile; readiness being
     *         {@code INSUFFICIENT_DATA}/unmeasured is still captured — that is itself the real state
     */
    public Optional<Capture> capture(String profileId, Instant now) {
        Optional<InterviewPreparationProfile> profile = profileService.findProfile(profileId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }
        Instant capturedAt = Instant.ofEpochMilli(now.toEpochMilli());
        InterviewReadinessResult result = readinessService.calculate(profile.get());
        PreparationSnapshot snapshot = PreparationSnapshots.fromReadiness(profile.get(), result,
                InterviewReadinessService.DEFAULT_POLICY.overallReadinessThresholdPercent(), capturedAt);
        LocalDate checkpointDateUtc = capturedAt.atOffset(ZoneOffset.UTC).toLocalDate();
        long revision = Math.min(Math.max(capturedAt.getEpochSecond(), 1L), 0xFFFF_FFFFL);
        LocalPreparationCheckpoint checkpoint = new LocalPreparationCheckpoint(profileId, checkpointDateUtc, revision, snapshot);
        LocalPreparationCheckpointRepository.SaveOutcome outcome = checkpointRepository.save(checkpoint);
        return Optional.of(new Capture(checkpoint, outcome));
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

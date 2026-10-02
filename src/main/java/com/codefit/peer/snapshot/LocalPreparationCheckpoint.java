package com.codefit.peer.snapshot;

import com.codefit.peer.protocol.PreparationSnapshot;

import java.time.LocalDate;
import java.util.Objects;

/**
 * #183's own local, dated checkpoint of one {@link PreparationSnapshot}. Readiness is otherwise
 * computed fresh at read time from whatever evidence exists right now
 * ({@code InterviewReadinessService}); it has no "as of a past date" parameter, so a checkpoint is the
 * <em>only</em> way a past readiness state can ever be shown later — it must be captured on the date
 * it describes, never reconstructed afterward (see {@code PreparationSnapshotCaptureService}, which is
 * the sole place {@link #checkpointDateUtc} and the wrapped snapshot's {@code capturedAt} are both set
 * from the same "now").
 *
 * <p>{@code checkpointDateUtc} is the UTC calendar day the capture ran on: at most one checkpoint per
 * profile per UTC day. Capturing again on the <em>same</em> UTC day is a correction that replaces this
 * row (a strictly later {@code capturedAt} within the day, via {@link #revision}); capturing on a
 * different UTC day is a genuinely new, permanent historical row that this one never supersedes.
 *
 * @param revision strictly increasing within one {@code (profileId, checkpointDateUtc)} pair
 */
public record LocalPreparationCheckpoint(String profileId, LocalDate checkpointDateUtc, long revision,
                                          PreparationSnapshot snapshot) {

    public LocalPreparationCheckpoint {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(checkpointDateUtc, "checkpointDateUtc");
        Objects.requireNonNull(snapshot, "snapshot");
        if (revision < 1) {
            throw new IllegalArgumentException("Revision must be >= 1.");
        }
        if (!profileId.equals(snapshot.profileId())) {
            throw new IllegalArgumentException("Checkpoint profileId must match the snapshot's own profileId.");
        }
    }
}

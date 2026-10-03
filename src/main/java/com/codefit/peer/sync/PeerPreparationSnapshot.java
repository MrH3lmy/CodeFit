package com.codefit.peer.sync;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.PreparationSnapshot;

import java.time.Instant;

/**
 * A validated, cached {@code PREPARATION_SNAPSHOT} received from a peer (#184): the author's current
 * readiness for one profile, as of {@code body.capturedAt()}. Only the latest revision per {@code
 * (author, objectId)} is ever stored - {@code PreparationSnapshot}'s object id is derived from
 * (author, recipient, profileId), so a newer capture for the same profile supersedes the previous one
 * rather than accumulating a history (#183's own local, per-day history is a completely separate,
 * never-synced table: {@code local_preparation_checkpoints}).
 */
public record PeerPreparationSnapshot(IdentityId author, ObjectId objectId, long epoch, long revision,
                                       PreparationSnapshot body, Instant receivedAt) {
}

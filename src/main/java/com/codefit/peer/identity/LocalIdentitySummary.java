package com.codefit.peer.identity;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

import java.time.Instant;

/**
 * The DTO summarizing a learner's own identity for display/service callers. Deliberately has no
 * private-key field of any kind — this is the shape that {@code IdentityService} returns from every
 * method that does not need to sign anything, so a caller can never even be handed key material by
 * accident (#181 requirement: key material must never appear in a summary DTO).
 */
public record LocalIdentitySummary(IdentityId id, IdentityKey publicKey, Instant createdAt,
                                    long highestKnownEpoch, long currentWriterEpoch, boolean sharingPaused,
                                    Instant lastRestoredAt) {
}

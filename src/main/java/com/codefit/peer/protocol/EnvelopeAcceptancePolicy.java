package com.codefit.peer.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * Receiver-side rules applied to an envelope that {@link EnvelopeCodec#decodeFrame(byte[])} already
 * parsed and signature-verified. Checks run in a fixed order so all conforming receivers agree:
 * <ol>
 *   <li>receiver in audience, else {@link RejectionReason#UNAUTHORIZED_AUDIENCE};</li>
 *   <li>{@code createdAt <= now + skew}, else {@link RejectionReason#NOT_YET_VALID};</li>
 *   <li>{@code expiresAt > now}, else {@link RejectionReason#EXPIRED};</li>
 *   <li>message id not already accepted, else {@link RejectionReason#DUPLICATE} (idempotent);</li>
 *   <li>epoch not below the author's highest accepted epoch, else {@link RejectionReason#STALE_EPOCH};</li>
 *   <li>{@code (epoch, sequence)} slot unused by a different message, else {@link RejectionReason#FORKED};</li>
 *   <li>object not tombstoned, else {@link RejectionReason#TOMBSTONED};</li>
 *   <li>revision newer than the held revision, else {@link RejectionReason#STALE_REVISION}.</li>
 * </ol>
 * Sequence gaps are normal (each recipient sees only what was shared with it). Whether the author's
 * current {@link ConsentRevision} grants the body's {@link MessageBody#requiredScope()} is checked by
 * the sync layer (#184), which owns persisted consent state.
 */
public final class EnvelopeAcceptancePolicy {
    private final IdentityId receiver;
    private final long maxClockSkewMillis;

    public EnvelopeAcceptancePolicy(IdentityId receiver) {
        this(receiver, ProtocolVersion.MAX_CLOCK_SKEW_MILLIS);
    }

    EnvelopeAcceptancePolicy(IdentityId receiver, long maxClockSkewMillis) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.maxClockSkewMillis = maxClockSkewMillis;
    }

    /** Classifies the envelope and, only when accepted, records it in {@code state}. */
    public AcceptanceVerdict evaluate(SignedEnvelope envelope, AuthorReplayState state, Instant now) {
        EnvelopeHeader header = envelope.header();
        if (!header.author().equals(state.author())) {
            throw new IllegalArgumentException("Replay state belongs to a different author.");
        }
        MessageId id = envelope.messageId();
        if (!header.audience().includes(receiver)) {
            return AcceptanceVerdict.reject(RejectionReason.UNAUTHORIZED_AUDIENCE, id);
        }
        if (header.createdAt().isAfter(now.plusMillis(maxClockSkewMillis))) {
            return AcceptanceVerdict.reject(RejectionReason.NOT_YET_VALID, id);
        }
        if (!header.expiresAt().isAfter(now)) {
            return AcceptanceVerdict.reject(RejectionReason.EXPIRED, id);
        }
        if (state.hasAccepted(id)) {
            return AcceptanceVerdict.reject(RejectionReason.DUPLICATE, id);
        }
        if (header.epoch() < state.highestEpoch()) {
            return AcceptanceVerdict.reject(RejectionReason.STALE_EPOCH, id);
        }
        MessageId occupant = state.slotOccupant(header.epoch(), header.sequence());
        if (occupant != null && !occupant.equals(id)) {
            return AcceptanceVerdict.reject(RejectionReason.FORKED, id);
        }
        if (state.isTombstoned(header.objectId())) {
            return AcceptanceVerdict.reject(RejectionReason.TOMBSTONED, id);
        }
        Long heldRevision = state.revisionOf(header.objectId());
        if (heldRevision != null && header.revision() <= heldRevision) {
            return AcceptanceVerdict.reject(RejectionReason.STALE_REVISION, id);
        }
        state.record(envelope, id);
        return AcceptanceVerdict.accept(id);
    }
}

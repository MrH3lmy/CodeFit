package com.codefit.peer.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * Authenticated envelope metadata. Every field is inside the signing bytes.
 *
 * @param minorVersion  envelope minor version the author wrote (informational within major 1)
 * @param author        Ed25519 identity key that signs the envelope
 * @param objectId      logical object this message is a revision of
 * @param epoch         writer-session epoch ({@link WriterEpoch}), 1..{@link WriterEpoch#maxAt}(createdAt)
 * @param sequence      per-author, per-epoch strictly increasing counter (1..2^63-1); gaps are normal with selective sharing
 * @param revision      per-object revision within the epoch (1..2^32-1); objects order by (epoch, revision)
 * @param createdAt     UTC instant, millisecond precision
 * @param expiresAt     UTC instant after which receivers drop the message and cached copies
 * @param audience      explicit recipients
 */
public record EnvelopeHeader(
        int minorVersion,
        IdentityKey author,
        ObjectId objectId,
        long epoch,
        long sequence,
        long revision,
        Instant createdAt,
        Instant expiresAt,
        Audience audience
) {
    public EnvelopeHeader {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(audience, "audience");
        if (minorVersion < 0 || minorVersion > 0xFFFF) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Minor version out of range.");
        }
        if (epoch < 1 || epoch > 0xFFFF_FFFFL) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Epoch must be 1..2^32-1.");
        }
        if (sequence < 1) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Sequence must be 1..2^63-1.");
        }
        if (revision < 1 || revision > 0xFFFF_FFFFL) {
            throw new ProtocolException(RejectionReason.MALFORMED, "Revision must be 1..2^32-1.");
        }
        long created = ProtocolTime.toWireMillis(createdAt, "createdAt");
        long expires = ProtocolTime.toWireMillis(expiresAt, "expiresAt");
        if (epoch > WriterEpoch.maxAt(createdAt)) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, "Epoch is later than the envelope's createdAt.");
        }
        if (expires <= created) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, "expiresAt must be after createdAt.");
        }
        if (expires - created > ProtocolVersion.MAX_LIFETIME_MILLIS) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, "Envelope lifetime exceeds the protocol maximum.");
        }
        if (audience.includes(author.id())) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "The author must not be listed as a recipient.");
        }
    }
}

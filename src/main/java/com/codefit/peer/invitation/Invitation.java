package com.codefit.peer.invitation;

import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.transport.PeerAddress;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A signed, expiring, bounded invitation (ADR-0001 §4, protocol context distinct from an envelope): the
 * identity key, the current transport-key binding, 1-4 usable IP-literal addresses, a one-time nonce,
 * and an expiry. Decoding one (see {@code InvitationCodec}) only ever produces this value — it never by
 * itself registers a contact or dials anything. A hostname is never accepted here: every address is
 * already a validated {@link PeerAddress} IP literal, so there is no DNS lookup implied anywhere in the
 * invitation life cycle.
 */
public record Invitation(IdentityKey identityKey, IdentityKey transportKey, Instant bindingValidFrom,
                          Instant bindingValidUntil, List<PeerAddress> addresses, byte[] nonce,
                          Instant issuedAt, Instant expiresAt) {
    public static final int NONCE_LENGTH = 16;
    public static final int MIN_ADDRESSES = 1;
    public static final int MAX_ADDRESSES = 4;
    /** Generous but bounded: an invitation is meant to be acted on soon, not held indefinitely. */
    public static final long MAX_LIFETIME_MILLIS = 30L * 24 * 60 * 60 * 1000;

    public Invitation {
        Objects.requireNonNull(identityKey, "identityKey");
        Objects.requireNonNull(transportKey, "transportKey");
        Objects.requireNonNull(bindingValidFrom, "bindingValidFrom");
        Objects.requireNonNull(bindingValidUntil, "bindingValidUntil");
        Objects.requireNonNull(addresses, "addresses");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (transportKey.equals(identityKey)) {
            throw new InvitationException(InvitationRejectionReason.TRANSPORT_KEY_EQUALS_IDENTITY_KEY,
                    "The transport key must differ from the identity key.");
        }
        if (!bindingValidUntil.isAfter(bindingValidFrom)) {
            throw new InvitationException(InvitationRejectionReason.INVALID_TIMESTAMPS, "bindingValidUntil must be after bindingValidFrom.");
        }
        if (addresses.isEmpty() || addresses.size() > MAX_ADDRESSES) {
            throw new InvitationException(InvitationRejectionReason.INVALID_ADDRESS,
                    "Invitation must carry " + MIN_ADDRESSES + ".." + MAX_ADDRESSES + " addresses, got " + addresses.size());
        }
        addresses = List.copyOf(addresses);
        if (nonce.length != NONCE_LENGTH) {
            throw new InvitationException(InvitationRejectionReason.MALFORMED, "Nonce must be exactly " + NONCE_LENGTH + " bytes.");
        }
        nonce = nonce.clone();
        if (!expiresAt.isAfter(issuedAt)) {
            throw new InvitationException(InvitationRejectionReason.INVALID_TIMESTAMPS, "expiresAt must be after issuedAt.");
        }
        if (expiresAt.toEpochMilli() - issuedAt.toEpochMilli() > MAX_LIFETIME_MILLIS) {
            throw new InvitationException(InvitationRejectionReason.INVALID_TIMESTAMPS,
                    "Invitation lifetime exceeds " + MAX_LIFETIME_MILLIS + "ms.");
        }
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isNotYetValid(Instant now) {
        return now.isBefore(issuedAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Invitation that
                && identityKey.equals(that.identityKey)
                && transportKey.equals(that.transportKey)
                && bindingValidFrom.equals(that.bindingValidFrom)
                && bindingValidUntil.equals(that.bindingValidUntil)
                && addresses.equals(that.addresses)
                && Objects.deepEquals(nonce, that.nonce)
                && issuedAt.equals(that.issuedAt)
                && expiresAt.equals(that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(identityKey, transportKey, bindingValidFrom, bindingValidUntil, addresses,
                java.util.Arrays.hashCode(nonce), issuedAt, expiresAt);
    }
}

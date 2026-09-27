package com.codefit.repository;

import java.time.Instant;
import java.util.Arrays;

/**
 * The raw persisted row behind #181's single local identity, including its still-encrypted private
 * key material. This is an internal repository&lt;-&gt;service carrier, never returned by a public
 * service method and never logged: {@code com.codefit.peer.identity.LocalIdentitySummary} is the
 * shape callers outside {@code IdentityService} ever see.
 */
public record PeerIdentityRow(byte[] identityPublicKey, byte[] privateKeyCiphertext, byte[] privateKeySalt,
                               int privateKeyIterations, byte[] privateKeyNonce, int keyFormatVersion,
                               long highestKnownEpoch, long currentWriterEpoch, boolean sharingPaused,
                               Instant createdAt, Instant lastRestoredAt) {

    public PeerIdentityRow {
        identityPublicKey = identityPublicKey.clone();
        privateKeyCiphertext = privateKeyCiphertext.clone();
        privateKeySalt = privateKeySalt.clone();
        privateKeyNonce = privateKeyNonce.clone();
    }

    @Override
    public byte[] identityPublicKey() {
        return identityPublicKey.clone();
    }

    @Override
    public byte[] privateKeyCiphertext() {
        return privateKeyCiphertext.clone();
    }

    @Override
    public byte[] privateKeySalt() {
        return privateKeySalt.clone();
    }

    @Override
    public byte[] privateKeyNonce() {
        return privateKeyNonce.clone();
    }

    @Override
    public String toString() {
        // Never render key bytes, ciphertext included: the point of this override is that even a
        // debugger's toString-on-hover or an accidental log line cannot leak them.
        return "PeerIdentityRow[highestKnownEpoch=" + highestKnownEpoch + ", currentWriterEpoch=" + currentWriterEpoch
                + ", sharingPaused=" + sharingPaused + ", createdAt=" + createdAt + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PeerIdentityRow that
                && privateKeyIterations == that.privateKeyIterations
                && keyFormatVersion == that.keyFormatVersion
                && highestKnownEpoch == that.highestKnownEpoch
                && currentWriterEpoch == that.currentWriterEpoch
                && sharingPaused == that.sharingPaused
                && Arrays.equals(identityPublicKey, that.identityPublicKey)
                && Arrays.equals(privateKeyCiphertext, that.privateKeyCiphertext)
                && Arrays.equals(privateKeySalt, that.privateKeySalt)
                && Arrays.equals(privateKeyNonce, that.privateKeyNonce)
                && java.util.Objects.equals(createdAt, that.createdAt)
                && java.util.Objects.equals(lastRestoredAt, that.lastRestoredAt);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(privateKeyIterations, keyFormatVersion, highestKnownEpoch,
                currentWriterEpoch, sharingPaused, createdAt, lastRestoredAt);
    }
}

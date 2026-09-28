package com.codefit.repository;

import java.time.Instant;

/**
 * The raw persisted row behind #182's single local transport key, including its still-encrypted private
 * key material. Internal repository&lt;-&gt;service carrier only, mirroring {@link PeerIdentityRow}'s own
 * contract: never returned by a public service method and never logged.
 */
public record TransportIdentityRow(byte[] transportPublicKey, byte[] privateKeyCiphertext, byte[] privateKeySalt,
                                    int privateKeyIterations, byte[] privateKeyNonce,
                                    Instant bindingValidFrom, Instant bindingValidUntil, Instant createdAt) {

    public TransportIdentityRow {
        transportPublicKey = transportPublicKey.clone();
        privateKeyCiphertext = privateKeyCiphertext.clone();
        privateKeySalt = privateKeySalt.clone();
        privateKeyNonce = privateKeyNonce.clone();
    }

    @Override
    public byte[] transportPublicKey() {
        return transportPublicKey.clone();
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
        // Never render key bytes/ciphertext, matching PeerIdentityRow's own guard.
        return "TransportIdentityRow[bindingValidFrom=" + bindingValidFrom + ", bindingValidUntil=" + bindingValidUntil
                + ", createdAt=" + createdAt + "]";
    }
}

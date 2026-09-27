package com.codefit.peer.identity.crypto;

import java.util.Arrays;
import java.util.Objects;

/**
 * The decoded contents of an identity backup file: everything needed to restore signing capability
 * and resume writer-epoch bookkeeping on another device or after data loss. {@code identityPublicKey}
 * and {@code lastKnownEpoch} travel outside the passphrase-sealed payload deliberately: a restore must
 * be able to read the epoch to compute the next writer session (docs/p2p/protocol-v1.md §10.1) even
 * before the user has typed a passphrase, and the public key is not secret.
 */
public record IdentityBackupPayload(
        int formatVersion,
        long createdAtEpochMillis,
        long lastKnownEpoch,
        byte[] identityPublicKey,
        EncryptedSecret encryptedPkcs8PrivateKey) {

    public IdentityBackupPayload {
        identityPublicKey = Objects.requireNonNull(identityPublicKey, "identityPublicKey").clone();
        Objects.requireNonNull(encryptedPkcs8PrivateKey, "encryptedPkcs8PrivateKey");
        if (identityPublicKey.length != KeyPairs.PUBLIC_KEY_LENGTH) {
            throw new IllegalArgumentException("identityPublicKey must be " + KeyPairs.PUBLIC_KEY_LENGTH + " bytes.");
        }
    }

    @Override
    public byte[] identityPublicKey() {
        return identityPublicKey.clone();
    }

    /**
     * The plaintext header bytes bound as AES/GCM associated data (see {@link PassphraseCipher}),
     * so tampering with the version, timestamps, or declared public key — all of which travel outside
     * the encrypted private key — still fails authentication instead of silently restoring under a
     * mismatched public key.
     */
    public byte[] headerAssociatedData() {
        return headerAssociatedData(formatVersion, createdAtEpochMillis, lastKnownEpoch, identityPublicKey);
    }

    public static byte[] headerAssociatedData(int formatVersion, long createdAtEpochMillis, long lastKnownEpoch,
                                               byte[] identityPublicKey) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1 + 8 + 8 + KeyPairs.PUBLIC_KEY_LENGTH);
        buffer.put((byte) formatVersion).putLong(createdAtEpochMillis).putLong(lastKnownEpoch).put(identityPublicKey);
        return buffer.array();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IdentityBackupPayload that
                && formatVersion == that.formatVersion
                && createdAtEpochMillis == that.createdAtEpochMillis
                && lastKnownEpoch == that.lastKnownEpoch
                && Arrays.equals(identityPublicKey, that.identityPublicKey)
                && encryptedPkcs8PrivateKey.equals(that.encryptedPkcs8PrivateKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(formatVersion, createdAtEpochMillis, lastKnownEpoch,
                Arrays.hashCode(identityPublicKey), encryptedPkcs8PrivateKey);
    }
}

package com.codefit.peer.identity;

import com.codefit.peer.protocol.IdentityKey;

import java.time.Instant;
import java.util.Objects;

/**
 * A signed statement that {@code newKey} succeeds {@code oldKey} for the same person, produced only
 * when the old private key is still available at rotation time (ADR-0001 §1: "old/new signed
 * continuity when the old key is available; otherwise require peers to explicitly re-pair"). Any
 * holder of both public keys can verify {@code continuitySignature} against {@code oldKey}. Handing
 * this to existing contacts so they can adopt the new key without re-pairing is a transport concern
 * for #182/#184; #181's job is only to produce and persist it.
 *
 * <p>{@code signingBytes} is deliberately simple and unambiguous (fixed ASCII context tag, then both
 * raw 32-byte keys, then the instant as epoch millis, big-endian) rather than reusing protocol v1's
 * envelope signing bytes: this is not a protocol v1 envelope, and giving it its own context string
 * keeps its signature from ever being confused with one (the same separation ADR-0001 already applies
 * between envelope signatures and invitation signatures).
 */
public record KeyContinuityRecord(IdentityKey oldKey, IdentityKey newKey, Instant rotatedAt,
                                   byte[] continuitySignature) {

    private static final byte[] CONTEXT = "CodeFit-Identity-Key-Continuity-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public KeyContinuityRecord {
        Objects.requireNonNull(oldKey, "oldKey");
        Objects.requireNonNull(newKey, "newKey");
        Objects.requireNonNull(rotatedAt, "rotatedAt");
        continuitySignature = Objects.requireNonNull(continuitySignature, "continuitySignature").clone();
    }

    @Override
    public byte[] continuitySignature() {
        return continuitySignature.clone();
    }

    public static byte[] signingBytes(IdentityKey oldKey, IdentityKey newKey, Instant rotatedAt) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(CONTEXT.length + 32 + 32 + 8);
        buffer.put(CONTEXT).put(oldKey.bytes()).put(newKey.bytes()).putLong(rotatedAt.toEpochMilli());
        return buffer.array();
    }

    public boolean verify() {
        return com.codefit.peer.identity.crypto.KeyPairs.verify(
                com.codefit.peer.identity.crypto.KeyPairs.publicKeyFromRaw(oldKey.bytes()),
                signingBytes(oldKey, newKey, rotatedAt), continuitySignature);
    }
}

package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.SignedEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds and caches, per (writer epoch, recipient), the one signed {@code IDENTITY_BINDING} envelope
 * this device sends that recipient at handshake time. Reusing the exact same signed bytes across many
 * reconnects to the same contact within one writer session is deliberate: the receiver's
 * {@code EnvelopeAcceptancePolicy} then reports every reconnect after the first as the idempotent
 * {@code DUPLICATE} verdict (protocol §10), never {@code STALE_REVISION} or {@code FORKED} — resending
 * it is exactly as safe as never resending it. A new epoch (a new writer session, e.g. after restart)
 * always gets a fresh cache and fresh sequence numbers, matching protocol §10.1.
 */
final class LocalBindingEnvelopeCache {
    private final Map<Long, Map<IdentityId, SignedEnvelope>> byEpoch = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    SignedEnvelope get(UnlockedIdentity localIdentity, TransportIdentity localTransport, IdentityId recipientId,
                        long epoch, Instant now) {
        Map<IdentityId, SignedEnvelope> forEpoch = byEpoch.computeIfAbsent(epoch, e -> new ConcurrentHashMap<>());
        return forEpoch.computeIfAbsent(recipientId, recipient -> build(localIdentity, localTransport, recipient, epoch, now));
    }

    private SignedEnvelope build(UnlockedIdentity localIdentity, TransportIdentity localTransport, IdentityId recipientId,
                                  long epoch, Instant now) {
        long sequence = sequenceCounters.computeIfAbsent(epoch, e -> new AtomicLong(0)).incrementAndGet();
        ObjectId objectId = bindingObjectId(localIdentity.publicKey().id());
        IdentityBinding binding = localTransport.toBinding();
        Instant createdAt = Instant.ofEpochMilli(now.toEpochMilli());
        Instant expiresAt = localTransport.validUntil();
        EnvelopeHeader header = new EnvelopeHeader(0, localIdentity.publicKey(), objectId, epoch, sequence, 1,
                createdAt, expiresAt, Audience.direct(List.of(recipientId)));
        Envelope envelope = new Envelope(header, binding);
        byte[] signature = localIdentity.sign(envelope.signingBytes());
        return new SignedEnvelope(envelope, signature);
    }

    private static ObjectId bindingObjectId(IdentityId authorId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("CodeFit-Transport-Identity-Binding-v1\0".getBytes(StandardCharsets.US_ASCII));
            digest.update(authorId.bytes());
            byte[] hash = digest.digest();
            return new ObjectId(Arrays.copyOf(hash, ObjectId.LENGTH));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform.", e);
        }
    }
}

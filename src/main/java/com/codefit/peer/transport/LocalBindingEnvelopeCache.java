package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
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
 *
 * <p><strong>Rotation.</strong> A binding for a <em>new</em> transport key is a new version of the same
 * binding object, and a receiver that already accepted the old one (same author, same object id, same
 * epoch) would reject a re-use of its revision as {@code STALE_REVISION} and a re-use of its sequence
 * number as {@code FORKED}. So the cached envelope is rebuilt whenever the local transport key changes,
 * its {@code revision} is the binding's {@code validFrom} in whole epoch seconds (strictly increasing
 * across rotations because {@code TransportKeyService} never mints a key with a non-increasing
 * {@code validFrom}, and stable across restarts without any extra persisted counter), and the sequence
 * counter simply keeps counting within the epoch.
 */
final class LocalBindingEnvelopeCache {
    private final Map<Long, Map<IdentityId, SignedEnvelope>> byEpoch = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> sequenceCounters = new ConcurrentHashMap<>();

    SignedEnvelope get(UnlockedIdentity localIdentity, TransportIdentity localTransport, IdentityId recipientId,
                        long epoch, Instant now) {
        Map<IdentityId, SignedEnvelope> forEpoch = byEpoch.computeIfAbsent(epoch, e -> new ConcurrentHashMap<>());
        IdentityKey currentKey = localTransport.publicKey();
        return forEpoch.compute(recipientId, (recipient, cached) -> {
            if (cached != null && cached.body() instanceof IdentityBinding binding && binding.transportKey().equals(currentKey)) {
                return cached;
            }
            return build(localIdentity, localTransport, recipient, epoch, now);
        });
    }

    /** Binding revision for {@code transportValidFrom}: whole epoch seconds, within the header's 1..2^32-1 range. */
    static long revisionFor(Instant transportValidFrom) {
        return Math.min(Math.max(transportValidFrom.getEpochSecond(), 1L), 0xFFFF_FFFFL);
    }

    private SignedEnvelope build(UnlockedIdentity localIdentity, TransportIdentity localTransport, IdentityId recipientId,
                                  long epoch, Instant now) {
        long sequence = sequenceCounters.computeIfAbsent(epoch, e -> new AtomicLong(0)).incrementAndGet();
        ObjectId objectId = bindingObjectId(localIdentity.publicKey().id());
        IdentityBinding binding = localTransport.toBinding();
        Instant createdAt = Instant.ofEpochMilli(now.toEpochMilli());
        Instant expiresAt = localTransport.validUntil();
        EnvelopeHeader header = new EnvelopeHeader(0, localIdentity.publicKey(), objectId, epoch, sequence, revisionFor(localTransport.validFrom()),
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

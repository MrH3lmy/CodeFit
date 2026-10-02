package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared fixtures for the transport tests that need two or more independent peers with their own identity
 * key and (rotatable) transport key, and an in-memory stand-in for the contact store. Validity windows are
 * anchored to the real clock because the TLS layer checks certificate validity against it.
 */
public final class TransportTestSupport {
    private TransportTestSupport() {
    }

    /** One device: a fixed identity key and a transport key that can be replaced (rotated) over time. */
    public static final class TestPeer {
        public final UnlockedIdentity identity;

        private TestPeer(UnlockedIdentity identity) {
            this.identity = identity;
        }

        public static TestPeer create() {
            KeyPair identityKeyPair = KeyPairs.generate();
            return new TestPeer(new UnlockedIdentity(
                    new IdentityKey(KeyPairs.rawPublicKey(identityKeyPair.getPublic())), identityKeyPair.getPrivate()));
        }

        public IdentityId id() {
            return identity.publicKey().id();
        }

        /** A fresh transport key whose binding is valid from {@code validFrom} for 90 days (and already valid now). */
        public TransportKeyMaterial newTransportKey(Instant validFrom) {
            return new TransportKeyMaterial(KeyPairs.generate(), validFrom, validFrom.plus(Duration.ofDays(90)));
        }
    }

    public static IdentityKey keyOf(TransportKeyMaterial material) {
        return new IdentityKey(KeyPairs.rawPublicKey(material.keyPair().getPublic()));
    }

    public static PinnedBinding pinOf(TransportKeyMaterial material) {
        return new PinnedBinding(keyOf(material), Instant.ofEpochMilli(material.validFrom().toEpochMilli()));
    }

    public static Instant hoursAgo(long hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    public static RetryPolicy singleAttempt() {
        return new RetryPolicy(1, Duration.ofMillis(50), Duration.ofMillis(100), 0.1);
    }

    /** An in-memory contact store with the same forward-only pin rule the real {@code ContactService} applies. */
    public static final class FakeContacts implements KnownContactLookup {
        public record Observation(IdentityId remote, IdentityBinding binding) {
        }

        private final Map<IdentityId, Status> statuses = new ConcurrentHashMap<>();
        private final Map<IdentityId, PinnedBinding> pins = new ConcurrentHashMap<>();
        public final List<Observation> verified = new CopyOnWriteArrayList<>();

        public FakeContacts pair(TestPeer peer, PinnedBinding pin) {
            return pair(peer.id(), pin);
        }

        public FakeContacts pair(IdentityId id, PinnedBinding pin) {
            statuses.put(id, Status.PAIRED);
            if (pin != null) {
                pins.put(id, pin);
            }
            return this;
        }

        public PinnedBinding pinFor(IdentityId id) {
            return pins.get(id);
        }

        public PinnedBinding pinFor(TestPeer peer) {
            return pins.get(peer.id());
        }

        public VerifiedBindingListener sink() {
            return (remote, binding) -> {
                verified.add(new Observation(remote, binding));
                PinnedBinding held = pins.get(remote);
                if (held == null || held.transportKey().equals(binding.transportKey())
                        || binding.validFrom().isAfter(held.validFrom())) {
                    pins.put(remote, new PinnedBinding(binding.transportKey(), binding.validFrom()));
                }
            };
        }

        public List<Observation> observations() {
            return new ArrayList<>(verified);
        }

        @Override
        public Status statusOf(IdentityId identityId) {
            return statuses.getOrDefault(identityId, Status.UNKNOWN);
        }

        @Override
        public Optional<PinnedBinding> pinnedBindingOf(IdentityId identityId) {
            return Optional.ofNullable(pins.get(identityId));
        }

        @Override
        public Optional<IdentityId> pairedContactPinnedTo(IdentityKey transportKey) {
            return pins.entrySet().stream()
                    .filter(entry -> entry.getValue().transportKey().equals(transportKey))
                    .map(Map.Entry::getKey)
                    .filter(id -> statusOf(id) == Status.PAIRED)
                    .findFirst();
        }
    }
}

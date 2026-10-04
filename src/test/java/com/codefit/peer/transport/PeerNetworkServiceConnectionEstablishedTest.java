package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link ConnectionEstablishedListener}'s own stated ordering invariant - "by the time this
 * fires, {@code activeConnection(remoteIdentityId)} already returns exactly this connection" - holds
 * for both inbound and outbound connections, and that a reconnect's later notification is never
 * observed out of order relative to an earlier one for the same identity. This is deliberately a
 * transport-layer-only test (two real {@link PeerNetworkService} instances, no database, no {@code
 * NetworkingService}): it exists to pin down the one subtlety discovered while wiring application-
 * lifetime sync orchestration onto this class - that {@link VerifiedBindingListener}'s own inbound
 * firing happens <em>before</em> tracking, which is wrong for this purpose (see both classes' javadoc).
 */
class PeerNetworkServiceConnectionEstablishedTest {

    private record Peer(UnlockedIdentity identity, TransportKeyMaterial material) {
        static Peer create() {
            KeyPair identityKeyPair = KeyPairs.generate();
            UnlockedIdentity identity = new UnlockedIdentity(
                    new IdentityKey(KeyPairs.rawPublicKey(identityKeyPair.getPublic())), identityKeyPair.getPrivate());
            KeyPair transportKeyPair = KeyPairs.generate();
            Instant now = Instant.now();
            TransportKeyMaterial material = new TransportKeyMaterial(transportKeyPair, now.minusSeconds(60), now.plusSeconds(3600 * 24 * 90));
            return new Peer(identity, material);
        }
    }

    private record Observation(IdentityId remoteIdentityId, PeerConnection connection, boolean alreadyTrackedAsThisConnection) {
    }

    @Test
    @Timeout(30)
    void outboundEstablishmentSeesTheConnectionAlreadyTrackedAsItself() throws Exception {
        Peer alice = Peer.create();
        Peer bob = Peer.create();
        List<Observation> aliceObservations = new CopyOnWriteArrayList<>();

        PeerNetworkService bobService = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { });
        PeerNetworkService[] aliceServiceHolder = new PeerNetworkService[1];
        PeerNetworkService aliceService = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { },
                (identity, binding) -> { }, (remoteIdentityId, connection) -> {
                    boolean alreadyTracked = aliceServiceHolder[0].activeConnection(remoteIdentityId)
                            .map(current -> current == connection).orElse(false);
                    aliceObservations.add(new Observation(remoteIdentityId, connection, alreadyTracked));
                });
        aliceServiceHolder[0] = aliceService;
        try {
            bobService.enable(bob.identity(), bob.material(), 1L, 0);
            aliceService.enable(alice.identity(), alice.material(), 1L, 0);
            int bobPort = bobService.listeningPort().orElseThrow();

            DialOutcome outcome = aliceService.connect(bob.identity().publicKey().id(), new PeerAddress("127.0.0.1", bobPort),
                    bobService.currentTransportPublicKey().orElseThrow(), RetryPolicy.standard(), new AtomicBoolean(false))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated(), "alice should connect to bob: " + outcome.result());

            assertEquals(1, aliceObservations.size(), "the outbound listener must fire exactly once");
            Observation observed = aliceObservations.get(0);
            assertEquals(bob.identity().publicKey().id(), observed.remoteIdentityId());
            assertSame(outcome.connection(), observed.connection(), "the listener must receive the actual connection object, not a copy");
            assertTrue(observed.alreadyTrackedAsThisConnection(),
                    "activeConnection() must already return this exact connection from inside the callback");
        } finally {
            aliceService.disable();
            bobService.disable();
        }
    }

    @Test
    @Timeout(30)
    void inboundEstablishmentSeesTheConnectionAlreadyTrackedAsItself() throws Exception {
        // This is the critical ordering case: on the accept side, naively reusing
        // VerifiedBindingListener (which fires BEFORE tracking - see PeerListener#handle) would make
        // activeConnection() return empty from inside the callback. This test fails under that bug and
        // passes with the actual fix (notifying from inside trackInboundConnection's own compute()).
        Peer alice = Peer.create();
        Peer bob = Peer.create();
        List<Observation> bobObservations = new CopyOnWriteArrayList<>();

        PeerNetworkService[] bobServiceHolder = new PeerNetworkService[1];
        PeerNetworkService bobService = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { },
                (identity, binding) -> { }, (remoteIdentityId, connection) -> {
                    boolean alreadyTracked = bobServiceHolder[0].activeConnection(remoteIdentityId)
                            .map(current -> current == connection).orElse(false);
                    bobObservations.add(new Observation(remoteIdentityId, connection, alreadyTracked));
                });
        bobServiceHolder[0] = bobService;
        PeerNetworkService aliceService = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { });
        try {
            bobService.enable(bob.identity(), bob.material(), 1L, 0);
            aliceService.enable(alice.identity(), alice.material(), 1L, 0);
            int bobPort = bobService.listeningPort().orElseThrow();

            DialOutcome outcome = aliceService.connect(bob.identity().publicKey().id(), new PeerAddress("127.0.0.1", bobPort),
                    bobService.currentTransportPublicKey().orElseThrow(), RetryPolicy.standard(), new AtomicBoolean(false))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated(), "alice should connect to bob: " + outcome.result());

            waitUntil(() -> bobObservations.size() >= 1, "bob's inbound establishment listener never fired");
            Observation observed = bobObservations.get(0);
            assertEquals(alice.identity().publicKey().id(), observed.remoteIdentityId());
            assertNotNull(observed.connection());
            assertTrue(observed.alreadyTrackedAsThisConnection(),
                    "activeConnection() must already return this exact connection from inside the INBOUND callback - "
                            + "this is exactly the ordering VerifiedBindingListener does not provide on the accept side");
        } finally {
            aliceService.disable();
            bobService.disable();
        }
    }

    @Test
    @Timeout(30)
    void aReconnectsNotificationIsNeverObservedBeforeTheEarlierOnesForTheSameIdentity() throws Exception {
        Peer alice = Peer.create();
        Peer bob = Peer.create();
        List<Observation> bobObservations = new CopyOnWriteArrayList<>();

        PeerNetworkService[] bobServiceHolder = new PeerNetworkService[1];
        PeerNetworkService bobService = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { },
                (identity, binding) -> { }, (remoteIdentityId, connection) -> {
                    boolean alreadyTracked = bobServiceHolder[0].activeConnection(remoteIdentityId)
                            .map(current -> current == connection).orElse(false);
                    bobObservations.add(new Observation(remoteIdentityId, connection, alreadyTracked));
                });
        bobServiceHolder[0] = bobService;
        PeerNetworkService aliceService = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { });
        try {
            bobService.enable(bob.identity(), bob.material(), 1L, 0);
            aliceService.enable(alice.identity(), alice.material(), 1L, 0);
            int bobPort = bobService.listeningPort().orElseThrow();
            PeerAddress address = new PeerAddress("127.0.0.1", bobPort);
            IdentityKey bobTransportKey = bobService.currentTransportPublicKey().orElseThrow();

            DialOutcome first = aliceService.connect(bob.identity().publicKey().id(), address, bobTransportKey,
                    RetryPolicy.standard(), new AtomicBoolean(false)).get(10, TimeUnit.SECONDS);
            assertTrue(first.result().authenticated(), "first dial should authenticate: " + first.result());
            waitUntil(() -> bobObservations.size() >= 1, "first inbound establishment never observed");

            DialOutcome second = aliceService.connect(bob.identity().publicKey().id(), address, bobTransportKey,
                    RetryPolicy.standard(), new AtomicBoolean(false)).get(10, TimeUnit.SECONDS);
            assertTrue(second.result().authenticated(), "reconnect dial should authenticate: " + second.result());
            waitUntil(() -> bobObservations.size() >= 2, "second (reconnect) inbound establishment never observed");

            assertEquals(2, bobObservations.size());
            Observation firstObservation = bobObservations.get(0);
            Observation secondObservation = bobObservations.get(1);
            assertNotSame(firstObservation.connection(), secondObservation.connection(),
                    "a reconnect must be a genuinely different connection object");
            assertTrue(firstObservation.alreadyTrackedAsThisConnection(), "first observation's own ordering invariant");
            assertTrue(secondObservation.alreadyTrackedAsThisConnection(),
                    "second observation must see itself (not the superseded first connection) as current");
            assertSame(bobService.activeConnection(alice.identity().publicKey().id()).orElseThrow(), secondObservation.connection(),
                    "after both notifications, the tracked connection must be the newer one");
        } finally {
            aliceService.disable();
            bobService.disable();
        }
    }

    @Test
    @Timeout(30)
    void twoAndThreeArgConstructorsStillWorkWithNoOpDefaults() throws Exception {
        Peer alice = Peer.create();
        Peer bob = Peer.create();

        // 2-arg: no VerifiedBindingListener, no ConnectionEstablishedListener.
        PeerNetworkService bobTwoArg = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { });
        // 3-arg: a real VerifiedBindingListener, still no ConnectionEstablishedListener.
        AtomicInteger bindingCalls = new AtomicInteger();
        PeerNetworkService aliceThreeArg = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { },
                (identity, binding) -> bindingCalls.incrementAndGet());
        try {
            bobTwoArg.enable(bob.identity(), bob.material(), 1L, 0);
            aliceThreeArg.enable(alice.identity(), alice.material(), 1L, 0);
            int bobPort = bobTwoArg.listeningPort().orElseThrow();

            DialOutcome outcome = aliceThreeArg.connect(bob.identity().publicKey().id(), new PeerAddress("127.0.0.1", bobPort),
                    bobTwoArg.currentTransportPublicKey().orElseThrow(), RetryPolicy.standard(), new AtomicBoolean(false))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated(), "both legacy constructor shapes must still fully work: " + outcome.result());
            assertEquals(1, bindingCalls.get(), "the 3-arg constructor's own VerifiedBindingListener must still be reached");
        } finally {
            aliceThreeArg.disable();
            bobTwoArg.disable();
        }
    }

    @Test
    @Timeout(10)
    void withLiveIdentityOnlyRunsTheActionWhileEnabled() throws Exception {
        Peer alice = Peer.create();
        PeerNetworkService service = new PeerNetworkService(id -> KnownContactLookup.Status.UNKNOWN, event -> { });

        AtomicReference<UnlockedIdentity> captured = new AtomicReference<>();
        boolean ranBeforeEnable = service.withLiveIdentity(captured::set);
        assertFalse(ranBeforeEnable, "withLiveIdentity must not run its action while disabled");
        assertEquals(null, captured.get());

        try {
            service.enable(alice.identity(), alice.material(), 1L, 0);
            boolean ranWhileEnabled = service.withLiveIdentity(captured::set);
            assertTrue(ranWhileEnabled, "withLiveIdentity must run its action while enabled");
            assertNotNull(captured.get());
            assertEquals(alice.identity().publicKey().id(), captured.get().publicKey().id());
        } finally {
            service.disable();
        }

        captured.set(null);
        boolean ranAfterDisable = service.withLiveIdentity(captured::set);
        assertFalse(ranAfterDisable, "withLiveIdentity must not run its action once disabled again");
        assertEquals(null, captured.get());
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String timeoutMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), timeoutMessage);
    }
}

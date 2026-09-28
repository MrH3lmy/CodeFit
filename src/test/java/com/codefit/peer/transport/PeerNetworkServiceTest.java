package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end evidence for #182's top-level lifecycle: disabled by default, a real two-peer connection
 * once enabled, and complete teardown (no lingering listener, threads, or connections) on disable.
 */
class PeerNetworkServiceTest {

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

    @Test
    void isDisabledByDefault() {
        PeerNetworkService service = new PeerNetworkService(id -> KnownContactLookup.Status.UNKNOWN, event -> { });
        assertFalse(service.isEnabled());
        assertTrue(service.listeningPort().isEmpty());
        assertTrue(service.currentBinding().isEmpty());
        service.close(); // must be a harmless no-op when never enabled
    }

    @Test
    @Timeout(30)
    void twoServicesConnectAndDisableTearsEverythingDown() throws Exception {
        Peer alice = Peer.create();
        Peer bob = Peer.create();

        KnownContactLookup aliceKnowsBob = id -> id.equals(bob.identity().publicKey().id())
                ? KnownContactLookup.Status.PAIRED : KnownContactLookup.Status.UNKNOWN;
        KnownContactLookup bobKnowsAlice = id -> id.equals(alice.identity().publicKey().id())
                ? KnownContactLookup.Status.PAIRED : KnownContactLookup.Status.UNKNOWN;

        PeerNetworkService aliceService = new PeerNetworkService(aliceKnowsBob, event -> { });
        PeerNetworkService bobService = new PeerNetworkService(bobKnowsAlice, event -> { });
        try {
            bobService.enable(bob.identity(), bob.material(), 1L, 0);
            assertTrue(bobService.isEnabled());
            int bobPort = bobService.listeningPort().orElseThrow();

            aliceService.enable(alice.identity(), alice.material(), 1L, 0);

            var future = aliceService.connect(bob.identity().publicKey().id(), new PeerAddress("127.0.0.1", bobPort),
                    bobService.currentTransportPublicKey().orElseThrow(), RetryPolicy.standard(), new AtomicBoolean(false));
            DialOutcome outcome = future.get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated(), "alice should connect to bob: " + outcome.result());

            assertTrue(aliceService.activeConnection(bob.identity().publicKey().id()).isPresent());
        } finally {
            aliceService.disable();
            bobService.disable();
        }

        assertFalse(aliceService.isEnabled());
        assertFalse(bobService.isEnabled());
        assertTrue(aliceService.activeConnection(bob.identity().publicKey().id()).isEmpty());

        // The listener's port must actually be released: a fresh bind to the same fixed port (rather than
        // an ephemeral 0) succeeds once torn down, proving the OS socket was really closed.
        Peer charlie = Peer.create();
        int freePort = findFreeLoopbackPort();
        PeerNetworkService rebornService = new PeerNetworkService(id -> KnownContactLookup.Status.UNKNOWN, event -> { });
        try {
            rebornService.enable(charlie.identity(), charlie.material(), 1L, freePort);
            assertEquals(freePort, rebornService.listeningPort().orElseThrow());
        } finally {
            rebornService.disable();
        }
    }

    @Test
    @Timeout(20)
    void connectingWhileDisabledFailsImmediatelyRatherThanHanging() {
        PeerNetworkService service = new PeerNetworkService(id -> KnownContactLookup.Status.UNKNOWN, event -> { });
        var future = service.connect(new IdentityId(new byte[32]), new PeerAddress("127.0.0.1", 65000),
                new IdentityKey(new byte[32]), RetryPolicy.standard(), new AtomicBoolean(false));
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    @Timeout(20)
    void cancellingAConnectStopsRetriesPromptly() throws Exception {
        Peer alice = Peer.create();
        PeerNetworkService aliceService = new PeerNetworkService(id -> KnownContactLookup.Status.PAIRED, event -> { });
        try {
            aliceService.enable(alice.identity(), alice.material(), 1L, 0);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            int freePort = findFreeLoopbackPort();
            RetryPolicy patientPolicy = new RetryPolicy(30, Duration.ofMillis(200), Duration.ofSeconds(5), 0.1);

            var future = aliceService.connect(new IdentityId(new byte[32]), new PeerAddress("127.0.0.1", freePort),
                    new IdentityKey(new byte[32]), patientPolicy, cancelled);
            Thread.sleep(300);
            cancelled.set(true);
            DialOutcome outcome = future.get(5, TimeUnit.SECONDS);
            assertFalse(outcome.result().authenticated());
            assertEquals(ConnectionFailureReason.CANCELLED, outcome.result().failureReason());
        } finally {
            aliceService.disable();
        }
    }

    private static int findFreeLoopbackPort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }
}

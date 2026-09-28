package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end evidence for #182's bounded, cancellable, cleanly-shutdown transport: a real listener on a
 * loopback ephemeral port accepting a real dialed connection, a connection-count bound being enforced,
 * clean listener shutdown, and dialer retry/backoff/cancellation against an address nothing is
 * listening on.
 */
class PeerListenerAndDialerTest {

    private record Peer(UnlockedIdentity identity, TransportIdentity transport) {
        static Peer create() {
            KeyPair identityKeyPair = KeyPairs.generate();
            UnlockedIdentity identity = new UnlockedIdentity(
                    new IdentityKey(KeyPairs.rawPublicKey(identityKeyPair.getPublic())), identityKeyPair.getPrivate());
            KeyPair transportKeyPair = KeyPairs.generate();
            Instant now = Instant.now();
            TransportIdentity transport = new TransportIdentity(transportKeyPair,
                    SelfSignedCertificateFactory.create(transportKeyPair, now.minus(1, ChronoUnit.HOURS), now.plus(90, ChronoUnit.DAYS)),
                    now.minus(1, ChronoUnit.HOURS), now.plus(90, ChronoUnit.DAYS));
            return new Peer(identity, transport);
        }

        PeerSession.Context context(KnownContactLookup lookup) {
            return new PeerSession.Context(identity, transport, 1L, new LocalBindingEnvelopeCache(),
                    new PeerSession.ReplayStates(), lookup);
        }
    }

    @Test
    @Timeout(30)
    void acceptsARealDialedConnectionAndEnforcesTheAuthenticatedConnectionBound() throws Exception {
        Peer bob = Peer.create();
        Peer alice = Peer.create();
        Peer charlie = Peer.create();
        KnownContactLookup bobKnowsAliceAndCharlie = id -> (id.equals(alice.identity().publicKey().id())
                || id.equals(charlie.identity().publicKey().id())) ? KnownContactLookup.Status.PAIRED : KnownContactLookup.Status.UNKNOWN;

        BlockingQueue<ConnectionEvent> events = new ArrayBlockingQueue<>(50);
        List<PeerConnection> accepted = new CopyOnWriteArrayList<>();

        try (PeerListener listener = new PeerListener(bob.transport(), bob.context(bobKnowsAliceAndCharlie), 0, 5,
                4, 4, 1, accepted::add, events::add)) {
            PeerAddress address = new PeerAddress("127.0.0.1", listener.localPort());

            DialOutcome aliceOutcome = PeerDialer.dialOnce(alice.transport(), alice.context(id -> KnownContactLookup.Status.PAIRED),
                    address, bob.identity().publicKey().id(), bob.transport().publicKey(), Instant.now());
            assertTrue(aliceOutcome.result().authenticated(), "alice should authenticate: " + aliceOutcome.result());
            assertNotNull(aliceOutcome.connection());
            waitForAcceptedCount(accepted, 1);

            try {
                // A second, otherwise-legitimate contact is turned away: the listener's authenticated-connection
                // bound is 1. Short timeouts: the listener closes the raw socket before any TLS bytes flow, so
                // the client should fail fast rather than wait out a full production-length handshake timeout.
                DialOutcome charlieOutcome = PeerDialer.dialOnce(charlie.transport(), charlie.context(id -> KnownContactLookup.Status.PAIRED),
                        address, bob.identity().publicKey().id(), bob.transport().publicKey(), Instant.now(), 2_000, 2_000);
                assertFalse(charlieOutcome.result().authenticated());
                assertNull(charlieOutcome.connection());

                ConnectionEvent limitEvent = pollUntil(events, e -> e.reason() == ConnectionFailureReason.CONNECTION_LIMIT_REACHED);
                assertNotNull(limitEvent, "expected a CONNECTION_LIMIT_REACHED event");

                // Freeing the one slot lets a new connection through.
                accepted.get(0).close();
                DialOutcome charlieRetry = PeerDialer.dialOnce(charlie.transport(), charlie.context(id -> KnownContactLookup.Status.PAIRED),
                        address, bob.identity().publicKey().id(), bob.transport().publicKey(), Instant.now());
                assertTrue(charlieRetry.result().authenticated(), "charlie should authenticate once a slot frees: " + charlieRetry.result());
                charlieRetry.connection().close();
            } finally {
                aliceOutcome.connection().close();
            }
        }
    }

    @Test
    @Timeout(15)
    void closeStopsTheListenerAndReleasesThePort() throws Exception {
        Peer bob = Peer.create();
        PeerListener listener = new PeerListener(bob.transport(), bob.context(id -> KnownContactLookup.Status.PAIRED), 0, 5,
                2, 2, 4, connection -> { }, event -> { });
        int port = listener.localPort();
        listener.close();

        Peer alice = Peer.create();
        DialOutcome outcome = PeerDialer.dialOnce(alice.transport(), alice.context(id -> KnownContactLookup.Status.PAIRED),
                new PeerAddress("127.0.0.1", port), bob.identity().publicKey().id(), bob.transport().publicKey(), Instant.now());
        assertFalse(outcome.result().authenticated());
        assertEquals(ConnectionFailureReason.CONNECTION_REFUSED, outcome.result().failureReason());
    }

    @Test
    @Timeout(20)
    void dialWithRetryStopsPromptlyOnCancellationAgainstAnUnreachableAddress() throws Exception {
        Peer alice = Peer.create();
        Peer bob = Peer.create();
        // Nothing listens on this loopback port.
        int freePort = findFreeLoopbackPort();
        PeerAddress unreachable = new PeerAddress("127.0.0.1", freePort);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        RetryPolicy policy = new RetryPolicy(20, Duration.ofMillis(200), Duration.ofSeconds(5), 0.1);

        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            cancelled.set(true);
        });
        canceller.start();

        long start = System.nanoTime();
        DialOutcome outcome = PeerDialer.dialWithRetry(alice.transport(), alice.context(id -> KnownContactLookup.Status.PAIRED),
                unreachable, bob.identity().publicKey().id(), bob.transport().publicKey(), policy, cancelled, event -> { });
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        canceller.join();

        assertFalse(outcome.result().authenticated());
        assertEquals(ConnectionFailureReason.CANCELLED, outcome.result().failureReason());
        assertTrue(elapsedMillis < 5_000, "cancellation should stop retries well before the 20-attempt cap: " + elapsedMillis + "ms");
    }

    @Test
    @Timeout(15)
    void dialWithRetryGivesUpAfterMaxAttemptsAndReportsUnreachable() {
        Peer alice = Peer.create();
        Peer bob = Peer.create();
        int freePort = findFreeLoopbackPort();
        RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(50), Duration.ofMillis(200), 0.1);

        DialOutcome outcome = PeerDialer.dialWithRetry(alice.transport(), alice.context(id -> KnownContactLookup.Status.PAIRED),
                new PeerAddress("127.0.0.1", freePort), bob.identity().publicKey().id(), bob.transport().publicKey(),
                policy, new AtomicBoolean(false), event -> { });

        assertFalse(outcome.result().authenticated());
        assertEquals(ConnectionFailureReason.CONNECTION_REFUSED, outcome.result().failureReason());
    }

    private static int findFreeLoopbackPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void waitForAcceptedCount(List<PeerConnection> accepted, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (accepted.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(expected, accepted.size());
    }

    private static ConnectionEvent pollUntil(BlockingQueue<ConnectionEvent> events, java.util.function.Predicate<ConnectionEvent> match)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            ConnectionEvent event = events.poll(200, TimeUnit.MILLISECONDS);
            if (event != null && match.test(event)) {
                return event;
            }
        }
        return null;
    }
}

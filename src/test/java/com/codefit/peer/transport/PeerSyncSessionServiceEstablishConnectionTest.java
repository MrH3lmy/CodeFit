package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.service.PeerSyncSessionService;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves {@code PeerSyncSessionService#establishConnection} satisfies {@link
 * ConnectionEstablishedListener}'s own contract ("must return quickly and must not perform blocking
 * I/O directly") and the staleness-recheck invariant a review of this PR's first version required:
 * dispatch must happen off the transport's own establishment thread, and a connection already (or
 * about to be) superseded by a reconnect must never send or start a receive loop that could clobber
 * the newer connection's own registration.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerSyncSessionServiceEstablishConnectionTest {

    @BeforeEach
    void resetTables() {
        PeerIdentityTestTables.resetAll();
    }

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

    private static DialOutcome dial(Peer dialer, PeerSession.Context dialerContext, Peer listener, PeerAddress address) {
        return PeerDialer.dialOnce(dialer.transport(), dialerContext,
                address, listener.identity().publicKey().id(), listener.transport().publicKey(), Instant.now());
    }

    private static void waitForAcceptedCount(java.util.List<PeerConnection> accepted, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (accepted.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(accepted.size() >= expected, "expected at least " + expected + " accepted connections, got " + accepted.size());
    }

    /** Same reflective read {@code PeerSyncSessionServiceReconnectRaceTest} already uses - the single,
     *  unambiguous source of truth for "which connection is currently registered for this identity". */
    private static PeerConnection registeredConnectionFor(PeerSyncSessionService service, IdentityId authorId) {
        try {
            Field mapField = PeerSyncSessionService.class.getDeclaredField("activeReceiveLoops");
            mapField.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) mapField.get(service);
            Object registration = map.get(authorId);
            if (registration == null) {
                return null;
            }
            Field connectionField = registration.getClass().getDeclaredField("connection");
            connectionField.setAccessible(true);
            return (PeerConnection) connectionField.get(registration);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String timeoutMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), timeoutMessage);
    }

    @Test
    @Timeout(30)
    void theDispatchingCallReturnsImmediatelyEvenWhileSendIsStillBlocked() throws Exception {
        Peer me = Peer.create();
        Peer contact = Peer.create();
        KnownContactLookup meKnowsContact = id -> KnownContactLookup.Status.PAIRED;

        java.util.List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        PeerSession.Context contactContext = contact.context(id -> KnownContactLookup.Status.PAIRED);
        try (PeerListener listener = new PeerListener(me.transport(), me.context(meKnowsContact), 0, 5,
                4, 4, 8, accepted::add, event -> { })) {
            PeerAddress address = new PeerAddress("127.0.0.1", listener.localPort());
            DialOutcome dialOutcome = dial(contact, contactContext, me, address);
            assertTrue(dialOutcome.result().authenticated(), "dial should authenticate: " + dialOutcome.result());
            waitForAcceptedCount(accepted, 1);
            PeerConnection connection = accepted.get(0);
            IdentityId myIdentityId = me.identity().publicKey().id();

            try (PeerSyncSessionService sessionService = new PeerSyncSessionService()) {
                CountDownLatch sendMayReturn = new CountDownLatch(1);
                AtomicBoolean sendCompleted = new AtomicBoolean(false);

                long before = System.currentTimeMillis();
                sessionService.establishConnection(connection, () -> true, () -> Optional.of(myIdentityId), () -> {
                    try {
                        assertTrue(sendMayReturn.await(10, TimeUnit.SECONDS), "test itself failed to release the send in time");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    sendCompleted.set(true);
                });
                long elapsedMillis = System.currentTimeMillis() - before;

                assertTrue(elapsedMillis < 500,
                        "establishConnection must return almost immediately regardless of how long the send takes; took " + elapsedMillis + "ms");
                assertFalse(sendCompleted.get(), "the send must still be genuinely blocked at this point, not already finished");

                sendMayReturn.countDown();
                waitUntil(sendCompleted::get, "the dispatched send never actually ran to completion");
                waitUntil(() -> registeredConnectionFor(sessionService, contact.identity().publicKey().id()) == connection,
                        "the receive loop must still start once the dispatched send completes");
            } finally {
                dialOutcome.connection().close();
            }
        }
    }

    @Test
    @Timeout(30)
    void aConnectionAlreadyStaleBeforeDispatchRunsNeverSendsOrStartsReceiving() throws Exception {
        Peer me = Peer.create();
        Peer contact = Peer.create();
        KnownContactLookup meKnowsContact = id -> KnownContactLookup.Status.PAIRED;

        java.util.List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        PeerSession.Context contactContext = contact.context(id -> KnownContactLookup.Status.PAIRED);
        try (PeerListener listener = new PeerListener(me.transport(), me.context(meKnowsContact), 0, 5,
                4, 4, 8, accepted::add, event -> { })) {
            PeerAddress address = new PeerAddress("127.0.0.1", listener.localPort());
            DialOutcome dialOutcome = dial(contact, contactContext, me, address);
            waitForAcceptedCount(accepted, 1);
            PeerConnection connection = accepted.get(0);
            IdentityId myIdentityId = me.identity().publicKey().id();

            try (PeerSyncSessionService sessionService = new PeerSyncSessionService()) {
                AtomicInteger sendCalls = new AtomicInteger();
                sessionService.establishConnection(connection, () -> false, () -> Optional.of(myIdentityId), sendCalls::incrementAndGet);

                // Bounded settle: there is no "it never ran" event to wait for, so this polls a fixed
                // window - matching this test file's other waits in spirit (short, generous, never relied
                // on alone to prove correctness: the real proof is the assertion below holding afterward).
                Thread.sleep(300);
                assertEquals(0, sendCalls.get(), "a connection already stale before the task even started must never be sent to");
                assertNull(registeredConnectionFor(sessionService, contact.identity().publicKey().id()),
                        "a connection already stale before the task even started must never get a receive loop");
            } finally {
                dialOutcome.connection().close();
            }
        }
    }

    @Test
    @Timeout(30)
    void aConnectionSupersededWhileItsSendWasRunningNeverStartsAReceiveLoopThatCouldClobberTheNewerOne() throws Exception {
        Peer me = Peer.create();
        Peer contact = Peer.create();
        KnownContactLookup meKnowsContact = id -> KnownContactLookup.Status.PAIRED;
        IdentityId contactId = contact.identity().publicKey().id();

        java.util.List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        PeerSession.Context contactContext = contact.context(id -> KnownContactLookup.Status.PAIRED);
        try (PeerListener listener = new PeerListener(me.transport(), me.context(meKnowsContact), 0, 5,
                4, 4, 8, accepted::add, event -> { })) {
            PeerAddress address = new PeerAddress("127.0.0.1", listener.localPort());

            DialOutcome dialA = dial(contact, contactContext, me, address);
            waitForAcceptedCount(accepted, 1);
            PeerConnection connectionA = accepted.get(0);

            DialOutcome dialB = dial(contact, contactContext, me, address);
            waitForAcceptedCount(accepted, 2);
            PeerConnection connectionB = accepted.get(1);

            IdentityId myIdentityId = me.identity().publicKey().id();

            // Mirrors production's own real stillCurrent supplier (NetworkingService.onConnectionEstablished:
            // peerNetworkService.activeConnection(id).map(c -> c == connection).orElse(false)) with a plain
            // reference this test controls directly, keeping the proof focused on PeerSyncSessionService's
            // own behavior rather than re-deriving PeerNetworkService's already separately-tested tracking.
            AtomicReference<PeerConnection> currentConnection = new AtomicReference<>(connectionA);

            try (PeerSyncSessionService sessionService = new PeerSyncSessionService()) {
                CountDownLatch aSendStarted = new CountDownLatch(1);
                CountDownLatch aSendMayReturn = new CountDownLatch(1);
                AtomicBoolean aSendRan = new AtomicBoolean(false);

                sessionService.establishConnection(connectionA, () -> currentConnection.get() == connectionA,
                        () -> Optional.of(myIdentityId), () -> {
                            aSendRan.set(true);
                            aSendStarted.countDown();
                            try {
                                assertTrue(aSendMayReturn.await(10, TimeUnit.SECONDS), "test itself failed to release A's send in time");
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });

                // A's first staleness check must already have passed (currentConnection was still A) by
                // the time its send actually started - proven directly, not assumed.
                assertTrue(aSendStarted.await(10, TimeUnit.SECONDS), "A's dispatched send never started");

                // Now B supersedes A, exactly like a real reconnect racing A's still-in-flight dispatch.
                currentConnection.set(connectionB);

                // Let A's send finish; its own post-send recheck must now see itself as stale.
                aSendMayReturn.countDown();

                // B's own establishment proceeds normally and must end up registered.
                sessionService.establishConnection(connectionB, () -> currentConnection.get() == connectionB,
                        () -> Optional.of(myIdentityId), () -> { });

                waitUntil(() -> registeredConnectionFor(sessionService, contactId) == connectionB,
                        "B's own establishment must still register its receive loop");

                // Give A's (already-superseded) task every chance to misbehave before asserting it did not.
                Thread.sleep(300);
                assertTrue(aSendRan.get(), "sanity: A's send genuinely ran before being superseded");
                assertSame(connectionB, registeredConnectionFor(sessionService, contactId),
                        "A's stale post-send receive-loop start must never have clobbered B's registration");
                // The real discriminator between "A correctly declined to register itself" and "A
                // wrongly registered, then got closed as a side effect of B's registration superseding
                // it": both end with B as the final registration either way (this executor is single-
                // threaded, so B's own establishConnection call always runs after A's finishes) - but
                // only the buggy path ever touches connectionA at all. A must still be genuinely open.
                assertTrue(connectionA.isOpen(),
                        "A must never have been registered (and so never closed as a side effect of B's registration) at all");
            } finally {
                dialA.connection().close();
                dialB.connection().close();
            }
        }
    }
}

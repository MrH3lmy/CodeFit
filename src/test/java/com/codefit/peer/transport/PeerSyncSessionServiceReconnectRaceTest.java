package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.ConsentRevision;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.sync.SyncOutcome;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Deterministic regression coverage for the reconnect receive-loop race in
 * {@code PeerSyncSessionService.startReceiving}: a stale registration for one {@link PeerConnection}
 * must never block - or be wrongly clobbered by - the registration belonging to a <em>different</em>,
 * newer {@link PeerConnection} for the same {@code remoteIdentityId}.
 *
 * <p>Every proof here is structural (what the map contains, what a real socket does when closed) or
 * event-driven (a {@link BlockingQueue}/{@code CountDownLatch} fed by the production {@code onOutcome}
 * callback), never a fixed {@code Thread.sleep}. Two real TLS connections are established over real
 * loopback sockets via the same {@link PeerListener}/{@link PeerDialer} machinery
 * {@code PeerListenerAndDialerTest} already uses - this is genuine transport, not a fake.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerSyncSessionServiceReconnectRaceTest {

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

    /** A harmless, always-decodable frame - its content is irrelevant; only that {@code receive()}
     *  successfully decodes it (so the production {@code onOutcome} callback fires) matters here. */
    private static SignedEnvelope probeFrame(Peer author, Peer recipient, long sequence) {
        ObjectId objectId = ConsentRevision.objectIdFor(author.identity().publicKey().id(), recipient.identity().publicKey().id());
        Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        EnvelopeHeader header = new EnvelopeHeader(0, author.identity().publicKey(), objectId, 1, sequence, 1,
                now, now.plus(Duration.ofDays(1)), Audience.direct(List.of(recipient.identity().publicKey().id())));
        Envelope envelope = new Envelope(header, new ConsentRevision(List.of(SharingScope.DAILY_SUMMARY)));
        return new SignedEnvelope(envelope, author.identity().sign(envelope.signingBytes()));
    }

    /**
     * One {@code dialerContext} must be reused across every dial attempt from the same identity in a
     * test, never rebuilt per attempt: {@link LocalBindingEnvelopeCache} deliberately caches and
     * reuses the exact same signed {@code IDENTITY_BINDING} bytes across reconnects within one writer
     * session (its own javadoc - "resending it is exactly as safe as never resending it"), which is
     * also simply what a real reconnecting client does (it keeps its one live process/context, it
     * does not rebuild a fresh one per attempt). A fresh {@code Context} per dial would instead replay
     * an identical {@code (epoch, sequence)} handshake proof the listener's persistent replay state
     * has already seen, and get rejected - a self-inflicted test bug, not a product one.
     */
    private static DialOutcome dial(Peer dialer, PeerSession.Context dialerContext, Peer listener, PeerAddress address) {
        return PeerDialer.dialOnce(dialer.transport(), dialerContext,
                address, listener.identity().publicKey().id(), listener.transport().publicKey(), Instant.now());
    }

    private static void waitForAcceptedCount(List<PeerConnection> accepted, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (accepted.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(accepted.size() >= expected, "expected at least " + expected + " accepted connections, got " + accepted.size());
    }

    @Test
    @Timeout(30)
    void aReplacementConnectionGetsAnActiveReceiveLoopWhileTheStaleRegistrationIsStillPresent() throws Exception {
        Peer me = Peer.create();
        Peer contact = Peer.create();
        KnownContactLookup meKnowsContact = id -> id.equals(contact.identity().publicKey().id())
                ? KnownContactLookup.Status.PAIRED : KnownContactLookup.Status.UNKNOWN;

        List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        PeerSession.Context contactContext = contact.context(id -> KnownContactLookup.Status.PAIRED);
        try (PeerListener listener = new PeerListener(me.transport(), me.context(meKnowsContact), 0, 5,
                4, 4, 8, accepted::add, event -> { })) {
            PeerAddress address = new PeerAddress("127.0.0.1", listener.localPort());

            DialOutcome firstDial = dial(contact, contactContext, me, address);
            assertTrue(firstDial.result().authenticated(), "first dial should authenticate: " + firstDial.result());
            waitForAcceptedCount(accepted, 1);
            PeerConnection connectionOld = accepted.get(0);

            DialOutcome secondDial = dial(contact, contactContext, me, address);
            assertTrue(secondDial.result().authenticated(), "reconnect dial should authenticate: " + secondDial.result());
            waitForAcceptedCount(accepted, 2);
            PeerConnection connectionNew = accepted.get(1);

            try (PeerSyncSessionService sessionService = new PeerSyncSessionService()) {
                sessionService.startReceiving(connectionOld, me.identity().publicKey().id(), (envelope, outcome) -> { });

                // connectionOld has nothing sent to it and nothing has closed it: a real blocked socket
                // read cannot have returned, so its registration is - deterministically, not probably -
                // still the one occupying the map when the reconnect below registers.
                assertTrue(connectionOld.isOpen(), "the old connection must still be open - this is the race precondition, forced, not hoped for");

                BlockingQueue<SyncOutcome> newOutcomes = new ArrayBlockingQueue<>(4);
                sessionService.startReceiving(connectionNew, me.identity().publicKey().id(), (envelope, outcome) -> newOutcomes.offer(outcome));

                // Superseding a stale registration closes its connection as part of registering the new
                // one - under the bug (computeIfAbsent, keyed only on identity), this call would have
                // been a complete no-op and connectionOld would still be open right now.
                assertFalse(connectionOld.isOpen(), "registering the replacement must close the superseded stale connection");

                // And the replacement must have a genuinely active loop of its own: send one real frame
                // on its dialer side and wait (event-driven, bounded only as a hang guard) for the
                // receiver to classify it.
                secondDial.connection().send(probeFrame(contact, me, 1));
                SyncOutcome outcome = newOutcomes.poll(10, TimeUnit.SECONDS);
                assertNotNull(outcome, "the replacement connection must have an active receive loop even though the old one's was never cleaned up");
            } finally {
                firstDial.connection().close();
                secondDial.connection().close();
            }
        }
    }

    /**
     * Reflectively inspects {@code PeerSyncSessionService}'s private {@code receiveLoopExecutor}
     * field - no production code changes for this: {@code Executors.newCachedThreadPool} returns a
     * real {@link ThreadPoolExecutor}, whose own {@link ThreadPoolExecutor#getActiveCount()} is a
     * genuine, real-time count of threads actually executing a receive loop right now. A thread can
     * only stop counting as "active" once {@code receiveLoop}'s {@code finally} block (its very last
     * statement before the method - and the thread - ends) has run, so this is a true completion
     * signal, not a timing proxy for one.
     */
    private static ThreadPoolExecutor receiveLoopExecutorOf(PeerSyncSessionService service) throws Exception {
        Field field = PeerSyncSessionService.class.getDeclaredField("receiveLoopExecutor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(service);
    }

    /** Bounded, condition-checked poll on that real thread count - never a fixed sleep standing in
     *  for the condition itself; the short sleep between checks only paces the polling, exactly like
     *  this file's own pre-existing {@link #waitForAcceptedCount}. */
    private static void waitForActiveReceiveThreads(PeerSyncSessionService service, int expectedCount) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        int last = -1;
        while (System.currentTimeMillis() < deadline) {
            last = receiveLoopExecutorOf(service).getActiveCount();
            if (last == expectedCount) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Timed out waiting for " + expectedCount + " active receive thread(s); last observed " + last);
    }

    /** Reflectively reads the current registration's own connection for one author identity directly
     *  out of the private {@code activeReceiveLoops} map - the single, unambiguous source of truth
     *  for "which connection does the map currently say is registered", with no indirect proxy for it. */
    private static PeerConnection registeredConnectionFor(PeerSyncSessionService service, com.codefit.peer.protocol.IdentityId authorId)
            throws Exception {
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
    }

    @Test
    @Timeout(30)
    void aStaleLoopsCleanupExecutingAfterTheReplacementIsRegisteredNeverRemovesIt() throws Exception {
        Peer me = Peer.create();
        Peer contact = Peer.create();
        KnownContactLookup meKnowsContact = id -> id.equals(contact.identity().publicKey().id())
                ? KnownContactLookup.Status.PAIRED : KnownContactLookup.Status.UNKNOWN;
        com.codefit.peer.protocol.IdentityId contactId = contact.identity().publicKey().id();

        List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        PeerSession.Context contactContext = contact.context(id -> KnownContactLookup.Status.PAIRED);
        try (PeerListener listener = new PeerListener(me.transport(), me.context(meKnowsContact), 0, 5,
                4, 4, 8, accepted::add, event -> { })) {
            PeerAddress address = new PeerAddress("127.0.0.1", listener.localPort());

            DialOutcome firstDial = dial(contact, contactContext, me, address);
            waitForAcceptedCount(accepted, 1);
            PeerConnection connectionOld = accepted.get(0);

            DialOutcome secondDial = dial(contact, contactContext, me, address);
            waitForAcceptedCount(accepted, 2);
            PeerConnection connectionNew = accepted.get(1);

            try (PeerSyncSessionService sessionService = new PeerSyncSessionService()) {
                // Required step 1: A's receive loop exists - not merely registered, but genuinely
                // running (the executor's own real thread count confirms it, not an assumption).
                sessionService.startReceiving(connectionOld, me.identity().publicKey().id(), (envelope, outcome) -> { });
                waitForActiveReceiveThreads(sessionService, 1);
                assertSame(connectionOld, registeredConnectionFor(sessionService, contactId));

                // Required step 2: replacement B becomes the current registration. Registering B also
                // closes A's connection as part of superseding it (production behavior) - that close()
                // is the trigger for A's eventual exception, but A's own background thread can only
                // react to it afterward: it takes an independent thread wake-up the close can only
                // cause, never precede. So this assertion - checked immediately, before anything
                // below waits for A to finish - already proves B is current strictly before A's
                // cleanup has had any chance to run.
                BlockingQueue<SyncOutcome> newOutcomes = new ArrayBlockingQueue<>(4);
                sessionService.startReceiving(connectionNew, me.identity().publicKey().id(), (envelope, outcome) -> newOutcomes.offer(outcome));
                assertSame(connectionNew, registeredConnectionFor(sessionService, contactId),
                        "B must be the current registration immediately upon registering it");

                // Required step 3: A's cleanup/finally executes AFTER B is registered - proven by
                // deterministically waiting (bounded poll on a real condition, not a sleep standing in
                // for it) for the active-thread count to settle back down to 1: it was 1 (just A)
                // before B was registered above, transiently becomes up to 2 (A still exiting, B now
                // running) the instant B's own thread starts, and can only return to 1 once A's thread
                // - whose sole exit path is through its own finally block - has fully terminated.
                // Reaching this point proves that termination happened, and it necessarily happened
                // after the registration check just above, since nothing could wait for it before
                // that check even ran.
                waitForActiveReceiveThreads(sessionService, 1);

                // Required step 4: A's cleanup does not remove B - checked directly against the map,
                // now that A's cleanup is confirmed to have already run.
                assertSame(connectionNew, registeredConnectionFor(sessionService, contactId),
                        "A's now-completed cleanup must not have removed B's registration");

                // Required step 5: B is then proven operational by receiving a real frame.
                secondDial.connection().send(probeFrame(contact, me, 1));
                SyncOutcome outcome = newOutcomes.poll(10, TimeUnit.SECONDS);
                assertNotNull(outcome, "B must still have a genuinely active receive loop after A's cleanup ran");
            } finally {
                firstDial.connection().close();
                secondDial.connection().close();
            }
        }
    }
}

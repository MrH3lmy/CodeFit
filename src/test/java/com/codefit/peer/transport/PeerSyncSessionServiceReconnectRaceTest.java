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

import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @Timeout(30)
    void aStaleLoopFinishingLaterNeverClobbersAFollowingNewerRegistration() throws Exception {
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
            waitForAcceptedCount(accepted, 1);
            PeerConnection connectionOld = accepted.get(0);

            DialOutcome secondDial = dial(contact, contactContext, me, address);
            waitForAcceptedCount(accepted, 2);
            PeerConnection connectionNew = accepted.get(1);

            DialOutcome thirdDial = dial(contact, contactContext, me, address);
            waitForAcceptedCount(accepted, 3);
            PeerConnection connectionThird = accepted.get(2);

            try (PeerSyncSessionService sessionService = new PeerSyncSessionService()) {
                sessionService.startReceiving(connectionOld, me.identity().publicKey().id(), (envelope, outcome) -> { });
                // Registering connectionNew supersedes (and closes) connectionOld. Its own receive
                // loop's cleanup for connectionOld may run on its own schedule, at any point from now
                // on, including strictly AFTER the next registration below - this is exactly the "old
                // loop terminates after the replacement was already registered" ordering, and nothing
                // here waits for or depends on exactly when that cleanup happens: the property under
                // test (a stale loop's own cleanup only ever removes ITS OWN connection's registration)
                // holds at every instant, which is why no synchronization on that cleanup is needed.
                sessionService.startReceiving(connectionNew, me.identity().publicKey().id(), (envelope, outcome) -> { });

                // A further reconnect must see connectionNew as the current registration and supersede
                // IT specifically. If the first (connectionOld) loop's eventual cleanup had instead
                // wrongly cleared whatever was currently registered - rather than only its own entry -
                // this registration would find nothing to supersede, and connectionNew would never be
                // closed by it.
                sessionService.startReceiving(connectionThird, me.identity().publicKey().id(), (envelope, outcome) -> { });

                assertFalse(connectionNew.isOpen(),
                        "the second connection's registration must still have been correctly current - and so "
                                + "correctly superseded - no matter when the first (already-superseded) connection's "
                                + "own receive loop happened to finish cleaning up after itself");
            } finally {
                firstDial.connection().close();
                secondDial.connection().close();
                thirdDial.connection().close();
            }
        }
    }
}

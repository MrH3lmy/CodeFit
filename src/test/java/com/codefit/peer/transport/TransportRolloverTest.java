package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.transport.TransportTestSupport.FakeContacts;
import com.codefit.peer.transport.TransportTestSupport.TestPeer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.codefit.peer.transport.TransportTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The transport-key rollover protocol (docs/p2p/transport-v1.md §9) over real TLS sockets: what happens when a
 * paired peer's key changes under a contact that still pins the old one, and - just as important - every way
 * a "replacement key" must be refused. The pin is never weakened: a different key is only ever adopted after
 * the peer proves, with a signature by its identity key, that the key is authorized, valid now, and strictly
 * newer than the pinned one.
 */
class TransportRolloverTest {
    private final List<PeerNetworkService> services = new ArrayList<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (PeerNetworkService service : services) {
            service.disable();
        }
        for (AutoCloseable closeable : closeables) {
            closeable.close();
        }
    }

    private PeerNetworkService start(TestPeer peer, TransportKeyMaterial material, long epoch, FakeContacts contacts) throws IOException {
        PeerNetworkService service = new PeerNetworkService(contacts, event -> { }, contacts.sink());
        service.enable(peer.identity, material, epoch, 0, ListenerBindAddress.loopbackOnly());
        services.add(service);
        return service;
    }

    private static DialOutcome connect(PeerNetworkService from, TestPeer to, int port, PinnedBinding pin) throws Exception {
        return from.connect(to.id(), new PeerAddress("127.0.0.1", port), pin, singleAttempt(), new AtomicBoolean(false))
                .get(20, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(60)
    void reconnectWithAnUnchangedKeyAuthenticatesEveryTimeAndKeepsThePin() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobKey = bob.newTransportKey(hoursAgo(4));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobKey));
        FakeContacts bobContacts = new FakeContacts().pair(alice, pinOf(aliceKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);
        PeerNetworkService bobService = start(bob, bobKey, 1L, bobContacts);
        int bobPort = bobService.listeningPort().orElseThrow();

        for (int i = 0; i < 3; i++) {
            DialOutcome outcome = connect(aliceService, bob, bobPort, aliceContacts.pinFor(bob));
            assertTrue(outcome.result().authenticated(), "reconnect #" + i + ": " + outcome.result());
            assertEquals(keyOf(bobKey), outcome.result().remoteBinding().transportKey());
        }
        assertEquals(pinOf(bobKey), aliceContacts.pinFor(bob), "an unchanged key never moves the pin");
        assertEquals(3, aliceContacts.observations().size(), "every authenticated dial surfaces the verified binding");
        assertEquals(pinOf(aliceKey), bobContacts.pinFor(alice));
        assertEquals(3, bobContacts.observations().size(), "the listener surfaces the caller's verified binding too");
    }

    @Test
    @Timeout(60)
    void aPeerThatRotatedItsKeyIsAdoptedThroughTheRolloverProofAndThePinMovesForward() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobOldKey));
        FakeContacts bobContacts = new FakeContacts().pair(alice, pinOf(aliceKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);
        PeerNetworkService bobService = start(bob, bobOldKey, 1L, bobContacts);
        int bobPort = bobService.listeningPort().orElseThrow();

        assertTrue(connect(aliceService, bob, bobPort, aliceContacts.pinFor(bob)).result().authenticated());
        aliceContacts.verified.clear();

        // Bob rotates while running: same port, new key.
        TransportKeyMaterial bobNewKey = bob.newTransportKey(hoursAgo(1));
        assertTrue(bobService.rekey(bobNewKey));
        assertEquals(bobPort, bobService.listeningPort().orElseThrow(), "rotation must not move the listener");
        assertEquals(keyOf(bobNewKey), bobService.currentTransportPublicKey().orElseThrow());

        // Alice still pins the old key: the pinned dial is refused at TLS, then the rollover proof succeeds.
        DialOutcome rolled = connect(aliceService, bob, bobPort, aliceContacts.pinFor(bob));
        assertTrue(rolled.result().authenticated(), "rollover should succeed: " + rolled.result());
        assertEquals(keyOf(bobNewKey), rolled.result().remoteBinding().transportKey());
        assertEquals(pinOf(bobNewKey), aliceContacts.pinFor(bob), "the pin moved forward to the proven new key");
        assertEquals(1, aliceContacts.observations().size());

        // And from now on the ordinary pinned dial works against the new key.
        DialOutcome plain = connect(aliceService, bob, bobPort, aliceContacts.pinFor(bob));
        assertTrue(plain.result().authenticated(), "plain pinned reconnect after rollover: " + plain.result());
        assertEquals(2, aliceContacts.observations().size());
    }

    @Test
    @Timeout(60)
    void aRotatedPeerDialingInIsAcceptedAndItsNewKeyIsPersistedByTheListener() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobOldKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);
        int alicePort = aliceService.listeningPort().orElseThrow();

        TransportKeyMaterial bobNewKey = bob.newTransportKey(hoursAgo(1));
        FakeContacts bobContacts = new FakeContacts().pair(alice, pinOf(aliceKey));
        PeerNetworkService bobService = start(bob, bobNewKey, 2L, bobContacts);

        // Alice's listener is structurally open: Bob's new key is adopted because Bob's binding for it is
        // identity-signed and strictly newer than the old pin, not because TLS accepted it.
        DialOutcome outcome = connect(bobService, alice, alicePort, bobContacts.pinFor(alice));
        assertTrue(outcome.result().authenticated(), outcome.result().toString());
        assertEquals(pinOf(bobNewKey), aliceContacts.pinFor(bob), "the listening side moved Bob's pin forward");
    }

    @Test
    @Timeout(60)
    void aReplacementKeyWhoseBindingIsOlderThanThePinIsRefusedAndThePinIsUntouched() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobPinnedKey = bob.newTransportKey(hoursAgo(2));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobPinnedKey));
        FakeContacts bobContacts = new FakeContacts().pair(alice, pinOf(aliceKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);
        // Bob comes back with a genuine, identity-signed, still-valid but OLDER key (e.g. a restored backup).
        TransportKeyMaterial bobStaleKey = bob.newTransportKey(hoursAgo(9));
        PeerNetworkService bobService = start(bob, bobStaleKey, 1L, bobContacts);

        DialOutcome outcome = connect(aliceService, bob, bobService.listeningPort().orElseThrow(), aliceContacts.pinFor(bob));
        assertFalse(outcome.result().authenticated());
        assertEquals(ConnectionFailureReason.STALE_BINDING, outcome.result().failureReason(), outcome.result().detail());
        assertEquals(pinOf(bobPinnedKey), aliceContacts.pinFor(bob));
        assertTrue(aliceContacts.observations().isEmpty(), "a refused rollover must never surface a binding to persist");
    }

    @Test
    @Timeout(60)
    void aCapturedPreRotationBindingCannotRollTheListenerBackToTheSupersededKey() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        TransportKeyMaterial bobNewKey = bob.newTransportKey(hoursAgo(1));
        // Alice already learned Bob's NEW key.
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobNewKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);
        int alicePort = aliceService.listeningPort().orElseThrow();

        // Someone holding Bob's superseded key (and Bob's old, still-unexpired binding for it) tries to connect.
        FakeContacts attackerView = new FakeContacts().pair(alice, pinOf(aliceKey));
        PeerNetworkService oldKeyHolder = start(bob, bobOldKey, 1L, attackerView);
        DialOutcome outcome = connect(oldKeyHolder, alice, alicePort, attackerView.pinFor(alice));

        assertFalse(outcome.result().authenticated(), "the old key must not be accepted again once a newer one is pinned");
        assertTrue(oldKeyHolder.activeConnection(alice.id()).isEmpty());
        assertEquals(pinOf(bobNewKey), aliceContacts.pinFor(bob));
        assertTrue(aliceContacts.observations().isEmpty());
    }

    @Test
    @Timeout(60)
    void aStrangerAtTheContactsAddressCannotTakeOverThePinAndLearnsNothingFromTheDialer() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TestPeer mallory = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobKey = bob.newTransportKey(hoursAgo(4));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);

        // Mallory sits where Bob used to be, presents her own key, and answers a rollover request with a
        // binding authored by HER identity (addressed to Alice, to be as convincing as possible).
        TransportKeyMaterial malloryKey = mallory.newTransportKey(hoursAgo(1));
        try (RogueServer rogue = new RogueServer(TransportIdentity.from(malloryKey),
                new LocalBindingEnvelopeCache().get(mallory.identity, TransportIdentity.from(malloryKey), alice.id(), 1L, Instant.now()))) {
            DialOutcome outcome = connect(aliceService, bob, rogue.port(), aliceContacts.pinFor(bob));

            assertFalse(outcome.result().authenticated());
            assertEquals(ConnectionFailureReason.IDENTITY_MISMATCH, outcome.result().failureReason(), outcome.result().detail());
            assertTrue(rogue.sawRolloverHello(), "the dialer asked for a rollover proof first");
            assertEquals(0, rogue.bytesReceivedAfterItsProof(),
                    "the dialer must disclose nothing (no identity, no binding) to a peer that has not proven itself");
        }
        assertEquals(pinOf(bobKey), aliceContacts.pinFor(bob));
        assertTrue(aliceContacts.observations().isEmpty());
    }

    @Test
    @Timeout(60)
    void aCapturedGenuineBindingPresentedOverSomeoneElsesTlsKeyIsRefused() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TestPeer mallory = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobKey = bob.newTransportKey(hoursAgo(4));
        TransportKeyMaterial bobNewerKey = bob.newTransportKey(hoursAgo(1));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);

        // Mallory replays a genuine Bob-signed, newer binding (to Alice) but can only present her own TLS key.
        SignedEnvelope genuineBobBinding = new LocalBindingEnvelopeCache()
                .get(bob.identity, TransportIdentity.from(bobNewerKey), alice.id(), 1L, Instant.now());
        TransportKeyMaterial malloryKey = mallory.newTransportKey(hoursAgo(1));
        try (RogueServer rogue = new RogueServer(TransportIdentity.from(malloryKey), genuineBobBinding)) {
            DialOutcome outcome = connect(aliceService, bob, rogue.port(), aliceContacts.pinFor(bob));

            assertFalse(outcome.result().authenticated());
            assertEquals(ConnectionFailureReason.BINDING_KEY_MISMATCH, outcome.result().failureReason(), outcome.result().detail());
            assertEquals(0, rogue.bytesReceivedAfterItsProof());
        }
        assertEquals(pinOf(bobKey), aliceContacts.pinFor(bob));
    }

    @Test
    @Timeout(60)
    void ifBothPeersRotatedTheRolloverIsRefusedRatherThanDisclosingFirstAndTheOldPinsStay() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceOldKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobOldKey));
        FakeContacts bobContacts = new FakeContacts().pair(alice, pinOf(aliceOldKey));
        // Both devices now run brand-new keys that neither contact store has seen.
        PeerNetworkService aliceService = start(alice, alice.newTransportKey(hoursAgo(2)), 1L, aliceContacts);
        PeerNetworkService bobService = start(bob, bob.newTransportKey(hoursAgo(1)), 1L, bobContacts);

        DialOutcome outcome = connect(aliceService, bob, bobService.listeningPort().orElseThrow(), aliceContacts.pinFor(bob));
        assertFalse(outcome.result().authenticated());
        assertEquals(ConnectionFailureReason.ROLLOVER_REFUSED, outcome.result().failureReason(), outcome.result().detail());
        assertEquals(pinOf(bobOldKey), aliceContacts.pinFor(bob), "documented limit: this case needs a fresh invitation");
        assertTrue(bobContacts.observations().isEmpty());
    }

    @Test
    @Timeout(60)
    void rekeyingTheListenerKeepsEstablishedConnectionsAndOnlyChangesWhatNewConnectionsAreShown() throws Exception {
        TestPeer alice = TestPeer.create();
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial aliceKey = alice.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        FakeContacts aliceContacts = new FakeContacts().pair(bob, pinOf(bobOldKey));
        FakeContacts bobContacts = new FakeContacts().pair(alice, pinOf(aliceKey));
        PeerNetworkService aliceService = start(alice, aliceKey, 1L, aliceContacts);
        PeerNetworkService bobService = start(bob, bobOldKey, 1L, bobContacts);
        int bobPort = bobService.listeningPort().orElseThrow();

        DialOutcome before = connect(aliceService, bob, bobPort, aliceContacts.pinFor(bob));
        assertTrue(before.result().authenticated());

        TransportKeyMaterial bobNewKey = bob.newTransportKey(hoursAgo(1));
        assertTrue(bobService.rekey(bobNewKey));
        assertFalse(bobService.rekey(bobNewKey), "re-applying the same key is a no-op");
        assertTrue(before.connection().isOpen(), "an already-authenticated connection survives the rotation");

        // A strictly pinned dial against the old key now fails at TLS, with no rollover permitted...
        DialOutcome pinnedToOld = aliceService.connect(bob.id(), new PeerAddress("127.0.0.1", bobPort), keyOf(bobOldKey),
                singleAttempt(), new AtomicBoolean(false)).get(20, TimeUnit.SECONDS);
        assertFalse(pinnedToOld.result().authenticated());
        assertEquals(ConnectionFailureReason.WRONG_PIN, pinnedToOld.result().failureReason());
        // ...and one pinned to the new key succeeds, with the binding the listener sends matching that key.
        DialOutcome pinnedToNew = aliceService.connect(bob.id(), new PeerAddress("127.0.0.1", bobPort), keyOf(bobNewKey),
                singleAttempt(), new AtomicBoolean(false)).get(20, TimeUnit.SECONDS);
        assertTrue(pinnedToNew.result().authenticated(), pinnedToNew.result().toString());
        assertEquals(keyOf(bobNewKey), pinnedToNew.result().remoteBinding().transportKey());
    }

    /**
     * A hostile listener: presents the given TLS key, answers a rollover hello with the given binding envelope,
     * then counts every byte the dialer sends afterwards (there must be none).
     */
    private final class RogueServer implements AutoCloseable {
        private final ServerSocket server;
        private final TransportIdentity transport;
        private final SignedEnvelope proof;
        private final AtomicInteger bytesAfterProof = new AtomicInteger();
        private final AtomicBoolean rolloverHello = new AtomicBoolean();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final CompletableFuture<Void> handledRolloverConnection = new CompletableFuture<>();

        RogueServer(TransportIdentity transport, SignedEnvelope proof) throws IOException {
            this.transport = transport;
            this.proof = proof;
            this.server = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
            closeables.add(this);
            Thread thread = new Thread(this::serve, "rogue-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        boolean sawRolloverHello() {
            return rolloverHello.get();
        }

        /** Waits for the rollover connection to finish, then reports how many bytes arrived after the proof was sent. */
        int bytesReceivedAfterItsProof() throws Exception {
            handledRolloverConnection.get(15, TimeUnit.SECONDS);
            return bytesAfterProof.get();
        }

        /**
         * Accepts connections until closed: the dialer's first, pinned dial is refused at TLS (that is the
         * point), and only its follow-up rollover dial gets as far as a hello.
         */
        private void serve() {
            try {
                while (!server.isClosed()) {
                    try (Socket raw = server.accept()) {
                        handle(raw);
                    } catch (IOException connectionFailed) {
                        // a refused TLS handshake (the pinned dial) or a closed server socket: keep serving
                    }
                }
            } finally {
                done.complete(null);
            }
        }

        private void handle(Socket raw) throws IOException {
            SSLSocket tls = (SSLSocket) TlsContexts.forListener(transport).getSocketFactory()
                    .createSocket(raw, null, raw.getPort(), true);
            tls.setUseClientMode(false);
            TlsContexts.hardenSocket(tls, true);
            tls.setSoTimeout(3_000);
            tls.startHandshake();
            HandshakeIo.writeHello(tls.getOutputStream(), List.of(1));
            HandshakeIo.Hello hello = HandshakeIo.readHelloFrame(tls.getInputStream());
            rolloverHello.set(hello.rolloverRequested());
            HandshakeIo.writeEnvelopeFrame(tls.getOutputStream(), proof);
            InputStream in = tls.getInputStream();
            try {
                int read;
                byte[] buffer = new byte[256];
                while ((read = in.read(buffer)) > 0) {
                    bytesAfterProof.addAndGet(read);
                }
            } catch (IOException closedOrTimedOut) {
                // the dialer hanging up (or silence) is the expected outcome
            }
            handledRolloverConnection.complete(null);
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

}

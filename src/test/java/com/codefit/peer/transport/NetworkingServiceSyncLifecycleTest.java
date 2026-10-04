package com.codefit.peer.transport;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.ConsentRevision;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.repository.PeerSyncConsentRepository;
import com.codefit.service.ContactService;
import com.codefit.service.IdentityService;
import com.codefit.service.NetworkingService;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.security.KeyPair;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves PR A's whole point end to end, one layer below the UI: a real {@link NetworkingService}
 * ("me", real {@code IdentityService}/{@code ContactService} against an isolated test database)
 * automatically starts receiving and automatically sends its outbox on every authenticated
 * connection - inbound or outbound - with no call anywhere in this file to {@code
 * PeerSyncSessionService.startReceiving}/{@code sendOutboxTo}, no {@code PeerController}, and no
 * JavaFX. "The other side" in every test is a raw, package-local transport fixture (own {@link
 * UnlockedIdentity}/{@link TransportIdentity}, no database of its own) built the same way {@code
 * PeerNetworkServiceTest}/{@code PeerSyncSessionServiceReconnectRaceTest} already do - never a second
 * {@code NetworkingService}, since {@code DatabaseConfig} is a single JVM-wide static and two
 * independent identity/contact stacks cannot coexist in one test JVM (the same constraint those
 * tests, and the real two-process demos, already document).
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class NetworkingServiceSyncLifecycleTest {

    private static final char[] PASSPHRASE = "networking-sync-lifecycle-test".toCharArray();

    @BeforeEach
    void resetTables() {
        PeerIdentityTestTables.resetAll();
    }

    /** A raw, database-less stand-in for "the other device" - never a second NetworkingService. */
    private record FakePeer(UnlockedIdentity identity, TransportIdentity transport) {
        static FakePeer create() {
            KeyPair identityKeyPair = KeyPairs.generate();
            UnlockedIdentity identity = new UnlockedIdentity(
                    new IdentityKey(KeyPairs.rawPublicKey(identityKeyPair.getPublic())), identityKeyPair.getPrivate());
            KeyPair transportKeyPair = KeyPairs.generate();
            Instant now = Instant.now();
            TransportIdentity transport = new TransportIdentity(transportKeyPair,
                    SelfSignedCertificateFactory.create(transportKeyPair, now.minus(1, ChronoUnit.HOURS), now.plus(90, ChronoUnit.DAYS)),
                    now.minus(1, ChronoUnit.HOURS), now.plus(90, ChronoUnit.DAYS));
            return new FakePeer(identity, transport);
        }

        PeerSession.Context context(KnownContactLookup lookup) {
            return new PeerSession.Context(identity, transport, 1L, new LocalBindingEnvelopeCache(),
                    new PeerSession.ReplayStates(), lookup);
        }
    }

    private static Instant nowMillis() {
        return Instant.ofEpochMilli(Instant.now().toEpochMilli());
    }

    /** Registers {@code fakePeer} as a real PAIRED contact of "me" - the same prerequisite a real
     *  pairing flow (#197) would have already satisfied - so the transport's own KnownContactLookup
     *  accepts a handshake with it, either direction. */
    private static long pairWithFakePeer(ContactService contactService, FakePeer fakePeer) {
        Contact pending = contactService.registerPendingContact(fakePeer.identity().publicKey(), "fake-peer", nowMillis());
        Contact paired = contactService.acceptInvitation(pending.id(), nowMillis());
        contactService.recordObservedTransportBinding(paired.id(), fakePeer.transport().publicKey(),
                fakePeer.transport().validFrom(), fakePeer.transport().validUntil(), nowMillis());
        return paired.id();
    }

    /**
     * One harmless, real, signed {@code ConsentRevision} authored by {@code author} for {@code
     * recipient}. {@code sequenceAndRevision} must strictly increase across calls for the same
     * (author, recipient) pair - {@code ConsentRevision.objectIdFor} depends only on that pair, never
     * on content, so a second call reusing an earlier value would collide with the first under the
     * real, correct {@code STALE_REVISION} replay check, not prove anything about a receive loop.
     */
    private static SignedEnvelope consentFrom(UnlockedIdentity author, IdentityId recipientId, List<SharingScope> scopes,
                                               long sequenceAndRevision) {
        ObjectId objectId = ConsentRevision.objectIdFor(author.publicKey().id(), recipientId);
        Instant now = nowMillis();
        EnvelopeHeader header = new EnvelopeHeader(0, author.publicKey(), objectId, 1, sequenceAndRevision, sequenceAndRevision,
                now, now.plus(Duration.ofDays(1)), Audience.direct(List.of(recipientId)));
        Envelope envelope = new Envelope(header, new ConsentRevision(scopes));
        return new SignedEnvelope(envelope, author.sign(envelope.signingBytes()));
    }

    private static Optional<List<SharingScope>> scopesMeHasReceivedFrom(IdentityId authorId) throws Exception {
        try (Connection connection = DatabaseConfig.getConnection()) {
            return new PeerSyncConsentRepository().scopesFor(connection, authorId);
        }
    }

    /**
     * {@link PeerConnection#receive()} is a plain blocking socket read with no timeout of its own, and
     * - unlike {@code Thread.sleep} in {@link #waitUntil} - does not respond to {@code
     * Thread.interrupt()}, so a JUnit {@code @Timeout} annotation cannot actually bound a direct,
     * on-the-test-thread call to it (confirmed directly: temporarily reverting the production fix
     * under test made such a call hang indefinitely rather than timing out). Every call in this file
     * therefore runs {@code receive()} on its own daemon thread and bounds only the *wait* for its
     * result - exactly the pattern {@code PeerSyncSessionService}'s own background receive loop plus a
     * {@code BlockingQueue} already establishes elsewhere in this codebase's own tests.
     */
    private static SignedEnvelope receiveWithin(PeerConnection connection, Duration timeout) throws Exception {
        BlockingQueue<Object> result = new ArrayBlockingQueue<>(1);
        Thread receiver = new Thread(() -> {
            try {
                result.offer(connection.receive());
            } catch (Exception e) {
                result.offer(e);
            }
        }, "test-bounded-receive");
        receiver.setDaemon(true);
        receiver.start();
        Object outcome = result.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        assertNotNull(outcome, "receive() never returned anything within " + timeout);
        if (outcome instanceof Exception exception) {
            throw exception;
        }
        return (SignedEnvelope) outcome;
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String timeoutMessage) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), timeoutMessage);
    }

    private static long aliveDaemonThreadsNamed(String prefix) {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(t -> t.getName().startsWith(prefix))
                .count();
    }

    @Test
    @Timeout(30)
    void outboundConnectionAutomaticallyStartsReceivingAndSendsTheOutboxWithoutExplicitCalls() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100); // writer epoch must strictly advance past createIdentity's own initial one

        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        long contactId = pairWithFakePeer(contactService, fakePeer);
        // A non-empty permission grant is what makes the automatic outbox batch non-empty (the
        // consent envelope is only built when a permission row exists at all) - this is the one real,
        // observable signal that an automatic send pass actually happened, without needing any of
        // PR B's progress-snapshot machinery.
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), 1, null, false), nowMillis());

        List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        KnownContactLookup fakePeerKnowsMe = id -> KnownContactLookup.Status.PAIRED;
        NetworkingService networkingService = new NetworkingService();
        try (PeerListener fakePeerListener = new PeerListener(fakePeer.transport(), fakePeer.context(fakePeerKnowsMe), 0, 5,
                4, 4, 8, accepted::add, event -> { })) {
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());

            DialOutcome outcome = networkingService.connectToContact(contactId,
                    new PeerAddress("127.0.0.1", fakePeerListener.localPort()), RetryPolicy.standard(), new AtomicBoolean(false))
                    .get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated(), "me should connect to the fake peer: " + outcome.result());
            waitUntil(() -> accepted.size() >= 1, "the fake peer never accepted the connection");

            PeerConnection fakePeerSideConnection = accepted.get(0);
            SignedEnvelope received = receiveWithin(fakePeerSideConnection, Duration.ofSeconds(10));
            assertTrue(received.body() instanceof ConsentRevision, "me's automatic send must include a real signed consent envelope");
            assertEquals(identityService.currentIdentity().orElseThrow().id(), received.header().author().id(),
                    "the consent envelope must be authored by me, not the fake peer");
        } finally {
            networkingService.close();
        }
    }

    @Test
    @Timeout(30)
    void inboundConnectionAutomaticallyStartsReceiving() throws Exception {
        // This is the scenario the investigation flagged: on the accept side, a naive reuse of
        // VerifiedBindingListener's ordering would make this receive loop start too late (or never, if
        // it tried to rediscover the connection) relative to when the fake peer's very first frame
        // arrives. This test proves the whole, real NetworkingService-level pipeline, not just
        // PeerNetworkService's own tracking order (already covered in
        // PeerNetworkServiceConnectionEstablishedTest).
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100);

        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        pairWithFakePeer(contactService, fakePeer);
        IdentityId myIdentityId = identityService.currentIdentity().orElseThrow().id();

        NetworkingService networkingService = new NetworkingService();
        try {
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            int myPort = networkingService.listeningPort().orElseThrow();
            IdentityKey myTransportKey = networkingService.liveTransportKey().orElseThrow();

            PeerSession.Context fakePeerContext = fakePeer.context(id -> KnownContactLookup.Status.PAIRED);
            DialOutcome outcome = PeerDialer.dialOnce(fakePeer.transport(), fakePeerContext,
                    new PeerAddress("127.0.0.1", myPort), myIdentityId, myTransportKey, Instant.now());
            assertTrue(outcome.result().authenticated(), "the fake peer should connect to me: " + outcome.result());

            try {
                outcome.connection().send(consentFrom(fakePeer.identity(), myIdentityId, List.of(SharingScope.DAILY_SUMMARY), 1));

                waitUntil(() -> {
                    try {
                        return scopesMeHasReceivedFrom(fakePeer.identity().publicKey().id())
                                .map(scopes -> scopes.contains(SharingScope.DAILY_SUMMARY)).orElse(false);
                    } catch (Exception e) {
                        return false;
                    }
                }, "me never automatically received and ingested the fake peer's consent envelope");
            } finally {
                outcome.connection().close();
            }
        } finally {
            networkingService.close();
        }
    }

    @Test
    @Timeout(30)
    void aReconnectReplacesTheReceiveLoopAutomaticallyAndTheNewConnectionSyncsSuccessfully() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100);

        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        pairWithFakePeer(contactService, fakePeer);
        IdentityId myIdentityId = identityService.currentIdentity().orElseThrow().id();

        NetworkingService networkingService = new NetworkingService();
        try {
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            int myPort = networkingService.listeningPort().orElseThrow();
            IdentityKey myTransportKey = networkingService.liveTransportKey().orElseThrow();
            PeerAddress address = new PeerAddress("127.0.0.1", myPort);

            // One reusable dialer-side context, exactly like a real reconnecting client keeps - see
            // PeerSyncSessionServiceReconnectRaceTest's own helper javadoc for why a fresh Context per
            // attempt would self-inflict a replay rejection instead of proving anything real.
            PeerSession.Context fakePeerContext = fakePeer.context(id -> KnownContactLookup.Status.PAIRED);

            DialOutcome firstDial = PeerDialer.dialOnce(fakePeer.transport(), fakePeerContext, address, myIdentityId, myTransportKey, Instant.now());
            assertTrue(firstDial.result().authenticated(), "first dial should authenticate: " + firstDial.result());
            firstDial.connection().send(consentFrom(fakePeer.identity(), myIdentityId, List.of(SharingScope.DAILY_SUMMARY), 1));
            waitUntil(() -> {
                try {
                    return scopesMeHasReceivedFrom(fakePeer.identity().publicKey().id()).isPresent();
                } catch (Exception e) {
                    return false;
                }
            }, "me never ingested anything from the first connection");

            DialOutcome secondDial = PeerDialer.dialOnce(fakePeer.transport(), fakePeerContext, address, myIdentityId, myTransportKey, Instant.now());
            assertTrue(secondDial.result().authenticated(), "reconnect dial should authenticate: " + secondDial.result());
            try {
                // No explicit startReceiving call anywhere - the orchestration under test must have
                // already replaced the receive loop for a brand-new frame on the brand-new connection.
                secondDial.connection().send(consentFrom(fakePeer.identity(), myIdentityId, List.of(SharingScope.WEEKLY_SUMMARY), 2));
                waitUntil(() -> {
                    try {
                        return scopesMeHasReceivedFrom(fakePeer.identity().publicKey().id())
                                .map(scopes -> scopes.contains(SharingScope.WEEKLY_SUMMARY)).orElse(false);
                    } catch (Exception e) {
                        return false;
                    }
                }, "me never ingested the second connection's frame - the automatic receive loop was not correctly replaced");
            } finally {
                firstDial.connection().close();
                secondDial.connection().close();
            }
        } finally {
            networkingService.close();
        }
    }

    @Test
    @Timeout(30)
    void disablingNetworkingClosesTransportAndLeavesNoStaleSyncThreadsAlive() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100);

        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        long contactId = pairWithFakePeer(contactService, fakePeer);
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), 1, null, false), nowMillis());

        List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        NetworkingService networkingService = new NetworkingService();
        PeerConnection fakePeerSideConnection;
        try (PeerListener fakePeerListener = new PeerListener(fakePeer.transport(), fakePeer.context(id -> KnownContactLookup.Status.PAIRED),
                0, 5, 4, 4, 8, accepted::add, event -> { })) {
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            DialOutcome outcome = networkingService.connectToContact(contactId, new PeerAddress("127.0.0.1", fakePeerListener.localPort()),
                    RetryPolicy.standard(), new AtomicBoolean(false)).get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated());
            waitUntil(() -> accepted.size() >= 1, "fake peer never accepted");
            fakePeerSideConnection = accepted.get(0);
            assertNotNull(receiveWithin(fakePeerSideConnection, Duration.ofSeconds(10)), "sanity: sync must have actually run once before disabling");

            assertTrue(aliveDaemonThreadsNamed("codefit-peer-sync-") > 0, "sanity: sync threads must exist while enabled");

            networkingService.disableNetworking();

            assertFalse(networkingService.isNetworkingEnabled());
            waitUntil(() -> !outcome.connection().isOpen(), "disableNetworking() must close my side of the connection");
            waitUntil(() -> aliveDaemonThreadsNamed("codefit-peer-sync-") == 0,
                    "disableNetworking() must leave no live codefit-peer-sync-* thread behind");
        } finally {
            networkingService.close();
        }
    }

    @Test
    @Timeout(45)
    void disableThenReenableGenuinelyExchangesASyncFrameAgain() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100);

        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        pairWithFakePeer(contactService, fakePeer);
        IdentityId myIdentityId = identityService.currentIdentity().orElseThrow().id();

        NetworkingService networkingService = new NetworkingService();
        try {
            // --- First lifecycle ---
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            int firstPort = networkingService.listeningPort().orElseThrow();
            IdentityKey firstTransportKey = networkingService.liveTransportKey().orElseThrow();
            PeerSession.Context fakePeerContext = fakePeer.context(id -> KnownContactLookup.Status.PAIRED);

            DialOutcome firstDial = PeerDialer.dialOnce(fakePeer.transport(), fakePeerContext,
                    new PeerAddress("127.0.0.1", firstPort), myIdentityId, firstTransportKey, Instant.now());
            assertTrue(firstDial.result().authenticated(), "first lifecycle's dial should authenticate: " + firstDial.result());
            firstDial.connection().send(consentFrom(fakePeer.identity(), myIdentityId, List.of(SharingScope.DAILY_SUMMARY), 1));
            waitUntil(() -> {
                try {
                    return scopesMeHasReceivedFrom(fakePeer.identity().publicKey().id()).isPresent();
                } catch (Exception e) {
                    return false;
                }
            }, "first lifecycle never genuinely synced");
            firstDial.connection().close();

            // --- Disable ---
            networkingService.disableNetworking();
            assertFalse(networkingService.isNetworkingEnabled());
            waitUntil(() -> aliveDaemonThreadsNamed("codefit-peer-sync-") == 0, "first lifecycle's sync threads must be gone before re-enabling");

            // A fresh writer session's epoch must strictly advance past the previous one (second
            // granularity, protocol §10.1) - the same real constraint TwoProcessPeerDemo's own
            // comment documents for exactly this reason.
            Thread.sleep(1_100);

            // --- Second lifecycle: must independently, genuinely re-sync, not merely "enable() returned". ---
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            int secondPort = networkingService.listeningPort().orElseThrow();
            IdentityKey secondTransportKey = networkingService.liveTransportKey().orElseThrow();

            DialOutcome secondDial = PeerDialer.dialOnce(fakePeer.transport(), fakePeerContext,
                    new PeerAddress("127.0.0.1", secondPort), myIdentityId, secondTransportKey, Instant.now());
            assertTrue(secondDial.result().authenticated(), "second lifecycle's dial should authenticate: " + secondDial.result());
            try {
                secondDial.connection().send(consentFrom(fakePeer.identity(), myIdentityId, List.of(SharingScope.PREPARATION_SNAPSHOT), 2));
                waitUntil(() -> {
                    try {
                        return scopesMeHasReceivedFrom(fakePeer.identity().publicKey().id())
                                .map(scopes -> scopes.contains(SharingScope.PREPARATION_SNAPSHOT)).orElse(false);
                    } catch (Exception e) {
                        return false;
                    }
                }, "second lifecycle (after disable/re-enable) never genuinely re-synced a real frame");
            } finally {
                secondDial.connection().close();
            }
        } finally {
            networkingService.close();
        }
    }

    @Test
    @Timeout(20)
    void applicationShutdownClosesEverythingPromptlyWithNoLingeringResources() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100);

        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        long contactId = pairWithFakePeer(contactService, fakePeer);
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), 1, null, false), nowMillis());

        List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        NetworkingService networkingService = new NetworkingService();
        try (PeerListener fakePeerListener = new PeerListener(fakePeer.transport(), fakePeer.context(id -> KnownContactLookup.Status.PAIRED),
                0, 5, 4, 4, 8, accepted::add, event -> { })) {
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            DialOutcome outcome = networkingService.connectToContact(contactId, new PeerAddress("127.0.0.1", fakePeerListener.localPort()),
                    RetryPolicy.standard(), new AtomicBoolean(false)).get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated());
            waitUntil(() -> accepted.size() >= 1, "fake peer never accepted");

            long before = System.currentTimeMillis();
            networkingService.close(); // mirrors PeerSessionHolder.shutdown() -> networkingService.close()
            long elapsedMillis = System.currentTimeMillis() - before;

            assertTrue(elapsedMillis < 15_000, "close() must return promptly, not hang: took " + elapsedMillis + "ms");
            assertFalse(networkingService.isNetworkingEnabled());
            waitUntil(() -> aliveDaemonThreadsNamed("codefit-peer-sync-") == 0, "close() must leave no codefit-peer-sync-* thread alive");
            waitUntil(() -> aliveDaemonThreadsNamed("codefit-peer-dial-") == 0, "close() must leave no codefit-peer-dial-* thread alive");
        }
    }

    @Test
    @Timeout(10)
    void syncOutboxNowRequiresNetworkingEnabled() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        long contactId = pairWithFakePeer(contactService, fakePeer);

        NetworkingService networkingService = new NetworkingService();
        assertThrows(IllegalStateException.class, () -> networkingService.syncOutboxNow(contactId),
                "syncOutboxNow must require networking to be enabled");
    }

    @Test
    @Timeout(30)
    void syncOutboxNowRequiresAnActiveConnection() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100);
        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        long contactId = pairWithFakePeer(contactService, fakePeer);

        NetworkingService networkingService = new NetworkingService();
        try {
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            assertThrows(IllegalStateException.class, () -> networkingService.syncOutboxNow(contactId),
                    "syncOutboxNow must require an active authenticated connection to this contact");
        } finally {
            networkingService.close();
        }
    }

    /**
     * The scenario {@code syncOutboxNow} exists for: a connection already established with nothing
     * new to send (the automatic establishment send has nothing eligible yet), new data approved
     * afterward, and {@code syncOutboxNow} - not a reconnect, not a second receive loop - delivering
     * it over that same still-live connection.
     */
    @Test
    @Timeout(30)
    void syncOutboxNowSendsDataApprovedAfterTheConnectionWasAlreadyEstablished() throws Exception {
        IdentityService identityService = new IdentityService();
        identityService.createIdentity(PASSPHRASE, nowMillis());
        Thread.sleep(1_100);

        ContactService contactService = new ContactService();
        FakePeer fakePeer = FakePeer.create();
        long contactId = pairWithFakePeer(contactService, fakePeer);
        // Deliberately no permission grant yet - the automatic establishment send below must have
        // nothing eligible to send, so any later consent frame can only have arrived via syncOutboxNow.

        List<PeerConnection> accepted = new CopyOnWriteArrayList<>();
        NetworkingService networkingService = new NetworkingService();
        try (PeerListener fakePeerListener = new PeerListener(fakePeer.transport(), fakePeer.context(id -> KnownContactLookup.Status.PAIRED),
                0, 5, 4, 4, 8, accepted::add, event -> { })) {
            networkingService.enableNetworking(PASSPHRASE, 0, nowMillis());
            DialOutcome outcome = networkingService.connectToContact(contactId, new PeerAddress("127.0.0.1", fakePeerListener.localPort()),
                    RetryPolicy.standard(), new AtomicBoolean(false)).get(10, TimeUnit.SECONDS);
            assertTrue(outcome.result().authenticated());
            waitUntil(() -> accepted.size() >= 1, "fake peer never accepted");
            PeerConnection fakePeerSideConnection = accepted.get(0);

            // Now approve sharing, after the connection is already live.
            contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), 1, null, false), nowMillis());
            networkingService.syncOutboxNow(contactId);

            SignedEnvelope received = receiveWithin(fakePeerSideConnection, Duration.ofSeconds(10));
            assertTrue(received.body() instanceof ConsentRevision,
                    "syncOutboxNow must deliver the newly-approved consent over the already-live connection");
        } finally {
            networkingService.close();
        }
    }
}

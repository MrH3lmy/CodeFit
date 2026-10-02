package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.identity.IdentityMismatchException;
import com.codefit.peer.identity.IllegalContactStateException;
import com.codefit.peer.identity.KnownIdentityException;
import com.codefit.peer.identity.ObservedTransportBinding;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.invitation.Invitation;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.invitation.InvitationException;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.transport.ConnectionFailureReason;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.ListenerBindAddress;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.PeerNetworkService;
import com.codefit.peer.transport.PinnedBinding;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.peer.transport.TransportKeyMaterial;
import com.codefit.peer.transport.TransportTestSupport.FakeContacts;
import com.codefit.peer.transport.TransportTestSupport.TestPeer;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.codefit.peer.transport.TransportTestSupport.hoursAgo;
import static com.codefit.peer.transport.TransportTestSupport.keyOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The explicit recovery path for a {@link TrustState#PAIRED} contact after <em>both</em> sides
 * independently rotate their transport key before reconnecting — the one case the live rollover
 * handshake ({@code com.codefit.peer.transport.PeerSession#dialForRollover}) cannot resolve, because
 * neither side's TLS certificate is still the one the other side has pinned (docs/p2p/transport-v1.md
 * §9, {@code TransportRolloverTest#ifBothPeersRotatedTheRolloverIsRefusedRatherThanDisclosingFirstAndTheOldPinsStay}).
 * This suite exercises the documented recovery through {@link NetworkingService#recoverContactTransportKey}
 * and the companion duplicate-contact refusal in {@link NetworkingService#registerPendingContactFromInvitation}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class TransportKeyRecoveryTest {
    private static final char[] PASS = "recovery-pass".toCharArray();
    private static final RetryPolicy ONCE = new RetryPolicy(1, Duration.ofMillis(50), Duration.ofMillis(100), 0.1);

    private Instant now;
    private IdentityService identityService;
    private ContactService contactService;
    private TransportKeyService keyService;
    private final List<NetworkingService> networking = new ArrayList<>();
    private final List<PeerNetworkService> remotes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        now = Instant.ofEpochSecond(Instant.now().getEpochSecond());
        identityService = new IdentityService();
        contactService = new ContactService();
        keyService = new TransportKeyService();
        identityService.createIdentity(PASS, now.minus(Duration.ofDays(20)));
    }

    @AfterEach
    void tearDown() {
        networking.forEach(NetworkingService::close);
        remotes.forEach(PeerNetworkService::disable);
    }

    private NetworkingService newNetworking() {
        NetworkingService service = new NetworkingService(identityService, contactService, keyService);
        networking.add(service);
        return service;
    }

    private IdentityId aliceId() {
        return identityService.currentIdentity().orElseThrow().id();
    }

    private PeerNetworkService startBob(TestPeer bob, TransportKeyMaterial key, long epoch, FakeContacts bobContacts) throws Exception {
        PeerNetworkService service = new PeerNetworkService(bobContacts, event -> { }, bobContacts.sink());
        service.enable(bob.identity, key, epoch, 0, ListenerBindAddress.loopbackOnly());
        remotes.add(service);
        return service;
    }

    private static byte[] nonce() {
        byte[] nonce = new byte[Invitation.NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    /** Bob's own, freshly signed invitation advertising {@code key} right now, for recovery (never dialing). */
    private static SignedInvitation invitationFrom(TestPeer peer, TransportKeyMaterial key, Instant now) {
        Invitation invitation = new Invitation(peer.identity.publicKey(), keyOf(key), key.validFrom(), key.validUntil(),
                List.of(new PeerAddress("127.0.0.1", 1)), nonce(), now, now.plus(Duration.ofHours(1)));
        return InvitationCodec.sign(invitation, peer.identity);
    }

    /** Alice learns Bob the way a user would: Bob's signed invitation, then an explicit accept. */
    private Contact pairAliceWithBob(TestPeer bob, TransportKeyMaterial bobKey, int bobPort) {
        Invitation invitation = new Invitation(bob.identity.publicKey(), keyOf(bobKey), bobKey.validFrom(), bobKey.validUntil(),
                List.of(new PeerAddress("127.0.0.1", bobPort)), nonce(), now, now.plus(Duration.ofHours(1)));
        SignedInvitation signed = InvitationCodec.sign(invitation, bob.identity);
        Contact pending = newNetworking().registerPendingContactFromInvitation(signed, now);
        return contactService.acceptInvitation(pending.id(), now);
    }

    private static DialOutcome await(java.util.concurrent.CompletableFuture<DialOutcome> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }

    @Test
    @Timeout(90)
    void bothSidesRotatingIsRefusedByRolloverButRecoveredThroughAFreshInvitationAndThenReconnects() throws Exception {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        FakeContacts bobContacts = new FakeContacts();
        PeerNetworkService bobService = startBob(bob, bobOldKey, 1L, bobContacts);
        int bobPort = bobService.listeningPort().orElseThrow();
        Contact bobContact = pairAliceWithBob(bob, bobOldKey, bobPort);
        PeerAddress bobAddress = new PeerAddress("127.0.0.1", bobPort);

        // A granted permission and its revision must survive the whole rotation/recovery dance untouched.
        contactService.updatePermissions(bobContact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), now);
        ContactPermission permissionBefore = contactService.permissionsFor(bobContact.id()).orElseThrow();

        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofMinutes(10)));
        bobContacts.pair(aliceId(), new PinnedBinding(alice.liveTransportKey().orElseThrow(), now.minus(Duration.ofMinutes(10))));

        // Both sides rotate independently before ever reconnecting.
        alice.rotateTransportKey(PASS, now);
        TransportKeyMaterial bobNewKey = bob.newTransportKey(hoursAgo(1));
        assertTrue(bobService.rekey(bobNewKey));

        // Normal rollover cannot resolve it: neither side's live TLS key is what the other has pinned.
        DialOutcome refused = await(alice.connectToContact(bobContact.id(), bobAddress, ONCE, new AtomicBoolean(false)));
        assertFalse(refused.result().authenticated());
        assertEquals(ConnectionFailureReason.ROLLOVER_REFUSED, refused.result().failureReason(), refused.result().detail());
        assertEquals(keyOf(bobOldKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey(),
                "a refused rollover must never move the pin");

        // Bob issues a fresh signed invitation carrying his new key; merely decoding/parsing it must not
        // mutate anything (#182: "parsing an invitation alone must not establish trust").
        SignedInvitation bobFreshInvitation = invitationFrom(bob, bobNewKey, now);
        alice.parseInvitationBase64(InvitationCodec.toBase64(bobFreshInvitation));
        assertEquals(keyOf(bobOldKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey(),
                "parsing a fresh invitation alone must not touch the pin");

        // The explicit, separate recovery action approves and applies it.
        ContactService.TransportBindingUpdate update = alice.recoverContactTransportKey(bobContact.id(), bobFreshInvitation, now);
        assertEquals(ContactService.TransportBindingUpdate.ROTATED, update);
        assertEquals(keyOf(bobNewKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey(),
                "the pin moved forward only after explicit recovery");

        // Reconnecting now succeeds against the recovered key.
        DialOutcome reconnected = await(alice.connectToContact(bobContact.id(), bobAddress, ONCE, new AtomicBoolean(false)));
        assertTrue(reconnected.result().authenticated(), reconnected.result().toString());
        assertEquals(keyOf(bobNewKey), reconnected.result().remoteBinding().transportKey());

        // Contact identity, history and permissions were never touched by any of this.
        Contact afterContact = contactService.requireContact(bobContact.id());
        assertEquals(bobContact.id(), afterContact.id());
        assertEquals(bobContact.identityId(), afterContact.identityId());
        assertEquals(bobContact.createdAt(), afterContact.createdAt());
        assertEquals(TrustState.PAIRED, afterContact.trustState());
        ContactPermission permissionAfter = contactService.permissionsFor(bobContact.id()).orElseThrow();
        assertEquals(permissionBefore, permissionAfter, "recovery must never touch sharing permissions");
        assertEquals(1, contactService.listContacts().size(), "recovery never creates or duplicates a contact");
    }

    @Test
    @Timeout(90)
    void recoveryIgnoresAStaleOrReplayedInvitationAndNeverRollsThePinBackward() throws Exception {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(5));
        TransportKeyMaterial bobCurrentKey = bob.newTransportKey(hoursAgo(1));
        Contact bobContact = pairAliceWithBob(bob, bobOldKey, 4242);

        // Bob already rotated once and that rotation was already recovered/observed.
        contactService.recordAuthenticatedTransportBinding(bobContact.id(), keyOf(bobCurrentKey),
                bobCurrentKey.validFrom(), bobCurrentKey.validUntil(), now);
        assertEquals(keyOf(bobCurrentKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey());

        NetworkingService alice = newNetworking();

        // A stale invitation surfaces (e.g. an old copy, or a captured replay) carrying the SUPERSEDED key.
        SignedInvitation staleInvitation = invitationFrom(bob, bobOldKey, now);
        ContactService.TransportBindingUpdate update = alice.recoverContactTransportKey(bobContact.id(), staleInvitation, now);

        assertEquals(ContactService.TransportBindingUpdate.IGNORED_STALE, update);
        assertEquals(keyOf(bobCurrentKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey(),
                "a stale invitation must never roll the pin backward");
    }

    @Test
    @Timeout(90)
    void recoveryRefusesAnInvitationSignedByADifferentIdentity() throws Exception {
        TestPeer bob = TestPeer.create();
        TestPeer mallory = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        Contact bobContact = pairAliceWithBob(bob, bobOldKey, 4242);
        ObservedTransportBinding pinBefore = contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow();

        NetworkingService alice = newNetworking();

        // Mallory can only validly sign an invitation for HER OWN identity key (InvitationCodec.sign
        // refuses a mismatched signer), but presents it as an attempted update to Bob's contact.
        TransportKeyMaterial malloryKey = mallory.newTransportKey(hoursAgo(1));
        SignedInvitation malloryInvitation = invitationFrom(mallory, malloryKey, now);

        assertThrows(IdentityMismatchException.class,
                () -> alice.recoverContactTransportKey(bobContact.id(), malloryInvitation, now));
        assertEquals(pinBefore, contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow(),
                "an invitation from a different identity must never update Bob's contact");
    }

    @Test
    @Timeout(90)
    void recoveryRefusesForBlockedOrRemovedContactsUntilExplicitlyRePaired() throws Exception {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        TransportKeyMaterial bobNewKey = bob.newTransportKey(hoursAgo(1));
        Contact bobContact = pairAliceWithBob(bob, bobOldKey, 4242);
        NetworkingService alice = newNetworking();
        SignedInvitation freshInvitation = invitationFrom(bob, bobNewKey, now);

        contactService.block(bobContact.id(), false, now);
        assertThrows(IllegalContactStateException.class,
                () -> alice.recoverContactTransportKey(bobContact.id(), freshInvitation, now),
                "a blocked contact must not silently regain trust through recovery");
        assertEquals(keyOf(bobOldKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey());

        contactService.remove(bobContact.id(), false, now);
        assertThrows(IllegalContactStateException.class,
                () -> alice.recoverContactTransportKey(bobContact.id(), freshInvitation, now),
                "a removed contact must not silently regain trust through recovery");

        // The existing, explicit re-pair flow is what intentionally allows it to resume.
        contactService.rePair(bobContact.id(), now);
        ContactService.TransportBindingUpdate update = alice.recoverContactTransportKey(bobContact.id(), freshInvitation, now);
        assertEquals(ContactService.TransportBindingUpdate.ROTATED, update);
        assertEquals(keyOf(bobNewKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey());
    }

    @Test
    @Timeout(90)
    void aFreshInvitationForAnAlreadyKnownIdentityNeverCreatesADuplicateContactOrMutatesTheExistingPin() throws Exception {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        TransportKeyMaterial bobNewKey = bob.newTransportKey(hoursAgo(1));
        Contact bobContact = pairAliceWithBob(bob, bobOldKey, 4242);
        NetworkingService alice = newNetworking();

        SignedInvitation freshInvitation = invitationFrom(bob, bobNewKey, now);

        KnownIdentityException thrown = assertThrows(KnownIdentityException.class,
                () -> alice.registerPendingContactFromInvitation(freshInvitation, now));
        assertEquals(bobContact.id(), thrown.existingContactId());
        assertEquals(TrustState.PAIRED, thrown.trustState());

        assertEquals(1, contactService.listContacts().size(), "no duplicate contact was created");
        assertEquals(keyOf(bobOldKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey(),
                "the ordinary ingestion path must never mutate an existing contact's pin");

        // The caller routes to the dedicated recovery action using the id this exception carries instead.
        ContactService.TransportBindingUpdate update = alice.recoverContactTransportKey(thrown.existingContactId(), freshInvitation, now);
        assertEquals(ContactService.TransportBindingUpdate.ROTATED, update);
    }

    @Test
    @Timeout(90)
    void recoveryRefusesAnInvitationWhoseDeclaredBindingWindowDoesNotCoverNow() throws Exception {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(hoursAgo(4));
        Contact bobContact = pairAliceWithBob(bob, bobOldKey, 4242);
        NetworkingService alice = newNetworking();

        // A genuinely signed invitation whose declared binding is not valid until the future.
        TransportKeyMaterial notYetValid = new TransportKeyMaterial(com.codefit.peer.identity.crypto.KeyPairs.generate(),
                now.plus(Duration.ofHours(2)), now.plus(Duration.ofDays(90)));
        SignedInvitation futureInvitation = invitationFrom(bob, notYetValid, now);
        assertThrows(InvitationException.class,
                () -> alice.recoverContactTransportKey(bobContact.id(), futureInvitation, now));
        assertEquals(keyOf(bobOldKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey());
    }

    /**
     * Proves the recovery method has no built-in direction: it takes only a contact id and a signed
     * invitation, so whichever paired identity is doing the recovering runs the exact same code path.
     * Demonstrated against two fully independent, real {@link NetworkingService} stacks, each over its
     * own isolated SQLite database (never simultaneously "live" — see {@link DatabaseConfig}'s single,
     * process-wide current database), each recovering the other's rotated key from a fresh invitation.
     */
    @Test
    @Timeout(90)
    void recoveryWorksInBothDirectionsWhenEachSideIndependentlyRotates() throws Exception {
        String savedDatabaseUrl = DatabaseConfig.currentDatabaseUrl();
        Path bobDbDir = Files.createTempDirectory("codefit-recovery-bob");
        try {
            // --- Alice's side (the class's shared isolated database): mint her first invitation. ---
            NetworkingService alice = newNetworking();
            SignedInvitation aliceInvite1 = alice.createInvitation(PASS,
                    List.of(new PeerAddress("127.0.0.1", 1)), Duration.ofHours(1), now);
            IdentityId aliceIdentityId = aliceId();

            // --- Switch to Bob's own, independent database and stand up his own real stack. ---
            DatabaseConfig.useDatabaseFile(bobDbDir.resolve("bob.db"));
            DatabaseConfig.initialize();
            char[] bobPass = "bobs-own-pass".toCharArray();
            IdentityService bobIdentityService = new IdentityService();
            ContactService bobContactService = new ContactService();
            TransportKeyService bobKeyService = new TransportKeyService();
            bobIdentityService.createIdentity(bobPass, now.minus(Duration.ofDays(20)));
            NetworkingService bob = new NetworkingService(bobIdentityService, bobContactService, bobKeyService);

            Contact bobsAliceContact = bob.registerPendingContactFromInvitation(aliceInvite1, now);
            bobsAliceContact = bobContactService.acceptInvitation(bobsAliceContact.id(), now);
            SignedInvitation bobInvite1 = bob.createInvitation(bobPass,
                    List.of(new PeerAddress("127.0.0.1", 1)), Duration.ofHours(1), now);
            IdentityId bobIdentityId = bobIdentityService.currentIdentity().orElseThrow().id();

            // --- Back to Alice's database: pair with Bob using his first invitation. ---
            DatabaseConfig.useDatabaseUrl(savedDatabaseUrl);
            Contact alicesBobContact = alice.registerPendingContactFromInvitation(bobInvite1, now);
            alicesBobContact = contactService.acceptInvitation(alicesBobContact.id(), now);
            assertEquals(bobIdentityId, alicesBobContact.identityId());

            // --- Both sides independently rotate before ever reconnecting. Neither has an enabled
            // listener in this test (no live dial is attempted), so the renewed key is read from the
            // freshly minted invitation rather than NetworkingService#liveTransportKey(), which only
            // reflects a running listener.
            alice.rotateTransportKey(PASS, now);
            SignedInvitation aliceInvite2 = alice.createInvitation(PASS,
                    List.of(new PeerAddress("127.0.0.1", 1)), Duration.ofHours(1), now.plusSeconds(1));
            IdentityKey aliceNewKey = aliceInvite2.invitation().transportKey();

            DatabaseConfig.useDatabaseFile(bobDbDir.resolve("bob.db"));
            bob.rotateTransportKey(bobPass, now);
            SignedInvitation bobInvite2 = bob.createInvitation(bobPass,
                    List.of(new PeerAddress("127.0.0.1", 1)), Duration.ofHours(1), now.plusSeconds(1));
            IdentityKey bobNewKey = bobInvite2.invitation().transportKey();

            // --- Bob recovers Alice's new key from her fresh invitation. ---
            ContactService.TransportBindingUpdate bobSideUpdate =
                    bob.recoverContactTransportKey(bobsAliceContact.id(), aliceInvite2, now.plusSeconds(1));
            assertEquals(ContactService.TransportBindingUpdate.ROTATED, bobSideUpdate);
            assertEquals(aliceNewKey, bobContactService.lastObservedTransportBinding(bobsAliceContact.id()).orElseThrow().transportKey(),
                    "Bob's own pin for Alice moved forward");

            // --- Alice recovers Bob's new key from his fresh invitation, the same way, in the other direction. ---
            DatabaseConfig.useDatabaseUrl(savedDatabaseUrl);
            ContactService.TransportBindingUpdate aliceSideUpdate =
                    alice.recoverContactTransportKey(alicesBobContact.id(), bobInvite2, now.plusSeconds(1));
            assertEquals(ContactService.TransportBindingUpdate.ROTATED, aliceSideUpdate);
            assertEquals(bobNewKey, contactService.lastObservedTransportBinding(alicesBobContact.id()).orElseThrow().transportKey(),
                    "Alice's own pin for Bob moved forward, independently of Bob's own recovery of her key");
            assertNotEquals(aliceNewKey, bobNewKey);
        } finally {
            DatabaseConfig.useDatabaseUrl(savedDatabaseUrl);
            deleteRecursively(bobDbDir);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}

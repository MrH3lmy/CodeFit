package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ObservedTransportBinding;
import com.codefit.peer.invitation.Invitation;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
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

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.codefit.peer.transport.TransportTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The local and remote transport-key lifecycle through the real {@link NetworkingService}, real
 * {@link TransportKeyService} and a real (isolated) SQLite database, against a second peer ("Bob") that is a
 * raw {@link PeerNetworkService}: persisted key, running listener, advertised invitation key and emitted
 * {@code IDENTITY_BINDING} must always agree, and a paired contact's pinned key must only ever move forward
 * on an identity-signed proof. Timestamps are anchored to the real clock (TLS checks certificate validity
 * against it) with the local identity/key "aged" so the 7-day renewal window can be hit without waiting.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class NetworkingServiceKeyLifecycleTest {
    private static final char[] PASS = "lifecycle-pass".toCharArray();

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

    /** Bob: a raw remote peer that knows Alice as paired, pinned to {@code alicePin}. */
    private PeerNetworkService startBob(TestPeer bob, TransportKeyMaterial key, long epoch, FakeContacts bobContacts) throws Exception {
        PeerNetworkService service = new PeerNetworkService(bobContacts, event -> { }, bobContacts.sink());
        service.enable(bob.identity, key, epoch, 0, ListenerBindAddress.loopbackOnly());
        remotes.add(service);
        return service;
    }

    /** Alice learns Bob the way a user would: Bob's signed invitation, then an explicit accept. */
    private Contact pairAliceWithBob(TestPeer bob, TransportKeyMaterial bobKey, int bobPort) {
        Invitation invitation = new Invitation(bob.identity.publicKey(), keyOf(bobKey), bobKey.validFrom(), bobKey.validUntil(),
                List.of(new PeerAddress("127.0.0.1", bobPort)), nonce(), now, now.plus(Duration.ofHours(1)));
        SignedInvitation signed = InvitationCodec.sign(invitation, bob.identity);
        Contact pending = newNetworking().registerPendingContactFromInvitation(signed, now);
        return contactService.acceptInvitation(pending.id(), now);
    }

    private static byte[] nonce() {
        byte[] nonce = new byte[Invitation.NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        return nonce;
    }

    private static DialOutcome await(java.util.concurrent.CompletableFuture<DialOutcome> future) throws Exception {
        return future.get(20, TimeUnit.SECONDS);
    }

    private static final RetryPolicy ONCE = new RetryPolicy(1, Duration.ofMillis(50), Duration.ofMillis(100), 0.1);

    private IdentityKey persistedKey() {
        return keyService.currentPublicKey().orElseThrow();
    }

    /** Seeds a transport key issued 175 days ago: its binding ends in 5 days, i.e. inside the 7-day renewal window. */
    private IdentityKey seedKeyNearExpiry() {
        TransportKeyMaterial old = keyService.ensureCurrent(PASS, now.minus(Duration.ofDays(175)));
        assertTrue(old.validUntil().isBefore(now.plus(Duration.ofDays(7))));
        return persistedKey();
    }

    @Test
    @Timeout(90)
    void aSuccessfulReconnectWithAnUnchangedKeyPersistsTheVerifiedBindingAndKeepsThePin() throws Exception {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobKey = bob.newTransportKey(now.minus(Duration.ofHours(3)));
        FakeContacts bobContacts = new FakeContacts();
        PeerNetworkService bobService = startBob(bob, bobKey, 1L, bobContacts);
        Contact bobContact = pairAliceWithBob(bob, bobKey, bobService.listeningPort().orElseThrow());

        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofMinutes(5)));
        bobContacts.pair(aliceId(), new PinnedBinding(alice.liveTransportKey().orElseThrow(), now.minus(Duration.ofMinutes(5))));

        ObservedTransportBinding before = contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow();
        PeerAddress bobAddress = new PeerAddress("127.0.0.1", bobService.listeningPort().orElseThrow());
        for (int i = 0; i < 2; i++) {
            DialOutcome outcome = await(alice.connectToContact(bobContact.id(), bobAddress, ONCE, new AtomicBoolean(false)));
            assertTrue(outcome.result().authenticated(), "reconnect #" + i + ": " + outcome.result());
            assertEquals(keyOf(bobKey), outcome.result().remoteBinding().transportKey());
        }

        ObservedTransportBinding after = contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow();
        assertEquals(before.transportKey(), after.transportKey());
        assertEquals(before.validFrom(), after.validFrom());
        assertTrue(after.observedAt().isAfter(before.observedAt()) || after.observedAt().equals(before.observedAt()));
        assertFalse(contactService.knownAddresses(bobContact.id()).isEmpty(), "the working address is cached after authentication");
    }

    @Test
    @Timeout(90)
    void invitationCreatedWhileNetworkingIsAlreadyEnabledAdvertisesExactlyTheKeyTheListenerPresents() throws Exception {
        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofMinutes(5)));
        IdentityKey live = alice.liveTransportKey().orElseThrow();

        SignedInvitation invitation = alice.createInvitation(PASS, Duration.ofHours(1), now);

        assertEquals(live, invitation.invitation().transportKey());
        assertEquals(live, persistedKey());
        assertEquals(live, alice.liveTransportKey().orElseThrow());

        // Bob pins what the invitation says and must be able to connect to the already-running listener.
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobKey = bob.newTransportKey(now.minus(Duration.ofHours(1)));
        FakeContacts bobContacts = new FakeContacts().pair(aliceId(),
                new PinnedBinding(invitation.invitation().transportKey(), invitation.invitation().bindingValidFrom()));
        PeerNetworkService bobService = startBob(bob, bobKey, 1L, bobContacts);
        pairAliceWithBob(bob, bobKey, bobService.listeningPort().orElseThrow());

        DialOutcome outcome = await(bobService.connect(aliceId(), invitation.invitation().addresses().get(0),
                bobContacts.pinFor(aliceId()), ONCE, new AtomicBoolean(false)));
        assertTrue(outcome.result().authenticated(), outcome.result().toString());
        assertEquals(live, outcome.result().remoteBinding().transportKey(), "the emitted IDENTITY_BINDING names the same key");
    }

    @Test
    @Timeout(90)
    void invitationNearTheRenewalWindowSwitchesTheRunningListenerToTheRenewedKeyBeforeAdvertisingIt() throws Exception {
        IdentityKey oldKey = seedKeyNearExpiry();
        NetworkingService alice = newNetworking();
        // Enabled 4 days ago the old key is not yet due (7-day window not reached), so it is the one listening.
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofDays(4)));
        assertEquals(oldKey, alice.liveTransportKey().orElseThrow());
        int portBefore = alice.listeningPort().orElseThrow();

        // Today the key is inside its renewal window: ensureCurrent() inside createInvitation rotates it.
        SignedInvitation invitation = alice.createInvitation(PASS, Duration.ofHours(1), now);

        IdentityKey advertised = invitation.invitation().transportKey();
        assertNotEquals(oldKey, advertised, "the key was renewed");
        assertEquals(advertised, persistedKey(), "persisted == advertised");
        assertEquals(advertised, alice.liveTransportKey().orElseThrow(), "the running listener was switched before the invitation was issued");
        assertEquals(portBefore, alice.listeningPort().orElseThrow(), "renewal must not move the listener");

        // A newly invited peer pins the advertised key and connects (before the fix: WRONG_PIN against OLD_KEY).
        TestPeer carol = TestPeer.create();
        TransportKeyMaterial carolKey = carol.newTransportKey(now.minus(Duration.ofHours(1)));
        FakeContacts carolContacts = new FakeContacts().pair(aliceId(),
                new PinnedBinding(advertised, invitation.invitation().bindingValidFrom()));
        PeerNetworkService carolService = startBob(carol, carolKey, 1L, carolContacts);
        pairAliceWithBob(carol, carolKey, carolService.listeningPort().orElseThrow());
        DialOutcome outcome = await(carolService.connect(aliceId(), invitation.invitation().addresses().get(0),
                carolContacts.pinFor(aliceId()), ONCE, new AtomicBoolean(false)));
        assertTrue(outcome.result().authenticated(), outcome.result().toString());
        assertEquals(advertised, outcome.result().remoteBinding().transportKey());
    }

    @Test
    @Timeout(90)
    void aPairedPeerPinnedToTheOldKeyFollowsALocalAutomaticRotationThroughTheRolloverProof() throws Exception {
        IdentityKey oldKey = seedKeyNearExpiry();
        ObservedPinSeed seed = new ObservedPinSeed(oldKey, keyService.ensureCurrent(PASS, now.minus(Duration.ofDays(175))).validFrom());
        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofDays(4)));

        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobKey = bob.newTransportKey(now.minus(Duration.ofHours(3)));
        FakeContacts bobContacts = new FakeContacts().pair(aliceId(), new PinnedBinding(seed.key(), seed.validFrom()));
        PeerNetworkService bobService = startBob(bob, bobKey, 1L, bobContacts);
        pairAliceWithBob(bob, bobKey, bobService.listeningPort().orElseThrow());

        assertTrue(alice.refreshTransportKey(PASS, now), "inside the renewal window the key is renewed");
        IdentityKey renewed = alice.liveTransportKey().orElseThrow();
        assertNotEquals(oldKey, renewed);
        assertEquals(renewed, persistedKey());
        assertFalse(alice.refreshTransportKey(PASS, now.plusSeconds(1)), "a second refresh finds nothing to do");

        DialOutcome outcome = await(bobService.connect(aliceId(), new PeerAddress("127.0.0.1", alice.listeningPort().orElseThrow()),
                bobContacts.pinFor(aliceId()), ONCE, new AtomicBoolean(false)));
        assertTrue(outcome.result().authenticated(), "Bob follows Alice's rotation: " + outcome.result());
        assertEquals(renewed, outcome.result().remoteBinding().transportKey());
        assertEquals(renewed, bobContacts.pinFor(aliceId()).transportKey(), "Bob's pin moved to the proven key");
    }

    private record ObservedPinSeed(IdentityKey key, Instant validFrom) {
    }

    @Test
    @Timeout(90)
    void aForcedRotationSwitchesTheListenerAndPersistedStateTogether() throws Exception {
        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofMinutes(5)));
        IdentityKey first = alice.liveTransportKey().orElseThrow();

        alice.rotateTransportKey(PASS, now);

        IdentityKey second = alice.liveTransportKey().orElseThrow();
        assertNotEquals(first, second);
        assertEquals(second, persistedKey());
        assertEquals(second, alice.createInvitation(PASS, Duration.ofHours(1), now.plusSeconds(1)).invitation().transportKey());
        assertEquals(second, alice.liveTransportKey().orElseThrow());
    }

    @Test
    @Timeout(90)
    void enablingNetworkingTwiceIsIdempotentAndDoesNotStartASecondWriterSession() throws Exception {
        NetworkingService alice = newNetworking();
        Instant t = now.minus(Duration.ofMinutes(5));
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), t);
        int port = alice.listeningPort().orElseThrow();
        IdentityKey key = alice.liveTransportKey().orElseThrow();
        long epoch = identityService.currentIdentity().orElseThrow().highestKnownEpoch();

        alice.enableNetworking(PASS, 0, t); // same instant: a second beginWriterSession would be refused

        assertEquals(port, alice.listeningPort().orElseThrow());
        assertEquals(key, alice.liveTransportKey().orElseThrow());
        assertEquals(epoch, identityService.currentIdentity().orElseThrow().highestKnownEpoch());
    }

    @Test
    @Timeout(120)
    void aPeerRotationIsFollowedByConnectToContactAndSurvivesARestartOfTheLocalDevice() throws Exception {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(now.minus(Duration.ofHours(4)));
        FakeContacts bobContacts = new FakeContacts();
        PeerNetworkService bobService = startBob(bob, bobOldKey, 1L, bobContacts);
        int bobPort = bobService.listeningPort().orElseThrow();
        Contact bobContact = pairAliceWithBob(bob, bobOldKey, bobPort);
        PeerAddress bobAddress = new PeerAddress("127.0.0.1", bobPort);

        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofMinutes(10)));
        IdentityKey aliceKey = alice.liveTransportKey().orElseThrow();
        bobContacts.pair(aliceId(), new PinnedBinding(aliceKey, now.minus(Duration.ofMinutes(10))));
        assertTrue(await(alice.connectToContact(bobContact.id(), bobAddress, ONCE, new AtomicBoolean(false))).result().authenticated());

        // Alice restarts: a brand-new service over the same database. Same persisted key, new writer epoch.
        alice.disableNetworking();
        NetworkingService restarted = newNetworking();
        Thread.sleep(1_100); // a new writer session's epoch must be a later whole second than the previous one
        restarted.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), Instant.now());
        assertEquals(aliceKey, restarted.liveTransportKey().orElseThrow(), "the persisted key survives a restart unchanged");
        DialOutcome afterRestart = await(restarted.connectToContact(bobContact.id(), bobAddress, ONCE, new AtomicBoolean(false)));
        assertTrue(afterRestart.result().authenticated(), "reconnect after restart from the persisted pin: " + afterRestart.result());
        restarted.disconnect(bob.id());

        // Bob rotates while Alice is offline; Alice, restarted again, still pins the old key.
        restarted.disableNetworking();
        TransportKeyMaterial bobNewKey = bob.newTransportKey(now.minus(Duration.ofHours(1)));
        assertTrue(bobService.rekey(bobNewKey));
        NetworkingService restartedAgain = newNetworking();
        Thread.sleep(1_100);
        restartedAgain.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), Instant.now());
        assertEquals(keyOf(bobOldKey), contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow().transportKey());

        DialOutcome rolled = await(restartedAgain.connectToContact(bobContact.id(), bobAddress, ONCE, new AtomicBoolean(false)));
        assertTrue(rolled.result().authenticated(), "rollover after restart: " + rolled.result());
        ObservedTransportBinding pinned = contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow();
        assertEquals(keyOf(bobNewKey), pinned.transportKey(), "the verified live binding was persisted");
        assertEquals(bobNewKey.validFrom().toEpochMilli(), pinned.validFrom().toEpochMilli());
    }

    @Test
    @Timeout(90)
    void aStaleOrUnauthorizedReplacementKeyNeverChangesTheStoredPin() throws Exception {
        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofMinutes(10)));
        PeerAddress aliceAddress = new PeerAddress("127.0.0.1", alice.listeningPort().orElseThrow());
        PinnedBinding alicePin = new PinnedBinding(alice.liveTransportKey().orElseThrow(), now.minus(Duration.ofMinutes(10)));

        // Bob is paired and pinned to his CURRENT (newer) key.
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial bobOldKey = bob.newTransportKey(now.minus(Duration.ofHours(5)));
        TransportKeyMaterial bobCurrentKey = bob.newTransportKey(now.minus(Duration.ofHours(1)));
        FakeContacts bobCurrentContacts = new FakeContacts().pair(aliceId(), alicePin);
        PeerNetworkService bobCurrent = startBob(bob, bobCurrentKey, 1L, bobCurrentContacts);
        Contact bobContact = pairAliceWithBob(bob, bobCurrentKey, bobCurrent.listeningPort().orElseThrow());
        ObservedTransportBinding pinBefore = contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow();
        bobCurrent.disable();

        // (1) Someone with Bob's identity but his superseded key and older binding dials in: refused as stale.
        FakeContacts staleContacts = new FakeContacts().pair(aliceId(), alicePin);
        PeerNetworkService staleBob = startBob(bob, bobOldKey, 2L, staleContacts);
        DialOutcome stale = await(staleBob.connect(aliceId(), aliceAddress, alicePin, ONCE, new AtomicBoolean(false)));
        assertFalse(stale.result().authenticated(), "the old key must not be re-adopted: " + stale.result());
        assertEquals(pinBefore, contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow(), "pin untouched by the stale attempt");

        // (2) A stranger with her own identity and key: not a known contact at all.
        TestPeer mallory = TestPeer.create();
        FakeContacts malloryContacts = new FakeContacts().pair(aliceId(), alicePin);
        PeerNetworkService malloryService = startBob(mallory, mallory.newTransportKey(now.minus(Duration.ofHours(1))), 1L, malloryContacts);
        DialOutcome stranger = await(malloryService.connect(aliceId(), aliceAddress, alicePin, ONCE, new AtomicBoolean(false)));
        assertFalse(stranger.result().authenticated());
        assertEquals(pinBefore, contactService.lastObservedTransportBinding(bobContact.id()).orElseThrow());
        assertEquals(1, contactService.listContacts().size(), "no contact was created by an unknown dialer");
    }

    @Test
    void theContactStoreOnlyEverMovesAPinForward() {
        TestPeer bob = TestPeer.create();
        TransportKeyMaterial first = bob.newTransportKey(now.minus(Duration.ofHours(5)));
        TransportKeyMaterial second = bob.newTransportKey(now.minus(Duration.ofHours(2)));
        Contact contact = pairAliceWithBob(bob, first, 4242);

        ContactService.TransportBindingUpdate same = contactService.recordAuthenticatedTransportBinding(contact.id(), keyOf(first),
                first.validFrom(), first.validUntil(), now);
        assertEquals(ContactService.TransportBindingUpdate.REFRESHED, same);

        assertEquals(ContactService.TransportBindingUpdate.ROTATED, contactService.recordAuthenticatedTransportBinding(
                contact.id(), keyOf(second), second.validFrom(), second.validUntil(), now));
        assertEquals(keyOf(second), contactService.lastObservedTransportBinding(contact.id()).orElseThrow().transportKey());

        // The superseded key's (still unexpired) binding can no longer replace it.
        assertEquals(ContactService.TransportBindingUpdate.IGNORED_STALE, contactService.recordAuthenticatedTransportBinding(
                contact.id(), keyOf(first), first.validFrom(), first.validUntil(), now));
        assertEquals(keyOf(second), contactService.lastObservedTransportBinding(contact.id()).orElseThrow().transportKey());
    }

    @Test
    @Timeout(90)
    void invitationsAdvertiseWhatTheListenerReallyAcceptsOn() throws Exception {
        NetworkingService alice = newNetworking();
        assertTrue(alice.reachableAddresses().isEmpty(), "nothing is reachable while networking is off");
        assertThrows(IllegalStateException.class, () -> alice.createInvitation(PASS, Duration.ofHours(1), now));

        alice.enableNetworking(PASS, 0, now.minus(Duration.ofMinutes(5))); // default bind: wildcard
        assertTrue(alice.listenerBinding().orElseThrow().isWildcard());
        int port = alice.listeningPort().orElseThrow();

        List<PeerAddress> reachable = alice.reachableAddresses();
        for (PeerAddress address : reachable) {
            assertEquals(port, address.port(), "advertised port is the real listening port");
            assertFalse(address.toInetAddress().isLoopbackAddress(), "a LAN peer cannot use loopback: " + address);
        }
        if (!reachable.isEmpty()) {
            SignedInvitation invitation = alice.createInvitation(PASS, Duration.ofHours(1), now);
            assertEquals(reachable, invitation.invitation().addresses());
        }
    }

    @Test
    void lanDiscoveryRefusesALoopbackOnlyListenerBecauseNoPeerCouldDialTheAnnouncedAddress() throws Exception {
        NetworkingService alice = newNetworking();
        alice.enableNetworking(PASS, 0, ListenerBindAddress.loopbackOnly(), now.minus(Duration.ofMinutes(5)));
        assertThrows(java.io.IOException.class, alice::enableLanDiscovery);
        assertFalse(alice.isLanDiscoveryEnabled());
    }
}

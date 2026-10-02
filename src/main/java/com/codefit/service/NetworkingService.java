package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactAddressSource;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.ObservedTransportBinding;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.discovery.DiscoveredPeer;
import com.codefit.peer.discovery.LanDiscoveryService;
import com.codefit.peer.invitation.Invitation;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.invitation.InvitationException;
import com.codefit.peer.invitation.InvitationRejectionReason;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.KnownContactLookup;
import com.codefit.peer.transport.ListenerBindAddress;
import com.codefit.peer.transport.LocalAddresses;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.PeerNetworkService;
import com.codefit.peer.transport.PinnedBinding;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.peer.transport.TransportKeyMaterial;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The public façade for #182: wires the identity/contact services (#181) to the transport engine
 * ({@code com.codefit.peer.transport}), and adds invitation creation/acceptance. Networking is disabled
 * until {@link #enableNetworking} is called, matching every other requirement in this class's package —
 * this is the class a future JavaFX screen (#187) is expected to call; it never touches JavaFX itself.
 *
 * <p><strong>One transport key, everywhere.</strong> The key a running listener presents, the key an
 * {@code IDENTITY_BINDING} vouches for, the key a new invitation advertises and the key persisted in
 * {@code transport_identity} must always be the same key. Every path that could change or read the local
 * key ({@link #enableNetworking}, {@link #createInvitation}, {@link #refreshTransportKey},
 * {@link #rotateTransportKey}) therefore runs under one lock and, whenever it persists a different key,
 * switches the live listener to it <em>before</em> returning the key to anyone. Nothing outside
 * {@link TransportKeyService} mints keys, and nothing but this class ever calls it while networking is on.
 *
 * <p><strong>Remote keys only move forward, and only on proof.</strong> The key pinned for a paired
 * contact changes solely when a connection fully authenticates (see {@link #onVerifiedBinding}) with an
 * identity-signed binding strictly newer than the pinned one; see docs/p2p/transport-v1.md §9.
 */
public class NetworkingService implements AutoCloseable {
    private final IdentityService identityService;
    private final ContactService contactService;
    private final TransportKeyService transportKeyService;
    private final PeerNetworkService peerNetworkService;
    /** Serializes everything that reads, changes or advertises the local transport key or the listener's lifecycle. */
    private final Object lifecycleLock = new Object();
    private volatile LanDiscoveryService lanDiscoveryService;

    public NetworkingService() {
        this(new IdentityService(), new ContactService(), new TransportKeyService());
    }

    NetworkingService(IdentityService identityService, ContactService contactService, TransportKeyService transportKeyService) {
        this.identityService = identityService;
        this.contactService = contactService;
        this.transportKeyService = transportKeyService;
        this.peerNetworkService = new PeerNetworkService(new ContactLookup(), event -> { }, this::onVerifiedBinding);
    }

    /** What the transport asks of local contact state; every answer is read fresh from the contact store. */
    private final class ContactLookup implements KnownContactLookup {
        @Override
        public Status statusOf(IdentityId candidate) {
            return findContact(candidate)
                    .map(contact -> switch (contact.trustState()) {
                        case PENDING -> Status.PENDING;
                        case PAIRED -> Status.PAIRED;
                        case BLOCKED, REMOVED -> Status.BLOCKED_OR_REMOVED;
                    })
                    .orElse(Status.UNKNOWN);
        }

        @Override
        public Optional<PinnedBinding> pinnedBindingOf(IdentityId candidate) {
            return findContact(candidate)
                    .flatMap(contact -> contactService.lastObservedTransportBinding(contact.id()))
                    .map(binding -> new PinnedBinding(binding.transportKey(), binding.validFrom()));
        }

        @Override
        public Optional<IdentityId> pairedContactPinnedTo(IdentityKey transportKey) {
            return contactService.listContacts().stream()
                    .filter(contact -> contact.trustState() == TrustState.PAIRED)
                    .filter(contact -> contactService.lastObservedTransportBinding(contact.id())
                            .map(binding -> binding.transportKey().equals(transportKey)).orElse(false))
                    .map(Contact::identityId)
                    .findFirst();
        }
    }

    /**
     * Protocol timestamps are whole milliseconds, and {@code Instant.now()} is finer than that on current
     * JDKs; every public entry point takes the caller's clock reading through this so a plain
     * {@code Instant.now()} is always acceptable.
     */
    private static Instant wireTime(Instant instant) {
        return Instant.ofEpochMilli(instant.toEpochMilli());
    }

    private Optional<Contact> findContact(IdentityId identityId) {
        return contactService.listContacts().stream()
                .filter(contact -> contact.identityId().equals(identityId))
                .findFirst();
    }

    /**
     * Called by the transport for every connection that fully authenticated, inbound or outbound, with the
     * remote's live, identity-signed binding. This is the single place a contact's pinned transport key is
     * refreshed after pairing: the same key refreshes its window, a strictly-newer binding for a different
     * key rolls the pin forward, and anything older is ignored (so a replayed pre-rotation binding can
     * never roll a contact back). Never reached for a connection that failed any check.
     */
    private void onVerifiedBinding(IdentityId remoteIdentityId, IdentityBinding binding) {
        findContact(remoteIdentityId).ifPresent(contact -> contactService.recordAuthenticatedTransportBinding(
                contact.id(), binding.transportKey(), binding.validFrom(), binding.validUntil(), Instant.now()));
    }

    public boolean isNetworkingEnabled() {
        return peerNetworkService.isEnabled();
    }

    public Optional<Integer> listeningPort() {
        return peerNetworkService.listeningPort();
    }

    /** How the running listener is bound, or empty while networking is disabled. */
    public Optional<ListenerBindAddress> listenerBinding() {
        return peerNetworkService.boundAddress();
    }

    /** The transport public key the running listener is presenting right now, or empty while disabled. */
    public Optional<IdentityKey> liveTransportKey() {
        return peerNetworkService.currentTransportPublicKey();
    }

    /**
     * Enables networking on every local interface (so real LAN peers can connect); equivalent to
     * {@link #enableNetworking(char[], int, ListenerBindAddress, Instant)} with
     * {@link ListenerBindAddress#wildcard()}. Reachability is not trust: nobody is accepted without mutual
     * TLS and an identity-signed binding from a locally paired contact.
     */
    public void enableNetworking(char[] vaultPassphrase, int listenPort, Instant now) throws IOException {
        enableNetworking(vaultPassphrase, listenPort, ListenerBindAddress.wildcard(), now);
    }

    /**
     * Unlocks the identity, ensures a current transport key exists, begins a fresh writer session
     * (protocol §10.1), and starts the listener on {@code bind}:{@code listenPort} (port 0 lets the OS
     * choose). Off by default until this is called; {@link #disableNetworking} fully reverses it. Calling it
     * while already enabled neither rebinds nor starts another writer session; it only makes sure the live
     * key is the current one (disable first to change port or bind).
     */
    public void enableNetworking(char[] vaultPassphrase, int listenPort, ListenerBindAddress bind, Instant now) throws IOException {
        now = wireTime(now);
        synchronized (lifecycleLock) {
            UnlockedIdentity identity = identityService.unlock(vaultPassphrase);
            if (peerNetworkService.isEnabled()) {
                syncLiveTransportKey(vaultPassphrase, now);
                return;
            }
            TransportKeyMaterial material = transportKeyService.ensureCurrent(vaultPassphrase, now);
            long epoch = identityService.beginWriterSession(now);
            peerNetworkService.enable(identity, material, epoch, listenPort, bind);
        }
    }

    public void disableNetworking() {
        synchronized (lifecycleLock) {
            disableLanDiscovery();
            peerNetworkService.disable();
        }
    }

    @Override
    public void close() {
        disableNetworking();
    }

    /**
     * Renews the local transport key if it is inside its renewal window (or missing/expired) and, when
     * networking is on, makes the running listener present the renewed key immediately. A long-running
     * session must call this periodically (the vault passphrase is deliberately not kept in memory, so the
     * service cannot do it unprompted); {@link #createInvitation} and {@link #enableNetworking} already do.
     *
     * @return {@code true} if the key in use changed
     */
    public boolean refreshTransportKey(char[] vaultPassphrase, Instant now) {
        now = wireTime(now);
        synchronized (lifecycleLock) {
            identityService.unlock(vaultPassphrase);
            IdentityKey before = transportKeyService.currentPublicKey().orElse(null);
            TransportKeyMaterial current = syncLiveTransportKey(vaultPassphrase, now);
            IdentityKey after = new IdentityKey(KeyPairs.rawPublicKey(current.keyPair().getPublic()));
            return !after.equals(before);
        }
    }

    /**
     * Forces a brand-new transport key now (e.g. suspected key exposure), persists it, and switches the
     * running listener to it. Already-paired peers pick it up through the rollover handshake the next time
     * they connect (docs/p2p/transport-v1.md §9); invitations issued before this call advertise the old key.
     */
    public void rotateTransportKey(char[] vaultPassphrase, Instant now) {
        now = wireTime(now);
        synchronized (lifecycleLock) {
            identityService.unlock(vaultPassphrase);
            TransportKeyMaterial rotated = transportKeyService.rotate(vaultPassphrase, now);
            if (peerNetworkService.isEnabled()) {
                peerNetworkService.rekey(rotated);
            }
        }
    }

    /**
     * Brings persisted state and the live listener to the same, current key. The persisted key is the
     * source of truth: {@code ensureCurrent} may mint a new one, and if the listener is on a different key
     * (because it just rotated, or an earlier switch failed part-way) it is switched now, so the returned
     * key is always the one being presented. Caller must hold {@link #lifecycleLock}.
     */
    private TransportKeyMaterial syncLiveTransportKey(char[] vaultPassphrase, Instant now) {
        TransportKeyMaterial current = transportKeyService.ensureCurrent(vaultPassphrase, now);
        if (peerNetworkService.isEnabled()) {
            peerNetworkService.rekey(current);
        }
        return current;
    }

    public boolean isLanDiscoveryEnabled() {
        return lanDiscoveryService != null;
    }

    /**
     * Starts optional, opt-in LAN discovery (#182; off unless explicitly called). Requires networking to
     * already be enabled, since announcements advertise this device's live listening port. Discovered
     * paired contacts are recorded into the same address cache {@link #registerPendingContactFromInvitation}
     * seeds, never trusted beyond "here is an address to try dialing." Announcements go out only on the
     * interfaces the listener's bind actually covers, so what peers learn is an address and port that are
     * really listening.
     *
     * @throws IllegalStateException networking is not enabled, or no local identity exists yet
     * @throws IOException           the listener is loopback-only (nothing on the LAN could dial it) or no
     *                               suitable multicast interface exists
     */
    public void enableLanDiscovery() throws IOException {
        synchronized (lifecycleLock) {
            if (lanDiscoveryService != null) {
                return;
            }
            int port = peerNetworkService.listeningPort()
                    .orElseThrow(() -> new IllegalStateException("Enable networking before starting LAN discovery."));
            ListenerBindAddress bind = peerNetworkService.boundAddress().orElseThrow();
            LocalIdentitySummary identity = identityService.currentIdentity()
                    .orElseThrow(() -> new IllegalStateException("No local identity exists yet."));
            lanDiscoveryService = new LanDiscoveryService(identity.id(), this::currentPairedContactIds, port,
                    this::onDiscovered, bind);
        }
    }

    public void disableLanDiscovery() {
        synchronized (lifecycleLock) {
            if (lanDiscoveryService != null) {
                lanDiscoveryService.close();
                lanDiscoveryService = null;
            }
        }
    }

    private List<IdentityId> currentPairedContactIds() {
        return contactService.listContacts().stream()
                .filter(contact -> contact.trustState() == TrustState.PAIRED)
                .map(Contact::identityId)
                .toList();
    }

    private void onDiscovered(DiscoveredPeer discovered) {
        contactService.listContacts().stream()
                .filter(contact -> contact.identityId().equals(discovered.contactIdentityId()))
                .findFirst()
                .ifPresent(contact -> contactService.recordAddressSighting(
                        contact.id(), discovered.address(), ContactAddressSource.LAN_DISCOVERY, discovered.discoveredAt()));
    }

    /**
     * The addresses a remote peer could use to reach this device's running listener: this machine's
     * non-loopback interface addresses covered by the listener's bind, paired with its real listening port.
     * Empty while networking is disabled.
     */
    public List<PeerAddress> reachableAddresses() {
        Optional<Integer> port = peerNetworkService.listeningPort();
        Optional<ListenerBindAddress> bind = peerNetworkService.boundAddress();
        if (port.isEmpty() || bind.isEmpty()) {
            return List.of();
        }
        return LocalAddresses.reachable(bind.get(), port.get(), Invitation.MAX_ADDRESSES);
    }

    /**
     * Like {@link #createInvitation(char[], List, Duration, Instant)} but advertises
     * {@link #reachableAddresses()}, so the address and port in the invitation are exactly what the running
     * listener accepts connections on.
     *
     * @throws IllegalStateException networking is not enabled, or the listener has no address a remote peer could dial
     */
    public SignedInvitation createInvitation(char[] vaultPassphrase, Duration lifetime, Instant now) {
        synchronized (lifecycleLock) {
            List<PeerAddress> addresses = reachableAddresses();
            if (addresses.isEmpty()) {
                throw new IllegalStateException(peerNetworkService.isEnabled()
                        ? "The listener has no address a remote peer could dial (is it bound to loopback only?)."
                        : "Enable networking first so the invitation can advertise the address the listener is reachable on.");
            }
            return createInvitation(vaultPassphrase, addresses, lifetime, now);
        }
    }

    /**
     * Builds a signed, expiring, bounded invitation carrying this device's identity key, current
     * transport-key binding, and the given usable IP-literal addresses (ADR-0001 §4). Does not require
     * networking to already be enabled: an invitation only needs a valid transport key to exist, which
     * this also mints on demand via {@link TransportKeyService}.
     *
     * <p>The advertised key is always the one actually in use: if networking is on and the key is due for
     * renewal, the renewed key is persisted <em>and the running listener is switched to it</em> before the
     * invitation is signed, so an invited peer never pins a key the listener is not presenting.
     */
    public SignedInvitation createInvitation(char[] vaultPassphrase, List<PeerAddress> addresses, Duration lifetime, Instant now) {
        now = wireTime(now);
        synchronized (lifecycleLock) {
            UnlockedIdentity identity = identityService.unlock(vaultPassphrase);
            TransportKeyMaterial material = syncLiveTransportKey(vaultPassphrase, now);
            IdentityKey transportPublicKey = new IdentityKey(KeyPairs.rawPublicKey(material.keyPair().getPublic()));
            if (peerNetworkService.isEnabled() && !peerNetworkService.currentTransportPublicKey().orElseThrow().equals(transportPublicKey)) {
                throw new IllegalStateException("The running listener is not presenting the current transport key; refusing to advertise it.");
            }
            byte[] nonce = new byte[Invitation.NONCE_LENGTH];
            new SecureRandom().nextBytes(nonce);
            Invitation invitation = new Invitation(identity.publicKey(), transportPublicKey, material.validFrom(),
                    material.validUntil(), addresses, nonce, now, now.plus(lifetime));
            return InvitationCodec.sign(invitation, identity);
        }
    }

    /**
     * Decodes and signature-verifies an invitation blob only; registers no contact and dials nothing
     * (#182: "Parsing an invitation alone must not establish trust"). The caller must separately show the
     * user the identity fingerprint for out-of-band verification before calling
     * {@link #registerPendingContactFromInvitation}.
     *
     * @throws InvitationException malformed, tampered, or an unsupported format version
     */
    public SignedInvitation parseInvitationBase64(String base64) {
        return InvitationCodec.fromBase64(base64);
    }

    /**
     * The explicit, separate step that actually registers a contact from a parsed invitation: still only
     * {@link TrustState#PENDING} (grants nothing) until the user separately calls
     * {@code ContactService.acceptInvitation}. Seeds the local address cache and the pinned transport-key
     * binding from the invitation so the very first connection attempt, once paired, has something to
     * pin against.
     *
     * @throws InvitationException the invitation is expired or not yet valid
     */
    public Contact registerPendingContactFromInvitation(SignedInvitation signed, Instant now) {
        now = wireTime(now);
        Invitation invitation = signed.invitation();
        if (invitation.isExpired(now)) {
            throw new InvitationException(InvitationRejectionReason.EXPIRED, "Invitation expired at " + invitation.expiresAt());
        }
        if (invitation.isNotYetValid(now)) {
            throw new InvitationException(InvitationRejectionReason.NOT_YET_VALID, "Invitation is not valid until " + invitation.issuedAt());
        }
        Contact contact = contactService.registerPendingContact(invitation.identityKey(), "", now);
        for (PeerAddress address : invitation.addresses()) {
            contactService.recordAddressSighting(contact.id(), address, ContactAddressSource.INVITATION, now);
        }
        contactService.recordObservedTransportBinding(contact.id(), invitation.transportKey(),
                invitation.bindingValidFrom(), invitation.bindingValidUntil(), now);
        return contact;
    }

    /**
     * Dials a paired contact at {@code address}, pinned to the last transport key observed for them (from
     * their invitation or an earlier authenticated connection). If the contact has since rotated their
     * key, the pinned dial is refused at the TLS layer and a single <em>rollover</em> attempt follows: the
     * contact must prove, with an identity-signed {@code IDENTITY_BINDING} strictly newer than the pinned
     * one, that their identity authorizes the key they presented, before this device discloses anything.
     * On any authenticated success the contact's verified live binding is persisted (see
     * {@link #onVerifiedBinding}) before the returned future completes, and the address cache is refreshed;
     * on any failure the pinned key is left exactly as it was.
     *
     * @throws IllegalStateException the contact is not paired, or no transport key has ever been observed for them
     */
    public CompletableFuture<DialOutcome> connectToContact(long contactId, PeerAddress address, RetryPolicy policy,
                                                            AtomicBoolean cancelToken) {
        Contact contact = contactService.requireContact(contactId);
        if (contact.trustState() != TrustState.PAIRED) {
            throw new IllegalStateException("Contact " + contactId + " is not paired.");
        }
        ObservedTransportBinding pinned = contactService.lastObservedTransportBinding(contactId)
                .orElseThrow(() -> new IllegalStateException("No known transport key to pin for contact " + contactId + " yet."));
        PinnedBinding pin = new PinnedBinding(pinned.transportKey(), pinned.validFrom());
        return peerNetworkService.connect(contact.identityId(), address, pin, policy, cancelToken)
                .whenComplete((outcome, failure) -> {
                    if (failure == null && outcome.result().authenticated()) {
                        contactService.recordAddressSighting(contactId, address, ContactAddressSource.MANUAL, Instant.now());
                    }
                });
    }

    public void disconnect(IdentityId contactIdentityId) {
        peerNetworkService.disconnect(contactIdentityId);
    }

    public Optional<com.codefit.peer.transport.PeerConnection> activeConnection(IdentityId contactIdentityId) {
        return peerNetworkService.activeConnection(contactIdentityId);
    }

    public Optional<LocalIdentitySummary> currentIdentity() {
        return identityService.currentIdentity();
    }
}

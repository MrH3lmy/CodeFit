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
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.KnownContactLookup;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.PeerNetworkService;
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
 */
public class NetworkingService implements AutoCloseable {
    private final IdentityService identityService;
    private final ContactService contactService;
    private final TransportKeyService transportKeyService;
    private final PeerNetworkService peerNetworkService;
    private volatile LanDiscoveryService lanDiscoveryService;

    public NetworkingService() {
        this(new IdentityService(), new ContactService(), new TransportKeyService());
    }

    NetworkingService(IdentityService identityService, ContactService contactService, TransportKeyService transportKeyService) {
        this.identityService = identityService;
        this.contactService = contactService;
        this.transportKeyService = transportKeyService;
        this.peerNetworkService = new PeerNetworkService(this::lookupContactStatus, event -> { });
    }

    private KnownContactLookup.Status lookupContactStatus(IdentityId candidate) {
        return contactService.listContacts().stream()
                .filter(contact -> contact.identityId().equals(candidate))
                .findFirst()
                .map(contact -> switch (contact.trustState()) {
                    case PENDING -> KnownContactLookup.Status.PENDING;
                    case PAIRED -> KnownContactLookup.Status.PAIRED;
                    case BLOCKED, REMOVED -> KnownContactLookup.Status.BLOCKED_OR_REMOVED;
                })
                .orElse(KnownContactLookup.Status.UNKNOWN);
    }

    public boolean isNetworkingEnabled() {
        return peerNetworkService.isEnabled();
    }

    public Optional<Integer> listeningPort() {
        return peerNetworkService.listeningPort();
    }

    /**
     * Unlocks the identity, ensures a current transport key exists, begins a fresh writer session
     * (protocol §10.1), and starts the listener on {@code listenPort} (0 lets the OS choose). Off by
     * default until this is called; {@link #disableNetworking} fully reverses it.
     */
    public void enableNetworking(char[] vaultPassphrase, int listenPort, Instant now) throws IOException {
        UnlockedIdentity identity = identityService.unlock(vaultPassphrase);
        TransportKeyMaterial material = transportKeyService.ensureCurrent(vaultPassphrase, now);
        long epoch = identityService.beginWriterSession(now);
        peerNetworkService.enable(identity, material, epoch, listenPort);
    }

    public void disableNetworking() {
        disableLanDiscovery();
        peerNetworkService.disable();
    }

    @Override
    public void close() {
        disableNetworking();
    }

    public boolean isLanDiscoveryEnabled() {
        return lanDiscoveryService != null;
    }

    /**
     * Starts optional, opt-in LAN discovery (#182; off unless explicitly called). Requires networking to
     * already be enabled, since announcements advertise this device's live listening port. Discovered
     * paired contacts are recorded into the same address cache {@link #registerPendingContactFromInvitation}
     * seeds, never trusted beyond "here is an address to try dialing."
     *
     * @throws IllegalStateException networking is not enabled, or no local identity exists yet
     */
    public synchronized void enableLanDiscovery() throws IOException {
        if (lanDiscoveryService != null) {
            return;
        }
        int port = peerNetworkService.listeningPort()
                .orElseThrow(() -> new IllegalStateException("Enable networking before starting LAN discovery."));
        LocalIdentitySummary identity = identityService.currentIdentity()
                .orElseThrow(() -> new IllegalStateException("No local identity exists yet."));
        lanDiscoveryService = new LanDiscoveryService(identity.id(), this::currentPairedContactIds, port, this::onDiscovered);
    }

    public synchronized void disableLanDiscovery() {
        if (lanDiscoveryService != null) {
            lanDiscoveryService.close();
            lanDiscoveryService = null;
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
     * Builds a signed, expiring, bounded invitation carrying this device's identity key, current
     * transport-key binding, and the given usable IP-literal addresses (ADR-0001 §4). Does not require
     * networking to already be enabled: an invitation only needs a valid transport key to exist, which
     * this also mints on demand via {@link TransportKeyService}.
     */
    public SignedInvitation createInvitation(char[] vaultPassphrase, List<PeerAddress> addresses, Duration lifetime, Instant now) {
        UnlockedIdentity identity = identityService.unlock(vaultPassphrase);
        TransportKeyMaterial material = transportKeyService.ensureCurrent(vaultPassphrase, now);
        byte[] nonce = new byte[Invitation.NONCE_LENGTH];
        new SecureRandom().nextBytes(nonce);
        IdentityKey transportPublicKey = new IdentityKey(KeyPairs.rawPublicKey(material.keyPair().getPublic()));
        Invitation invitation = new Invitation(identity.publicKey(), transportPublicKey, material.validFrom(),
                material.validUntil(), addresses, nonce, now, now.plus(lifetime));
        return InvitationCodec.sign(invitation, identity);
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
     * Dials a paired contact at {@code address}, pinned to the last transport key observed for them
     * (from their invitation or an earlier live connection). On success, refreshes both the pinned
     * binding and the address cache from the live, just-verified {@code IDENTITY_BINDING} — never from
     * unauthenticated claims.
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
        return peerNetworkService.connect(contact.identityId(), address, pinned.transportKey(), policy, cancelToken)
                .whenComplete((outcome, failure) -> {
                    if (failure == null && outcome.result().authenticated()) {
                        Instant now = Instant.now();
                        contactService.recordAddressSighting(contactId, address, ContactAddressSource.MANUAL, now);
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

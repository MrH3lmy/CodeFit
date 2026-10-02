package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactAddress;
import com.codefit.peer.identity.ContactAddressSource;
import com.codefit.peer.identity.ContactNotFoundException;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.identity.ConsentChangeReason;
import com.codefit.peer.identity.IdentityFingerprint;
import com.codefit.peer.identity.IllegalContactStateException;
import com.codefit.peer.identity.ObservedTransportBinding;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.repository.ConsentChangeEventRepository;
import com.codefit.repository.ContactAddressRepository;
import com.codefit.repository.ContactPermissionRepository;
import com.codefit.repository.ContactRepository;
import com.codefit.repository.ContactTransportBindingRepository;
import com.codefit.repository.PeerIdentityRepository;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Owns contacts, their trust state, and their sharing grants (#181). Discovery alone (a future #182
 * invitation or LAN candidate calling {@link #registerPendingContact}) only ever creates a
 * {@link TrustState#PENDING} contact and never a {@link ContactPermission}: pairing and granting are
 * always two separate, explicit steps, so nothing is ever disclosed to a merely-discovered peer.
 */
public class ContactService {
    /**
     * Serializes every method that reads a contact's current permission revision (or trust state) and
     * then writes it back, so two threads in this process can never both read the same "current"
     * revision and each commit a distinct write believing it holds the next one — the exact lost-update
     * race #192's review reproduced with a {@code CyclicBarrier} forcing two concurrent
     * {@code updatePermissions} calls to read before either wrote. A DB transaction alone does not
     * prevent this: SQLite's write lock is acquired lazily, on the first write statement, so both
     * threads can finish their read before either one blocks. This is in-process coordination only —
     * the documented single-active-writer-per-identity limitation (protocol §10.1) is unchanged, and
     * nothing here claims to coordinate across processes or devices.
     *
     * <p>Package-private so {@code IdentityService}/{@code IdentityBackupService} can hold it for the
     * <em>entire</em> identity-recovery transaction, not just around the
     * {@link #downgradeAllPairedContactsForIdentityReset} call: releasing it as soon as that method
     * returns — before the transaction that calls it actually commits — left a window where
     * {@link #updatePermissions} could read the pre-downgrade PAIRED/revision state from a separate
     * connection (uncommitted writes on another connection aren't visible) and commit a fresh grant
     * after recovery finished, reactivating access for a contact recovery had just downgraded. #192's
     * review reproduced this against real SQLite.
     */
    static final Object PERMISSION_LOCK = new Object();

    /** Makes {@link #recordAuthenticatedTransportBinding}'s read-compare-write atomic against concurrent handshakes. */
    private static final Object TRANSPORT_BINDING_LOCK = new Object();

    private final ContactRepository contactRepository;
    private final ContactPermissionRepository permissionRepository;
    private final ConsentChangeEventRepository outboxRepository;
    private final PeerIdentityRepository identityRepository;
    private final ContactAddressRepository addressRepository;
    private final ContactTransportBindingRepository transportBindingRepository;

    public ContactService() {
        this(new ContactRepository(), new ContactPermissionRepository(), new ConsentChangeEventRepository(),
                new PeerIdentityRepository(), new ContactAddressRepository(), new ContactTransportBindingRepository());
    }

    ContactService(ContactRepository contactRepository, ContactPermissionRepository permissionRepository,
                    ConsentChangeEventRepository outboxRepository, PeerIdentityRepository identityRepository) {
        this(contactRepository, permissionRepository, outboxRepository, identityRepository,
                new ContactAddressRepository(), new ContactTransportBindingRepository());
    }

    ContactService(ContactRepository contactRepository, ContactPermissionRepository permissionRepository,
                    ConsentChangeEventRepository outboxRepository, PeerIdentityRepository identityRepository,
                    ContactAddressRepository addressRepository, ContactTransportBindingRepository transportBindingRepository) {
        this.contactRepository = contactRepository;
        this.permissionRepository = permissionRepository;
        this.outboxRepository = outboxRepository;
        this.identityRepository = identityRepository;
        this.addressRepository = addressRepository;
        this.transportBindingRepository = transportBindingRepository;
    }

    public List<Contact> listContacts() {
        return contactRepository.findAll();
    }

    public Contact requireContact(long contactId) {
        return contactRepository.findById(contactId)
                .orElseThrow(() -> new ContactNotFoundException("No contact with id " + contactId));
    }

    /** The existing contact for this identity key, in any {@link TrustState}, or empty if none exists yet. */
    public Optional<Contact> findByIdentity(IdentityKey identityKey) {
        return contactRepository.findByIdentityId(identityKey.id());
    }

    /**
     * Registers a candidate contact learned out of band (an invitation or LAN announcement, #182) as
     * {@link TrustState#PENDING}. This alone establishes no trust and grants nothing.
     *
     * @throws IllegalContactStateException this identity key is already a known contact
     */
    public Contact registerPendingContact(IdentityKey identityKey, String claimedDisplayName, Instant now) {
        if (contactRepository.findByIdentityId(identityKey.id()).isPresent()) {
            throw new IllegalContactStateException("This identity is already a known contact.");
        }
        String fingerprint = IdentityFingerprint.format(identityKey.id());
        long id = contactRepository.insertPending(identityKey, fingerprint, claimedDisplayName, now);
        return requireContact(id);
    }

    /** @throws IllegalContactStateException the contact is not {@link TrustState#PENDING} */
    public Contact acceptInvitation(long contactId, Instant now) {
        synchronized (PERMISSION_LOCK) {
            Contact contact = requireContact(contactId);
            requireState(contact, TrustState.PENDING);
            contactRepository.updateTrustState(contactId, TrustState.PAIRED, now);
            clearGrantOnPairingWithoutResettingAnExistingRevision(contactId, now);
            return requireContact(contactId);
        }
    }

    /**
     * Rejects a candidate that is currently {@link TrustState#PENDING}. A candidate that was never
     * paired has nothing to preserve and is deleted outright. A contact that reached {@code PENDING}
     * by being downgraded (e.g. {@link #downgradeAllPairedContactsForIdentityReset}) already carries
     * consent-change history #184 still needs to synchronize; deleting that row would cascade-delete
     * it (the schema's {@code ON DELETE CASCADE}), so this case is instead treated as {@link #remove}
     * without recording a redundant event — the downgrade already recorded one.
     *
     * @throws IllegalContactStateException the contact is not {@link TrustState#PENDING}
     */
    public void rejectInvitation(long contactId, Instant now) {
        synchronized (PERMISSION_LOCK) {
            Contact contact = requireContact(contactId);
            requireState(contact, TrustState.PENDING);
            if (outboxRepository.findByContactId(contactId).isEmpty()) {
                contactRepository.delete(contactId);
            } else {
                contactRepository.updateTrustState(contactId, TrustState.REMOVED, now);
            }
        }
    }

    /**
     * Explicitly re-establishes trust with a previously blocked or removed contact under the SAME
     * pinned key. Never restores the previous grant: {@link #acceptInvitation} also starts from
     * nothing, and a re-pair is exactly as deliberate an act as a first pairing.
     *
     * <p>Deliberately leaves an existing {@code contact_permissions} row's revision counter exactly
     * where {@link #block}/{@link #remove} left it, rather than resetting it via
     * {@link ContactPermission#none}: the wire consent object for this (author, recipient) pair is the
     * same one across a block/remove/re-pair cycle (its id is derived only from the two identities, see
     * {@code com.codefit.peer.protocol.ConsentRevision}), so a peer that already holds that object's
     * last revision would reject a reset-to-zero regrant as {@code STALE_REVISION}. #192's review
     * reproduced exactly that rejection against the real {@code EnvelopeAcceptancePolicy}.
     *
     * @throws IllegalContactStateException the contact is not {@link TrustState#BLOCKED} or {@link TrustState#REMOVED}
     */
    public Contact rePair(long contactId, Instant now) {
        synchronized (PERMISSION_LOCK) {
            Contact contact = requireContact(contactId);
            if (contact.trustState() != TrustState.BLOCKED && contact.trustState() != TrustState.REMOVED) {
                throw new IllegalContactStateException("Only a blocked or removed contact can be re-paired.");
            }
            contactRepository.updateTrustState(contactId, TrustState.PAIRED, now);
            clearGrantOnPairingWithoutResettingAnExistingRevision(contactId, now);
            return requireContact(contactId);
        }
    }

    /**
     * Forces the permission row to empty scopes on every transition into {@link TrustState#PAIRED},
     * without ever moving its revision counter backwards. A contact reaching {@code PAIRED} for the
     * very first time (via {@link #acceptInvitation}) has no row yet and gets the {@code revision = 0}
     * starting point; one that already has a row — because it was previously blocked, removed, or
     * downgraded — keeps exactly that revision, forced to empty scopes explicitly rather than merely
     * assumed to already be empty. That assumption briefly went false during the window #192's review
     * found (a stale {@link #updatePermissions} could commit a non-empty grant onto a just-downgraded
     * contact after the downgrade's own write, before {@link #acceptInvitation}/{@link #rePair} next
     * ran) — this method no longer depends on it holding.
     */
    private void clearGrantOnPairingWithoutResettingAnExistingRevision(long contactId, Instant now) {
        long revision = permissionRepository.find(contactId).map(ContactPermission::revision).orElse(0L);
        permissionRepository.save(new ContactPermission(contactId, List.of(), null, null, false, revision, now));
    }

    /**
     * Immediately revokes every granted scope and records a {@link ConsentChangeReason#BLOCKED}
     * outbox event; the service layer denies new reads/publication to this contact from this call
     * onward, regardless of whether or when a peer ever receives the revocation.
     */
    public Contact block(long contactId, boolean requestCacheDeletion, Instant now) {
        synchronized (PERMISSION_LOCK) {
            requireContact(contactId);
            Transactions.run(connection -> {
                revokeAndRecord(connection, contactId, ConsentChangeReason.BLOCKED, requestCacheDeletion, now);
                contactRepository.updateTrustState(connection, contactId, TrustState.BLOCKED, now);
            });
            return requireContact(contactId);
        }
    }

    /** Same immediate effect as {@link #block}, and requires an explicit {@link #rePair} to resume. */
    public Contact remove(long contactId, boolean requestCacheDeletion, Instant now) {
        synchronized (PERMISSION_LOCK) {
            requireContact(contactId);
            Transactions.run(connection -> {
                revokeAndRecord(connection, contactId, ConsentChangeReason.REMOVED, requestCacheDeletion, now);
                contactRepository.updateTrustState(connection, contactId, TrustState.REMOVED, now);
            });
            return requireContact(contactId);
        }
    }

    /**
     * Updates a paired contact's local alias (never the pinned identity or the peer's own claimed
     * display name): purely a local label, so renaming it can never affect identity or trust.
     */
    public void setAlias(long contactId, String alias, Instant now) {
        requireContact(contactId);
        contactRepository.updateAlias(contactId, alias, now);
    }

    /** Caches the peer's latest self-asserted display name; never unique, never the identity. */
    public void updateCachedDisplayName(long contactId, String displayName, Instant now) {
        requireContact(contactId);
        contactRepository.updateCachedDisplayName(contactId, displayName, now);
    }

    public Optional<ContactPermission> permissionsFor(long contactId) {
        requireContact(contactId);
        return permissionRepository.find(contactId);
    }

    /**
     * Replaces the complete sharing grant for a paired contact (never a delta, mirroring
     * {@code ConsentRevision}), recording a {@link ConsentChangeReason#GRANTED} or
     * {@link ConsentChangeReason#REVOKED} outbox event depending on whether the new scope set is empty.
     *
     * @throws IllegalContactStateException the contact is not {@link TrustState#PAIRED}
     */
    public ContactPermission updatePermissions(long contactId, PermissionGrant grant, Instant now) {
        synchronized (PERMISSION_LOCK) {
            Contact contact = requireContact(contactId);
            requireState(contact, TrustState.PAIRED);
            long nextRevision = permissionRepository.find(contactId).map(p -> p.revision() + 1).orElse(1L);
            ContactPermission updated = new ContactPermission(contactId, grant.scopes(), grant.historicalWindowDays(),
                    grant.expiresAt(), grant.allowForwarding(), nextRevision, now);
            ConsentChangeReason reason = updated.scopes().isEmpty() ? ConsentChangeReason.REVOKED : ConsentChangeReason.GRANTED;
            Transactions.run(connection -> {
                permissionRepository.save(connection, updated);
                outboxRepository.record(connection, contactId, updated.scopes(), false, reason, now);
            });
            return updated;
        }
    }

    /**
     * Whether this contact currently holds a valid, unexpired grant for {@code scope}, AND the local
     * identity is not paused. The single enforcement point every future publisher (#183/#184) and
     * every UI control must call before treating a contact as authorized — never inferred from the UI
     * alone. Sharing paused after a restore, rotation, or reset (pending the user's explicit review)
     * denies every contact here, not only ones changed by that event: a previously granted scope must
     * not keep flowing merely because {@code isAuthorizedToPublish} never re-checked identity state.
     */
    public boolean isAuthorizedToPublish(long contactId, SharingScope scope, Instant now) {
        boolean identityReadyToShare = identityRepository.find().map(row -> !row.sharingPaused()).orElse(false);
        if (!identityReadyToShare) {
            return false;
        }
        Optional<Contact> contact = contactRepository.findById(contactId);
        if (contact.isEmpty() || contact.get().trustState() != TrustState.PAIRED) {
            return false;
        }
        return permissionRepository.find(contactId)
                .filter(permission -> !permission.isExpired(now))
                .map(permission -> permission.grants(scope))
                .orElse(false);
    }

    /**
     * The earliest instant of pre-existing history this contact's grant allows sharing, or
     * {@link Optional#empty()} when nothing may be shared at all (not paired, no grant, or expired).
     * A grant with no explicit {@code historicalWindowDays} allows nothing before the grant itself was
     * made — history is never backfilled by default.
     */
    public Optional<Instant> historicalWindowStart(long contactId, SharingScope scope, Instant now) {
        if (!isAuthorizedToPublish(contactId, scope, now)) {
            return Optional.empty();
        }
        ContactPermission permission = permissionRepository.find(contactId).orElseThrow();
        Integer days = permission.historicalWindowDays();
        return Optional.of(days == null ? permission.updatedAt() : now.minus(java.time.Duration.ofDays(days)));
    }

    /**
     * The local reachable-address cache for a contact (#182: "Reconnect from a local contact/address
     * cache"), most-recently-seen first. Never itself a trust decision: dialing any of these still
     * requires the full mutual-TLS + {@code IDENTITY_BINDING} handshake to authenticate whoever answers.
     */
    public List<ContactAddress> knownAddresses(long contactId) {
        requireContact(contactId);
        return addressRepository.findByContactId(contactId);
    }

    /**
     * Records one sighting of {@code address} for {@code contactId} (from an accepted invitation, a
     * manual entry, or an opt-in LAN discovery match) and enforces the bounded cache size, evicting the
     * least-recently-seen address first. A repeat sighting of an address already cached only refreshes
     * its recency; it never grows the cache.
     */
    public void recordAddressSighting(long contactId, PeerAddress address, ContactAddressSource source, Instant now) {
        requireContact(contactId);
        addressRepository.recordSighting(contactId, address, source, now);
        addressRepository.evictOldestBeyond(contactId, ContactAddress.MAX_ADDRESSES_PER_CONTACT);
    }

    /**
     * The last transport key/{@code IDENTITY_BINDING} validity window observed for this contact, if any
     * — what {@code NetworkingService} pins a dial against (#182: "Pin the expected transport key and
     * validate its identity binding and validity"). Never itself sufficient to authenticate a connection.
     */
    public Optional<ObservedTransportBinding> lastObservedTransportBinding(long contactId) {
        requireContact(contactId);
        return transportBindingRepository.find(contactId);
    }

    /**
     * Records the transport key and validity window most recently proven for this contact, either from
     * an accepted invitation's declared binding or from a live, authenticated connection's
     * {@code IDENTITY_BINDING}. Always the single latest observation; an older one is simply overwritten.
     */
    public void recordObservedTransportBinding(long contactId, IdentityKey transportKey, Instant validFrom,
                                                Instant validUntil, Instant now) {
        requireContact(contactId);
        transportBindingRepository.save(new ObservedTransportBinding(contactId, transportKey, validFrom, validUntil, now));
    }

    /** What {@link #recordAuthenticatedTransportBinding} did with a verified live binding. */
    public enum TransportBindingUpdate {
        /** No key was pinned yet; this one now is. */
        RECORDED_FIRST,
        /** Same key as the pin; its validity window was refreshed. */
        REFRESHED,
        /** A different key with a strictly newer identity-signed binding replaced the pin (a rollover). */
        ROTATED,
        /** A different key whose binding is not newer than the pin; the pin is unchanged. */
        IGNORED_STALE
    }

    /**
     * Records the binding of a connection that just <em>fully authenticated</em> (mutual TLS, a signature
     * by this contact's identity key, live-certificate-key match, valid now) as the key to pin for them -
     * but only ever forward: the same key refreshes its window, a different key replaces the pin only if
     * its binding's {@code validFrom} is strictly later than the pinned one's, and anything older is
     * ignored. Callers must pass only bindings the transport has already verified; this method enforces
     * monotonicity, not authenticity.
     */
    public TransportBindingUpdate recordAuthenticatedTransportBinding(long contactId, IdentityKey transportKey,
                                                                       Instant validFrom, Instant validUntil, Instant now) {
        requireContact(contactId);
        synchronized (TRANSPORT_BINDING_LOCK) {
            Optional<ObservedTransportBinding> pinned = transportBindingRepository.find(contactId);
            TransportBindingUpdate outcome;
            if (pinned.isEmpty()) {
                outcome = TransportBindingUpdate.RECORDED_FIRST;
            } else if (pinned.get().transportKey().equals(transportKey)) {
                outcome = TransportBindingUpdate.REFRESHED;
            } else if (validFrom.isAfter(pinned.get().validFrom())) {
                outcome = TransportBindingUpdate.ROTATED;
            } else {
                return TransportBindingUpdate.IGNORED_STALE;
            }
            transportBindingRepository.save(new ObservedTransportBinding(contactId, transportKey, validFrom, validUntil, now));
            return outcome;
        }
    }

    /**
     * Called only by {@code IdentityService.resetIdentityWithoutContinuity} and
     * {@code IdentityBackupService.importBackup} (the different-identity case): without a signed
     * continuity proof, every paired contact must explicitly re-pair before any sharing resumes under
     * the new key (ADR-0001 §1). Takes the caller's own transaction connection rather than opening one
     * of its own, so the contact downgrades and the identity-row replacement that makes them necessary
     * commit or roll back together — #192's review found that committing the downgrades first left
     * contacts permanently stuck PENDING with no identity change to show for it when the later,
     * separate identity write failed.
     */
    void downgradeAllPairedContactsForIdentityReset(Connection connection, Instant now) {
        synchronized (PERMISSION_LOCK) {
            List<Contact> paired = contactRepository.findAll().stream()
                    .filter(contact -> contact.trustState() == TrustState.PAIRED)
                    .toList();
            for (Contact contact : paired) {
                revokeAndRecord(connection, contact.id(), ConsentChangeReason.IDENTITY_RESET, false, now);
                contactRepository.updateTrustState(connection, contact.id(), TrustState.PENDING, now);
            }
        }
    }

    private void revokeAndRecord(Connection connection, long contactId, ConsentChangeReason reason,
                                  boolean requestCacheDeletion, Instant now) {
        long nextRevision = permissionRepository.find(contactId).map(p -> p.revision() + 1).orElse(1L);
        permissionRepository.save(connection, new ContactPermission(contactId, List.of(), null, null, false, nextRevision, now));
        outboxRepository.record(connection, contactId, List.of(), requestCacheDeletion, reason, now);
    }

    private static void requireState(Contact contact, TrustState expected) {
        if (contact.trustState() != expected) {
            throw new IllegalContactStateException(
                    "Contact " + contact.id() + " is " + contact.trustState() + ", expected " + expected + ".");
        }
    }
}

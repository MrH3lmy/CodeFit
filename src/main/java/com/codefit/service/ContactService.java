package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactNotFoundException;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.identity.ConsentChangeReason;
import com.codefit.peer.identity.IdentityFingerprint;
import com.codefit.peer.identity.IllegalContactStateException;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.repository.ConsentChangeEventRepository;
import com.codefit.repository.ContactPermissionRepository;
import com.codefit.repository.ContactRepository;
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
    private final ContactRepository contactRepository;
    private final ContactPermissionRepository permissionRepository;
    private final ConsentChangeEventRepository outboxRepository;
    private final PeerIdentityRepository identityRepository;

    public ContactService() {
        this(new ContactRepository(), new ContactPermissionRepository(), new ConsentChangeEventRepository(),
                new PeerIdentityRepository());
    }

    ContactService(ContactRepository contactRepository, ContactPermissionRepository permissionRepository,
                    ConsentChangeEventRepository outboxRepository, PeerIdentityRepository identityRepository) {
        this.contactRepository = contactRepository;
        this.permissionRepository = permissionRepository;
        this.outboxRepository = outboxRepository;
        this.identityRepository = identityRepository;
    }

    public List<Contact> listContacts() {
        return contactRepository.findAll();
    }

    public Contact requireContact(long contactId) {
        return contactRepository.findById(contactId)
                .orElseThrow(() -> new ContactNotFoundException("No contact with id " + contactId));
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
        Contact contact = requireContact(contactId);
        requireState(contact, TrustState.PENDING);
        contactRepository.updateTrustState(contactId, TrustState.PAIRED, now);
        permissionRepository.save(ContactPermission.none(contactId, now));
        return requireContact(contactId);
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
        Contact contact = requireContact(contactId);
        requireState(contact, TrustState.PENDING);
        if (outboxRepository.findByContactId(contactId).isEmpty()) {
            contactRepository.delete(contactId);
        } else {
            contactRepository.updateTrustState(contactId, TrustState.REMOVED, now);
        }
    }

    /**
     * Explicitly re-establishes trust with a previously blocked or removed contact under the SAME
     * pinned key. Never restores the previous grant: {@link #acceptInvitation} also starts from
     * nothing, and a re-pair is exactly as deliberate an act as a first pairing.
     *
     * @throws IllegalContactStateException the contact is not {@link TrustState#BLOCKED} or {@link TrustState#REMOVED}
     */
    public Contact rePair(long contactId, Instant now) {
        Contact contact = requireContact(contactId);
        if (contact.trustState() != TrustState.BLOCKED && contact.trustState() != TrustState.REMOVED) {
            throw new IllegalContactStateException("Only a blocked or removed contact can be re-paired.");
        }
        contactRepository.updateTrustState(contactId, TrustState.PAIRED, now);
        permissionRepository.save(ContactPermission.none(contactId, now));
        return requireContact(contactId);
    }

    /**
     * Immediately revokes every granted scope and records a {@link ConsentChangeReason#BLOCKED}
     * outbox event; the service layer denies new reads/publication to this contact from this call
     * onward, regardless of whether or when a peer ever receives the revocation.
     */
    public Contact block(long contactId, boolean requestCacheDeletion, Instant now) {
        requireContact(contactId);
        Transactions.run(connection -> {
            revokeAndRecord(connection, contactId, ConsentChangeReason.BLOCKED, requestCacheDeletion, now);
            contactRepository.updateTrustState(connection, contactId, TrustState.BLOCKED, now);
        });
        return requireContact(contactId);
    }

    /** Same immediate effect as {@link #block}, and requires an explicit {@link #rePair} to resume. */
    public Contact remove(long contactId, boolean requestCacheDeletion, Instant now) {
        requireContact(contactId);
        Transactions.run(connection -> {
            revokeAndRecord(connection, contactId, ConsentChangeReason.REMOVED, requestCacheDeletion, now);
            contactRepository.updateTrustState(connection, contactId, TrustState.REMOVED, now);
        });
        return requireContact(contactId);
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
     * Called only by {@code IdentityService.resetIdentityWithoutContinuity}: without a signed
     * continuity proof, every paired contact must explicitly re-pair before any sharing resumes under
     * the new key (ADR-0001 §1).
     */
    void downgradeAllPairedContactsForIdentityReset(Instant now) {
        List<Contact> paired = contactRepository.findAll().stream()
                .filter(contact -> contact.trustState() == TrustState.PAIRED)
                .toList();
        if (paired.isEmpty()) {
            return;
        }
        Transactions.run(connection -> {
            for (Contact contact : paired) {
                revokeAndRecord(connection, contact.id(), ConsentChangeReason.IDENTITY_RESET, false, now);
                contactRepository.updateTrustState(connection, contact.id(), TrustState.PENDING, now);
            }
        });
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

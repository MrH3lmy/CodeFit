package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.ConsentRevision;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MessageBody;
import com.codefit.peer.protocol.MessageType;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.protocol.Tombstone;
import com.codefit.peer.protocol.TombstoneReason;
import com.codefit.peer.sync.PublicationOutboxEntry;
import com.codefit.repository.PublicationOutboxRepository;
import com.codefit.repository.PublicationWireStateRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the publication outbox (#184): which logical objects a learner has explicitly approved for
 * ongoing sharing with one contact, and computing - fresh, every session - the bounded set of signed
 * envelopes currently eligible to send that contact. "Eligible" is always recomputed from current
 * state (the outbox's approvals, {@code ContactService}'s current grant) rather than tracked as a
 * pending queue: an object's current signed form is cheap to rebuild and, when unchanged, byte-
 * identical to what was signed before (see {@link #signControlEnvelope}), so resending it is always
 * harmless - there is no "already placed on a socket" state to track on this side at all. See {@code
 * PublicationOutboxRepository}'s class javadoc for why {@code last_synced_*} is optimistic/UI-only
 * and never gates what gets resent.
 */
public class PeerSyncOutboxService {

    /** Hard per-call cap, independent of any caller-supplied bound, so a single session can never enumerate unlimited history. */
    private static final int MAX_ENVELOPES_PER_BATCH = 200;
    private static final Duration CONTROL_ENVELOPE_LIFETIME = Duration.ofDays(90);

    /**
     * Process-wide cache of the one signed {@code ConsentRevision}/{@code Tombstone} envelope built
     * per (recipient, objectId), rebuilt only when the body's fingerprint actually changed - the same
     * idempotent-resend pattern {@code SnapshotPublicationService.PublishedEnvelopeCache} uses for
     * progress/preparation bodies. Without this, resending unchanged content would mint a new {@code
     * sequence} (from the shared {@link WriterSessionSequencer}) every time while keeping the SAME
     * revision (from {@link PublicationWireStateRepository}, which reuses a revision for unchanged
     * content) - producing a different message id at a revision no newer than the one already held,
     * which a receiver would reject as {@code STALE_REVISION} instead of accepting it as the harmless
     * {@code DUPLICATE} a true resend should be. Static, not an instance field, for the same reason
     * {@link WriterSessionSequencer} is static: a fresh {@code PeerSyncOutboxService} is cheap to
     * construct and must not reset this bookkeeping.
     */
    private static final Map<CacheKey, SignedEnvelope> CONTROL_ENVELOPE_CACHE = new ConcurrentHashMap<>();

    private record CacheKey(IdentityId recipient, ObjectId objectId) {
    }

    private final PublicationOutboxRepository outboxRepository;
    private final PublicationWireStateRepository wireStateRepository;
    private final ContactService contactService;
    private final SnapshotPublicationService publicationService;

    public PeerSyncOutboxService() {
        this(new PublicationOutboxRepository(), new PublicationWireStateRepository(), new ContactService(),
                new SnapshotPublicationService());
    }

    PeerSyncOutboxService(PublicationOutboxRepository outboxRepository, PublicationWireStateRepository wireStateRepository,
                           ContactService contactService, SnapshotPublicationService publicationService) {
        this.outboxRepository = outboxRepository;
        this.wireStateRepository = wireStateRepository;
        this.contactService = contactService;
        this.publicationService = publicationService;
    }

    /** Approves one window for ongoing sharing with this contact. Idempotent. */
    public PublicationOutboxEntry approveProgressSummary(long contactId, ComparisonWindow window, Instant now) {
        return outboxRepository.approve(contactId, MessageType.PROGRESS_SUMMARY, encodeWindow(window), now);
    }

    /** Approves one preparation profile for ongoing sharing with this contact. Idempotent. */
    public PublicationOutboxEntry approvePreparationSnapshot(long contactId, String profileId, Instant now) {
        return outboxRepository.approve(contactId, MessageType.PREPARATION_SNAPSHOT, profileId, now);
    }

    public List<PublicationOutboxEntry> approvedFor(long contactId) {
        return outboxRepository.findByContact(contactId);
    }

    /**
     * The bounded set of envelopes currently worth sending this contact: a fresh {@code
     * CONSENT_REVISION} reflecting the current grant (if any), every outbox object this contact is
     * still authorized for, and a {@code Tombstone} for every object this contact previously had
     * (optimistically) but is no longer authorized for. Capped at {@link #MAX_ENVELOPES_PER_BATCH}.
     *
     * @return the envelopes to send, each paired with the outbox row id to {@code markSynced} after
     *         a session that sent it completes cleanly (empty for the consent/tombstone entries, which
     *         have no outbox row of their own)
     */
    public List<Batched> eligibleEnvelopesFor(long contactId, UnlockedIdentity identity, long writerEpoch, Instant now) {
        Contact contact = contactService.requireContact(contactId);
        List<Batched> batch = new ArrayList<>();

        Optional<com.codefit.peer.identity.ContactPermission> permission = contactService.permissionsFor(contactId);
        if (permission.isPresent() && batch.size() < MAX_ENVELOPES_PER_BATCH) {
            ObjectId consentObjectId = ConsentRevision.objectIdFor(identity.publicKey().id(), contact.identityId());
            SignedEnvelope envelope = signControlEnvelope(identity, writerEpoch, contact.identityId(), consentObjectId,
                    new ConsentRevision(permission.get().scopes()), now);
            batch.add(new Batched(envelope, Optional.empty()));
        }

        for (PublicationOutboxEntry entry : outboxRepository.findByContact(contactId)) {
            if (batch.size() >= MAX_ENVELOPES_PER_BATCH) {
                break;
            }
            buildEntry(contact, entry, identity, writerEpoch, now).ifPresent(batch::add);
        }
        return batch;
    }

    private Optional<Batched> buildEntry(Contact contact, PublicationOutboxEntry entry, UnlockedIdentity identity,
                                          long writerEpoch, Instant now) {
        try {
            if (entry.messageType() == MessageType.PROGRESS_SUMMARY) {
                ComparisonWindow window = decodeWindow(entry.logicalKey());
                SignedEnvelope envelope = publicationService.publishProgressSummary(entry.contactId(), identity, writerEpoch, window);
                return Optional.of(new Batched(envelope, Optional.of(entry.id())));
            } else if (entry.messageType() == MessageType.PREPARATION_SNAPSHOT) {
                SignedEnvelope envelope = publicationService.publishPreparationSnapshot(entry.contactId(), identity, writerEpoch,
                        entry.logicalKey());
                return Optional.of(new Batched(envelope, Optional.of(entry.id())));
            }
            return Optional.empty();
        } catch (SnapshotPublicationService.NotAuthorizedToPublishException notAuthorized) {
            // No longer authorized for this object. A tombstone is owed only if we believe the peer
            // already has some copy of it (an optimistic hint, never load-bearing for correctness -
            // worst case we send one extra harmless tombstone for an object the peer never actually got).
            if (entry.lastSyncedRevision() == null) {
                return Optional.empty();
            }
            ObjectId objectId = entry.messageType() == MessageType.PROGRESS_SUMMARY
                    ? SnapshotPublicationService.progressSummaryObjectId(identity.publicKey().id(), contact.identityId(), decodeWindow(entry.logicalKey()))
                    : SnapshotPublicationService.preparationSnapshotObjectId(identity.publicKey().id(), contact.identityId(), entry.logicalKey());
            SignedEnvelope tombstone = signControlEnvelope(identity, writerEpoch, contact.identityId(), objectId,
                    new Tombstone(entry.messageType(), TombstoneReason.REVOKED, true), now);
            return Optional.of(new Batched(tombstone, Optional.empty()));
        }
    }

    /** Optimistic, UI-facing hint only - see {@code PublicationOutboxRepository}. */
    public void markSynced(long outboxEntryId, long revision, Instant now) {
        outboxRepository.markSynced(outboxEntryId, revision, now);
    }

    /**
     * Signs a {@code ConsentRevision} or {@code Tombstone}, reusing the exact previous signed
     * envelope - same sequence, same signature - whenever the body is unchanged from last time (see
     * {@link #CONTROL_ENVELOPE_CACHE}'s javadoc for why that is required for correctness, not just an
     * optimization). The revision itself comes from {@link PublicationWireStateRepository}, which
     * reuses a revision for an unchanged fingerprint and only bumps it when the content genuinely
     * changed - mirroring exactly how {@code SnapshotPublicationService} already treats progress/
     * preparation bodies.
     */
    private SignedEnvelope signControlEnvelope(UnlockedIdentity identity, long writerEpoch, IdentityId recipient,
                                                ObjectId objectId, MessageBody body, Instant now) {
        CacheKey key = new CacheKey(recipient, objectId);
        SignedEnvelope cached = CONTROL_ENVELOPE_CACHE.get(key);
        byte[] fingerprint = fingerprint(body);
        if (cached != null && cached.body().equals(body) && cached.header().author().equals(identity.publicKey())) {
            return cached;
        }
        long revision = wireStateRepository.nextRevision(objectId, fingerprint);
        Instant createdAt = Instant.ofEpochMilli(now.toEpochMilli());
        EnvelopeHeader header = new EnvelopeHeader(0, identity.publicKey(), objectId, writerEpoch,
                WriterSessionSequencer.next(writerEpoch), revision, createdAt, createdAt.plus(CONTROL_ENVELOPE_LIFETIME),
                Audience.direct(List.of(recipient)));
        Envelope envelope = new Envelope(header, body);
        SignedEnvelope signed = new SignedEnvelope(envelope, identity.sign(envelope.signingBytes()));
        CONTROL_ENVELOPE_CACHE.put(key, signed);
        return signed;
    }

    private static byte[] fingerprint(MessageBody body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body.encodeBody());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform.", e);
        }
    }

    static String encodeWindow(ComparisonWindow window) {
        return window.kind().name() + "|" + window.localStartDate().toEpochDay() + "|" + window.zoneId() + "|"
                + (window.weekStart() == null ? "" : window.weekStart().getValue());
    }

    static ComparisonWindow decodeWindow(String logicalKey) {
        String[] parts = logicalKey.split("\\|", -1);
        LocalDate date = LocalDate.ofEpochDay(Long.parseLong(parts[1]));
        ZoneId zone = ZoneId.of(parts[2]);
        if ("DAY".equals(parts[0])) {
            return ComparisonWindow.day(date, zone);
        }
        return ComparisonWindow.week(date, zone, DayOfWeek.of(Integer.parseInt(parts[3])));
    }

    /** One envelope to send, paired with the outbox row to mark synced afterward (empty for consent/tombstone entries). */
    public record Batched(SignedEnvelope envelope, Optional<Long> outboxEntryId) {
    }
}

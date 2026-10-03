package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.protocol.ConsentRevision;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MessageBody;
import com.codefit.peer.protocol.MessageId;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.ProtocolVersion;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.protocol.SocialProfileCard;
import com.codefit.peer.protocol.Tombstone;
import com.codefit.peer.sync.SyncOutcome;
import com.codefit.repository.PeerPreparationSnapshotRepository;
import com.codefit.repository.PeerProgressSummaryRepository;
import com.codefit.repository.PeerSocialProfileCardRepository;
import com.codefit.repository.PeerSyncAuthorStateRepository;
import com.codefit.repository.PeerSyncConsentRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/**
 * Validates and applies one received {@link SignedEnvelope} (#184). This is the durable,
 * SQLite-backed equivalent of {@code com.codefit.peer.protocol.EnvelopeAcceptancePolicy} +
 * {@code AuthorReplayState} - that pair is deliberately in-memory only (its mutators are
 * package-private, by #180's own design: see {@code AuthorReplayState}'s javadoc and {@code
 * docs/p2p/protocol-v1.md} §10, "Replay state is in memory in #180 and is persisted in SQLite by
 * #184") - reproducing the exact same ordered rule set from §10, plus the two checks that
 * document explicitly assigns to the sync layer: whether the sender is even a known, paired
 * contact, and whether their own latest {@code CONSENT_REVISION} to this receiver grants the body's
 * required scope.
 *
 * <p>Every check and every write for one envelope run inside one transaction
 * ({@link Transactions#run}): acceptance is evaluated against whatever the database durably holds
 * right now, the accepted body is persisted into the peer inbox (or a tombstone/consent effect is
 * applied), and the replay-state tables (author's highest epoch, the accepted message/slot, the
 * object's latest version) are updated — all together, or none of it. A crash or rollback between
 * "decided to accept" and "committed" leaves the previous state completely intact, so the exact same
 * envelope arriving again later is evaluated completely fresh and reaches the identical decision —
 * nothing is lost, and nothing is double-applied.
 */
public class PeerSyncIngestService {

    private final ContactService contactService;
    private final PeerSyncAuthorStateRepository authorStateRepository;
    private final PeerSyncConsentRepository consentRepository;
    private final PeerProgressSummaryRepository progressSummaryRepository;
    private final PeerPreparationSnapshotRepository preparationSnapshotRepository;
    private final PeerSocialProfileCardRepository socialProfileCardRepository;

    public PeerSyncIngestService() {
        this(new ContactService(), new PeerSyncAuthorStateRepository(), new PeerSyncConsentRepository(),
                new PeerProgressSummaryRepository(), new PeerPreparationSnapshotRepository(), new PeerSocialProfileCardRepository());
    }

    PeerSyncIngestService(ContactService contactService, PeerSyncAuthorStateRepository authorStateRepository,
                           PeerSyncConsentRepository consentRepository, PeerProgressSummaryRepository progressSummaryRepository,
                           PeerPreparationSnapshotRepository preparationSnapshotRepository,
                           PeerSocialProfileCardRepository socialProfileCardRepository) {
        this.contactService = contactService;
        this.authorStateRepository = authorStateRepository;
        this.consentRepository = consentRepository;
        this.progressSummaryRepository = progressSummaryRepository;
        this.preparationSnapshotRepository = preparationSnapshotRepository;
        this.socialProfileCardRepository = socialProfileCardRepository;
    }

    /**
     * @param authenticatedSender the identity the transport layer has already authenticated this
     *                            connection as belonging to (#182's TLS-pinned contact); this is
     *                            cross-checked against the envelope's own signed author so a payload
     *                            genuinely authored by someone else cannot be credited to whoever
     *                            happens to be connected right now
     * @param myIdentityId        this receiver's own identity, checked against the envelope's audience
     */
    public SyncOutcome ingest(IdentityId authenticatedSender, IdentityId myIdentityId, SignedEnvelope envelope, Instant now) {
        // Verified again here, as the literal first check, never assuming a caller already verified it
        // (the pattern PR #194 established for SignedInvitation at every other service boundary).
        if (!envelope.verifySignature()) {
            return SyncOutcome.BAD_SIGNATURE;
        }
        IdentityId claimedAuthor = envelope.header().author().id();
        if (!claimedAuthor.equals(authenticatedSender)) {
            return SyncOutcome.SENDER_MISMATCH;
        }
        Contact contact = contactService.findByIdentity(envelope.header().author()).orElse(null);
        if (contact == null || contact.trustState() != TrustState.PAIRED) {
            return SyncOutcome.UNKNOWN_AUTHOR;
        }
        if (!envelope.header().audience().includes(myIdentityId)) {
            return SyncOutcome.UNAUTHORIZED_AUDIENCE;
        }
        if (envelope.header().createdAt().isAfter(now.plusMillis(ProtocolVersion.MAX_CLOCK_SKEW_MILLIS))) {
            return SyncOutcome.NOT_YET_VALID;
        }
        if (!envelope.header().expiresAt().isAfter(now)) {
            return SyncOutcome.EXPIRED;
        }

        SyncOutcome[] result = new SyncOutcome[1];
        Transactions.run(connection -> {
            try {
                result[0] = ingestWithinTransaction(connection, claimedAuthor, envelope, now);
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to ingest peer sync envelope", exception);
            }
        });
        return result[0];
    }

    private SyncOutcome ingestWithinTransaction(Connection connection, IdentityId author, SignedEnvelope envelope, Instant now)
            throws SQLException {
        var header = envelope.header();
        MessageId messageId = envelope.messageId();

        if (authorStateRepository.hasAccepted(connection, author, messageId)) {
            return SyncOutcome.DUPLICATE;
        }
        if (header.epoch() < authorStateRepository.highestEpoch(connection, author)) {
            return SyncOutcome.STALE_EPOCH;
        }
        var slotOccupant = authorStateRepository.slotOccupant(connection, author, header.epoch(), header.sequence());
        if (slotOccupant.isPresent() && !slotOccupant.get().equals(messageId)) {
            return SyncOutcome.FORKED;
        }
        var heldVersion = authorStateRepository.objectVersion(connection, author, header.objectId());
        if (heldVersion.isPresent() && heldVersion.get().isNewerThanOrEqualTo(header.epoch(), header.revision())) {
            return heldVersion.get().tombstone() ? SyncOutcome.TOMBSTONED : SyncOutcome.STALE_REVISION;
        }

        MessageBody body = envelope.body();
        SharingScope requiredScope = body.requiredScope();
        if (requiredScope != null) {
            var grantedScopes = consentRepository.scopesFor(connection, author);
            if (grantedScopes.isEmpty() || !grantedScopes.get().contains(requiredScope)) {
                return SyncOutcome.SCOPE_NOT_GRANTED;
            }
        }

        boolean tombstone = applyAccepted(connection, author, header.objectId(), header.epoch(), header.revision(), body, now);
        authorStateRepository.recordAccepted(connection, author, messageId, header.epoch(), header.sequence(),
                header.objectId(), header.revision(), tombstone, header.expiresAt(), now);
        return SyncOutcome.ACCEPTED;
    }

    /** @return whether the accepted body is itself a tombstone, for the replay-state's own bookkeeping */
    private boolean applyAccepted(Connection connection, IdentityId author, ObjectId objectId, long epoch, long revision,
                                   MessageBody body, Instant now) throws SQLException {
        switch (body) {
            case ProgressSummary summary -> progressSummaryRepository.upsert(connection, author, objectId, epoch, revision,
                    summary, now);
            case PreparationSnapshot snapshot -> preparationSnapshotRepository.upsert(connection, author, objectId, epoch,
                    revision, snapshot, now);
            case SocialProfileCard card -> socialProfileCardRepository.upsert(connection, author, objectId, epoch, revision,
                    card, now);
            case ConsentRevision consent -> consentRepository.save(connection, author, consent.scopes(), epoch, revision, now);
            case Tombstone tombstone -> {
                if (tombstone.requestCacheDeletion()) {
                    purgeCachedCopy(connection, author, objectId, tombstone.targetType());
                }
                return true;
            }
            case IdentityBinding binding -> {
                // Transport-key pinning already happens at the #182 handshake layer; nothing further
                // to cache here - only the replay-state bookkeeping (recorded by the caller) matters.
            }
        }
        return false;
    }

    private void purgeCachedCopy(Connection connection, IdentityId author, ObjectId objectId,
                                  com.codefit.peer.protocol.MessageType targetType) throws SQLException {
        switch (targetType) {
            case PROGRESS_SUMMARY -> progressSummaryRepository.delete(connection, author, objectId);
            case PREPARATION_SNAPSHOT -> preparationSnapshotRepository.delete(connection, author, objectId);
            case SOCIAL_PROFILE_CARD -> socialProfileCardRepository.delete(connection, author, objectId);
            default -> { }
        }
    }
}

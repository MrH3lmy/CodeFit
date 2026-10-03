package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.ConsentRevision;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeCodec;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.MessageType;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.protocol.TimestampBasis;
import com.codefit.peer.protocol.Tombstone;
import com.codefit.peer.protocol.TombstoneReason;
import com.codefit.peer.sync.SyncOutcome;
import com.codefit.repository.PeerProgressSummaryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #184: the durable acceptance/replay pipeline ({@code PeerSyncIngestService}), exercised directly
 * against hand-built {@link SignedEnvelope}s rather than over a real socket - the production-path
 * networking loop is covered separately by the two-instance end-to-end test. This is where the exact
 * §10 rule order (duplicate, stale epoch, forked, stale/tombstoned revision) and the two #184-owned
 * checks (unknown author, scope not granted) are proven against real SQLite persistence, including
 * across what simulates a restart (a fresh service/repository instance reading only what was
 * committed).
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerSyncIngestServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T12:00:00Z");

    private final IdentityService identityService = new IdentityService();
    private final ContactService contactService = new ContactService();
    private final PeerSyncIngestService ingestService = new PeerSyncIngestService();
    private final PeerProgressSummaryRepository progressSummaryRepository = new PeerProgressSummaryRepository();

    private IdentityId myIdentityId;
    private KeyPair peerKeyPair;
    private IdentityKey peerIdentityKey;
    private IdentityId peerIdentityId;
    private long peerContactId;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        myIdentityId = identityService.createIdentity("vault-pass".toCharArray(), NOW.minusSeconds(10)).id();
        identityService.resumeSharingAfterReview();

        peerKeyPair = KeyPairs.generate();
        peerIdentityKey = new IdentityKey(KeyPairs.rawPublicKey(peerKeyPair.getPublic()));
        peerIdentityId = peerIdentityKey.id();
        Contact pending = contactService.registerPendingContact(peerIdentityKey, "Peer", NOW.minusSeconds(5));
        Contact paired = contactService.acceptInvitation(pending.id(), NOW.minusSeconds(4));
        peerContactId = paired.id();
    }

    private SignedEnvelope sign(Envelope envelope) {
        return EnvelopeCodec.sign(envelope, peerKeyPair.getPrivate());
    }

    private Audience toMe() {
        return Audience.direct(List.of(myIdentityId));
    }

    private ObjectId randomObjectId(int seed) {
        byte[] bytes = new byte[ObjectId.LENGTH];
        bytes[0] = (byte) seed;
        bytes[ObjectId.LENGTH - 1] = (byte) (seed >>> 8);
        return new ObjectId(bytes);
    }

    private ProgressSummary summaryBody() {
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), ZoneId.of("UTC"));
        return new ProgressSummary(window, window.end(), List.of(
                new MetricValue("problem.attempts", 1, MetricUnit.COUNT, MetricAvailability.MEASURED, 3, 3,
                        MetricProvenance.LOCAL_RECORD, TimestampBasis.LEGACY_SQLITE_UTC)));
    }

    private SignedEnvelope progressSummaryEnvelope(ObjectId objectId, long epoch, long sequence, long revision) {
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, objectId, epoch, sequence, revision,
                NOW, NOW.plus(Duration.ofDays(30)), toMe());
        return sign(new Envelope(header, summaryBody()));
    }

    /** Grants myself (the receiver) the DAILY_SUMMARY scope from the peer's own persisted consent cache. */
    private void grantDailySummaryFromPeer() {
        ConsentRevision consent = new ConsentRevision(List.of(SharingScope.DAILY_SUMMARY));
        ObjectId consentObjectId = ConsentRevision.objectIdFor(peerIdentityId, myIdentityId);
        // A distinct, high sequence number so this consent envelope's (epoch, sequence) slot never
        // collides with the test's own progress-summary sequence numbers (1, 2, 3, ...).
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, consentObjectId, 1, 1000, 1,
                NOW.minusSeconds(2), NOW.plus(Duration.ofDays(30)), toMe());
        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId, sign(new Envelope(header, consent)), NOW.minusSeconds(1));
        assertEquals(SyncOutcome.ACCEPTED, outcome, "the consent revision itself must be accepted before it can grant anything");
    }

    @Test
    void grantedProgressSummaryIsAcceptedAndCached() {
        grantDailySummaryFromPeer();
        ObjectId objectId = randomObjectId(1);

        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId, progressSummaryEnvelope(objectId, 1, 1, 1), NOW);

        assertEquals(SyncOutcome.ACCEPTED, outcome);
        Optional<com.codefit.peer.sync.PeerProgressSummary> cached = progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, objectId);
        assertTrue(cached.isPresent());
        assertEquals(1L, cached.get().revision());
    }

    @Test
    void withoutAGrantedScopeTheSummaryIsRejectedAndNeverCached() {
        ObjectId objectId = randomObjectId(2);

        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId, progressSummaryEnvelope(objectId, 1, 1, 1), NOW);

        assertEquals(SyncOutcome.SCOPE_NOT_GRANTED, outcome);
        assertTrue(progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, objectId).isEmpty());
    }

    @Test
    void theExactSameEnvelopeResentIsAnIdempotentDuplicateNoOp() {
        grantDailySummaryFromPeer();
        ObjectId objectId = randomObjectId(3);
        SignedEnvelope envelope = progressSummaryEnvelope(objectId, 1, 1, 1);

        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId, envelope, NOW));
        assertEquals(SyncOutcome.DUPLICATE, ingestService.ingest(peerIdentityId, myIdentityId, envelope, NOW),
                "resending the identical signed envelope must be a harmless, idempotent no-op");
    }

    @Test
    void aNewerRevisionSupersedesAnOlderOneForTheSameObject() {
        grantDailySummaryFromPeer();
        ObjectId objectId = randomObjectId(4);
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(objectId, 1, 1, 1), NOW));
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(objectId, 1, 2, 2), NOW));

        Optional<com.codefit.peer.sync.PeerProgressSummary> cached = progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, objectId);
        assertEquals(2L, cached.orElseThrow().revision(), "the cache holds only the latest revision");
    }

    @Test
    void anOlderRevisionArrivingAfterANewerOneIsRejectedAsStaleNeverRollingBack() {
        grantDailySummaryFromPeer();
        ObjectId objectId = randomObjectId(5);
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(objectId, 1, 1, 2), NOW));

        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(objectId, 1, 2, 1), NOW);

        assertEquals(SyncOutcome.STALE_REVISION, outcome);
        assertEquals(2L, progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, objectId).orElseThrow().revision(),
                "the rollback attempt must never overwrite the newer cached revision");
    }

    @Test
    void aLowerEpochThanAlreadyAcceptedIsRejectedAsStale() {
        grantDailySummaryFromPeer();
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(randomObjectId(6), 5, 1, 1), NOW));

        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(randomObjectId(7), 3, 1, 1), NOW);

        assertEquals(SyncOutcome.STALE_EPOCH, outcome);
    }

    @Test
    void twoDifferentMessagesClaimingTheSameEpochAndSequenceAreForked() {
        grantDailySummaryFromPeer();
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(randomObjectId(8), 1, 1, 1), NOW));

        // A different object, but the SAME (epoch, sequence) slot the first message already claimed.
        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(randomObjectId(9), 1, 1, 1), NOW);

        assertEquals(SyncOutcome.FORKED, outcome);
    }

    @Test
    void aTombstoneWithCacheDeletionPurgesTheCacheButReplayOfTheOldRevisionNeverResurrectsIt() {
        grantDailySummaryFromPeer();
        ObjectId objectId = randomObjectId(10);
        SignedEnvelope original = progressSummaryEnvelope(objectId, 1, 1, 5);
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId, original, NOW));
        assertTrue(progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, objectId).isPresent());

        EnvelopeHeader tombstoneHeader = new EnvelopeHeader(0, peerIdentityKey, objectId, 1, 2, 6,
                NOW, NOW.plus(Duration.ofDays(30)), toMe());
        Tombstone tombstone = new Tombstone(MessageType.PROGRESS_SUMMARY, TombstoneReason.REVOKED, true);
        SyncOutcome tombstoneOutcome = ingestService.ingest(peerIdentityId, myIdentityId, sign(new Envelope(tombstoneHeader, tombstone)), NOW);
        assertEquals(SyncOutcome.ACCEPTED, tombstoneOutcome);
        assertTrue(progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, objectId).isEmpty(),
                "requestCacheDeletion must purge the cached copy");

        // A malicious or stale peer later replays the ORIGINAL, pre-revocation revision 5. It must not
        // resurrect, even against a brand-new PeerSyncIngestService instance (simulating a restart:
        // nothing here is in-memory, everything the decision depends on was durably committed above).
        PeerSyncIngestService restarted = new PeerSyncIngestService();
        SyncOutcome replay = restarted.ingest(peerIdentityId, myIdentityId, original, NOW.plusSeconds(1));
        assertEquals(SyncOutcome.DUPLICATE, replay, "the exact same signed envelope replayed is still just a harmless duplicate");

        // A DIFFERENT, never-before-seen envelope at or below the tombstone cutoff must be TOMBSTONED, not resurrect either.
        SignedEnvelope anotherOldRevision = progressSummaryEnvelope(objectId, 1, 3, 4);
        SyncOutcome belowCutoff = restarted.ingest(peerIdentityId, myIdentityId, anotherOldRevision, NOW.plusSeconds(1));
        assertEquals(SyncOutcome.TOMBSTONED, belowCutoff);
        assertTrue(progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, objectId).isEmpty(),
                "still purged - the replay must not bring the cache back");
    }

    @Test
    void anUnpairedOrUnknownAuthorIsRejectedEvenWithAValidSignatureAndAudience() {
        KeyPair strangerKeyPair = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerKeyPair.getPublic()));
        EnvelopeHeader header = new EnvelopeHeader(0, strangerKey, randomObjectId(11), 1, 1, 1,
                NOW, NOW.plus(Duration.ofDays(30)), toMe());
        SignedEnvelope envelope = EnvelopeCodec.sign(new Envelope(header, summaryBody()), strangerKeyPair.getPrivate());

        SyncOutcome outcome = ingestService.ingest(strangerKey.id(), myIdentityId, envelope, NOW);

        assertEquals(SyncOutcome.UNKNOWN_AUTHOR, outcome);
    }

    @Test
    void aConnectionAuthenticatedAsOnePeerCannotCreditAnEnvelopeGenuinelySignedBySomeoneElse() {
        grantDailySummaryFromPeer();
        SignedEnvelope genuinelyFromPeer = progressSummaryEnvelope(randomObjectId(12), 1, 1, 1);

        // The transport layer authenticated this connection as some OTHER identity, not the peer -
        // must never credit the peer's own genuinely-signed envelope to a different connection.
        SyncOutcome outcome = ingestService.ingest(myIdentityId, myIdentityId, genuinelyFromPeer, NOW);

        assertEquals(SyncOutcome.SENDER_MISMATCH, outcome);
    }

    @Test
    void anEnvelopeNotAddressedToMeIsRejectedAsUnauthorizedAudience() {
        grantDailySummaryFromPeer();
        IdentityId someoneElse = new IdentityId(new byte[32]);
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, randomObjectId(13), 1, 1, 1,
                NOW, NOW.plus(Duration.ofDays(30)), Audience.direct(List.of(someoneElse)));

        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId, sign(new Envelope(header, summaryBody())), NOW);

        assertEquals(SyncOutcome.UNAUTHORIZED_AUDIENCE, outcome);
    }

    @Test
    void anExpiredEnvelopeIsRejected() {
        grantDailySummaryFromPeer();
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, randomObjectId(14), 1, 1, 1,
                NOW.minusSeconds(120), NOW.minusSeconds(60), toMe());

        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId, sign(new Envelope(header, summaryBody())), NOW);

        assertEquals(SyncOutcome.EXPIRED, outcome);
    }

    @Test
    void anEnvelopeCreatedTooFarInTheFutureIsNotYetValid() {
        grantDailySummaryFromPeer();
        Instant farFuture = NOW.plus(Duration.ofHours(1));
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, randomObjectId(15), 1, 1, 1,
                farFuture, farFuture.plus(Duration.ofDays(30)), toMe());

        SyncOutcome outcome = ingestService.ingest(peerIdentityId, myIdentityId, sign(new Envelope(header, summaryBody())), NOW);

        assertEquals(SyncOutcome.NOT_YET_VALID, outcome);
    }

    @Test
    void revokingTheGrantAfterAcceptingOneSummaryStopsTheNextOneButNeverTouchesTheCachedCopy() {
        grantDailySummaryFromPeer();
        ObjectId firstObjectId = randomObjectId(16);
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(firstObjectId, 1, 1, 1), NOW));

        // The peer's own consent to me is revoked (a fresh, empty ConsentRevision at a higher revision).
        ConsentRevision revoked = new ConsentRevision(List.of());
        ObjectId consentObjectId = ConsentRevision.objectIdFor(peerIdentityId, myIdentityId);
        EnvelopeHeader revokeHeader = new EnvelopeHeader(0, peerIdentityKey, consentObjectId, 1, 2, 2,
                NOW, NOW.plus(Duration.ofDays(30)), toMe());
        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerIdentityId, myIdentityId, sign(new Envelope(revokeHeader, revoked)), NOW));

        SyncOutcome afterRevoke = ingestService.ingest(peerIdentityId, myIdentityId,
                progressSummaryEnvelope(randomObjectId(17), 1, 3, 1), NOW);
        assertEquals(SyncOutcome.SCOPE_NOT_GRANTED, afterRevoke, "a new summary must be rejected once the peer's own consent is revoked");
        assertTrue(progressSummaryRepository.findByAuthorAndObjectId(peerIdentityId, firstObjectId).isPresent(),
                "revoking future consent must never retroactively delete what was already validly cached (that needs an explicit tombstone)");
    }

    @Test
    void acceptingPeerDataNeverTouchesThisDevicesOwnContactOrPermissionState() {
        grantDailySummaryFromPeer();
        var permissionBefore = contactService.permissionsFor(peerContactId);
        ingestService.ingest(peerIdentityId, myIdentityId, progressSummaryEnvelope(randomObjectId(18), 1, 1, 1), NOW);
        var permissionAfter = contactService.permissionsFor(peerContactId);

        assertEquals(permissionBefore, permissionAfter, "ingesting peer data must never mutate this device's OWN grant toward that contact");
    }
}

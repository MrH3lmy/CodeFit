package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.ConsentRevision;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.MessageType;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.protocol.Tombstone;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #184: {@link PeerSyncOutboxService} computes what's currently eligible to send a contact, always
 * freshly from approvals + current authorization - never a tracked pending queue - and correctly
 * reuses byte-identical envelopes for unchanged content (required for a resend to be a harmless
 * {@code DUPLICATE} rather than a nonsensical {@code STALE_REVISION}).
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerSyncOutboxServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T12:00:00Z");

    private final IdentityService identityService = new IdentityService();
    private final ContactService contactService = new ContactService();
    // SnapshotPublicationService's own authorization/aggregation clock must agree with this test's
    // fixed NOW - its default constructor uses the real system clock, which would evaluate every
    // historical-window check against today's actual date instead of the fictional NOW used here.
    private final PeerSyncOutboxService outboxService = new PeerSyncOutboxService(
            new com.codefit.repository.PublicationOutboxRepository(), new com.codefit.repository.PublicationWireStateRepository(),
            contactService,
            new SnapshotPublicationService(contactService, new ProgressSnapshotService(), new PreparationSnapshotCaptureService(),
                    new com.codefit.repository.PreparationSnapshotWireStateRepository(), Clock.fixed(NOW, ZoneOffset.UTC)));

    private UnlockedIdentity identity;
    private long writerEpoch;
    private long contactId;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        identityService.createIdentity("vault-pass".toCharArray(), NOW.minusSeconds(20));
        identityService.resumeSharingAfterReview();
        identity = identityService.unlock("vault-pass".toCharArray());
        writerEpoch = identityService.beginWriterSession(NOW.minusSeconds(10));

        KeyPair contactKeyPair = KeyPairs.generate();
        IdentityKey contactKey = new IdentityKey(KeyPairs.rawPublicKey(contactKeyPair.getPublic()));
        Contact pending = contactService.registerPendingContact(contactKey, "Bob", NOW.minusSeconds(5));
        contactId = contactService.acceptInvitation(pending.id(), NOW.minusSeconds(4)).id();
    }

    private ComparisonWindow window() {
        return ComparisonWindow.day(LocalDate.of(2026, 1, 15), ZoneId.of("UTC"));
    }

    @Test
    void withNoApprovalsOrExplicitGrantTheOnlyEligibleEnvelopeIsAnEmptyScopeConsentRevision() {
        // Pairing itself already creates a ContactPermission row with empty scopes (#181's
        // clearGrantOnPairingWithoutResettingAnExistingRevision) - that is itself a real, honest
        // consent state ("explicitly nothing yet"), so it is legitimately published.
        List<PeerSyncOutboxService.Batched> batch = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW);

        assertEquals(1, batch.size());
        assertEquals(MessageType.CONSENT_REVISION, batch.get(0).envelope().body().type());
        assertEquals(List.of(), ((ConsentRevision) batch.get(0).envelope().body()).scopes());
    }

    @Test
    void aGrantAloneProducesOnlyAConsentRevisionNoApprovedObjectYet() {
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 30, null, false), NOW);

        List<PeerSyncOutboxService.Batched> batch = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW);

        assertEquals(1, batch.size());
        assertEquals(MessageType.CONSENT_REVISION, batch.get(0).envelope().body().type());
    }

    @Test
    void approvingAWindowAfterAGrantMakesItsProgressSummaryEligible() {
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 30, null, false), NOW);
        outboxService.approveProgressSummary(contactId, window(), NOW);

        List<PeerSyncOutboxService.Batched> batch = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW);

        assertEquals(2, batch.size(), "one CONSENT_REVISION plus one PROGRESS_SUMMARY");
        assertTrue(batch.stream().anyMatch(b -> b.envelope().body().type() == MessageType.PROGRESS_SUMMARY));
        assertTrue(batch.stream().anyMatch(b -> b.envelope().body() instanceof ProgressSummary));
    }

    @Test
    void resendingUnchangedContentReusesTheExactSameSignedBytes() {
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 30, null, false), NOW);
        outboxService.approveProgressSummary(contactId, window(), NOW);

        List<PeerSyncOutboxService.Batched> first = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW);
        List<PeerSyncOutboxService.Batched> second = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW.plusSeconds(5));

        SignedEnvelope firstConsent = consentEnvelope(first);
        SignedEnvelope secondConsent = consentEnvelope(second);
        assertEquals(firstConsent.messageId(), secondConsent.messageId(),
                "an unchanged resend must be byte-identical (same message id), or a receiver would wrongly see STALE_REVISION");
    }

    @Test
    void revokingAfterASuccessfulSyncEmitsATombstoneInsteadOfTheProgressSummary() {
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 30, null, false), NOW);
        var outboxEntry = outboxService.approveProgressSummary(contactId, window(), NOW);

        List<PeerSyncOutboxService.Batched> firstBatch = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW);
        SignedEnvelope summaryEnvelope = firstBatch.stream().filter(b -> b.envelope().body() instanceof ProgressSummary)
                .findFirst().orElseThrow().envelope();
        // Simulate a successfully completed sync session.
        outboxService.markSynced(outboxEntry.id(), summaryEnvelope.header().revision(), NOW);

        contactService.updatePermissions(contactId, PermissionGrant.revokeAll(), NOW.plusSeconds(1));

        List<PeerSyncOutboxService.Batched> afterRevoke = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW.plusSeconds(2));
        assertFalse(afterRevoke.stream().anyMatch(b -> b.envelope().body() instanceof ProgressSummary),
                "the revoked summary must never be (re)sent");
        assertTrue(afterRevoke.stream().anyMatch(b -> b.envelope().body() instanceof Tombstone),
                "a tombstone is owed since the peer was believed to already have a copy");
        SignedEnvelope consent = consentEnvelope(afterRevoke);
        assertEquals(List.of(), ((ConsentRevision) consent.body()).scopes(), "the fresh consent revision reflects the revoked (empty) grant");
    }

    @Test
    void revokingBeforeAnySuccessfulSyncEmitsNoTombstoneSinceThePeerNeverHadACopy() {
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 30, null, false), NOW);
        outboxService.approveProgressSummary(contactId, window(), NOW);
        // Never marked synced - the peer may never have actually received it.

        contactService.updatePermissions(contactId, PermissionGrant.revokeAll(), NOW.plusSeconds(1));

        List<PeerSyncOutboxService.Batched> afterRevoke = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW.plusSeconds(2));
        assertFalse(afterRevoke.stream().anyMatch(b -> b.envelope().body() instanceof Tombstone),
                "no tombstone is owed for an object nothing confirms the peer ever actually received");
    }

    @Test
    void anExpiredGrantStopsNewPublicationEvenThoughTheGrantRowStillExists() {
        // SnapshotPublicationService deliberately evaluates authorization against its OWN fixed
        // Clock, never a caller-supplied Instant (#183 round-2 fix: a caller must never be able to
        // revive an expired grant merely by passing a different "now"). Simulating time actually
        // passing therefore needs a second service instance built with a later fixed clock, not a
        // different `now` argument to the same instance.
        contactService.updatePermissions(contactId,
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 30, NOW.plusSeconds(10), false), NOW);
        outboxService.approveProgressSummary(contactId, window(), NOW);
        assertTrue(outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW).stream()
                .anyMatch(b -> b.envelope().body() instanceof ProgressSummary), "still within the grant's expiry");

        PeerSyncOutboxService laterOutboxService = outboxServiceAt(NOW.plusSeconds(20));
        List<PeerSyncOutboxService.Batched> afterExpiry = laterOutboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch,
                NOW.plusSeconds(20));
        assertFalse(afterExpiry.stream().anyMatch(b -> b.envelope().body() instanceof ProgressSummary),
                "an expired grant must stop new publication exactly like an explicit revoke, with no further action needed");
        assertEquals(List.of(), ((ConsentRevision) consentEnvelope(afterExpiry).body()).scopes(),
                "expiry must synchronize effective empty consent so delayed/replayed data cannot remain authorized");
    }

    private PeerSyncOutboxService outboxServiceAt(Instant now) {
        return new PeerSyncOutboxService(new com.codefit.repository.PublicationOutboxRepository(),
                new com.codefit.repository.PublicationWireStateRepository(), contactService,
                new SnapshotPublicationService(contactService, new ProgressSnapshotService(), new PreparationSnapshotCaptureService(),
                        new com.codefit.repository.PreparationSnapshotWireStateRepository(), Clock.fixed(now, ZoneOffset.UTC)));
    }

    @Test
    void repeatedBoundedPassesEventuallyReachEveryApprovedWindow() {
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 400, null, false), NOW);
        LocalDate start = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 250; i++) {
            outboxService.approveProgressSummary(contactId, ComparisonWindow.day(start.plusDays(i), ZoneId.of("UTC")), NOW);
        }

        java.util.Set<Long> reached = new java.util.HashSet<>();
        for (int pass = 0; pass < 3 && reached.size() < 250; pass++) {
            Instant passNow = NOW.plusSeconds(pass);
            List<PeerSyncOutboxService.Batched> batch =
                    outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, passNow);
            for (PeerSyncOutboxService.Batched item : batch) {
                item.outboxEntryId().ifPresent(id -> {
                    reached.add(id);
                    outboxService.markSynced(id, item.envelope().header().revision(), passNow);
                });
            }
        }

        assertEquals(250, reached.size(),
                "bounded passes must rotate through the durable outbox instead of starving rows beyond the first batch");
    }

    @Test
    void theEligibleBatchIsBoundedEvenWithManyApprovedWindows() {
        contactService.updatePermissions(contactId, new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 400, null, false), NOW);
        LocalDate start = LocalDate.of(2025, 1, 1);
        for (int i = 0; i < 250; i++) {
            outboxService.approveProgressSummary(contactId, ComparisonWindow.day(start.plusDays(i), ZoneId.of("UTC")), NOW);
        }

        List<PeerSyncOutboxService.Batched> batch = outboxService.eligibleEnvelopesFor(contactId, identity, writerEpoch, NOW);

        assertTrue(batch.size() <= 200, "one sync pass must never enumerate unbounded history, however many objects are approved: got "
                + batch.size());
    }

    private SignedEnvelope consentEnvelope(List<PeerSyncOutboxService.Batched> batch) {
        return batch.stream().filter(b -> b.envelope().body().type() == MessageType.CONSENT_REVISION)
                .findFirst().orElseThrow().envelope();
    }
}

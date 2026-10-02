package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #183's grant-enforcement and signing boundary: a contact's current #181 sharing grant must be
 * checked before any snapshot is captured/encoded/signed, two differently-granted contacts must never
 * be able to collide onto the same signed payload, and the signature must genuinely cover the whole
 * permitted body.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SnapshotPublicationServiceTest {
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    private final ContactService contactService = new ContactService();
    private final IdentityService identityService = new IdentityService();
    private SnapshotPublicationService publicationService;
    private UnlockedIdentity identity;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        identityService.createIdentity("vault-pass".toCharArray(), BASE);
        identityService.resumeSharingAfterReview();
        identity = identityService.unlock("vault-pass".toCharArray());
        publicationService = new SnapshotPublicationService();
    }

    private static IdentityKey key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new IdentityKey(bytes);
    }

    private Contact pairedContact(int keyFill, String name, Instant now) {
        Contact pending = contactService.registerPendingContact(key(keyFill), name, now);
        return contactService.acceptInvitation(pending.id(), now);
    }

    @Test
    void unauthorizedContactCannotPublishAnything() {
        Contact contact = pairedContact(1, "Ada", BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(contact.id(), identity, 1, window, BASE.plusSeconds(1)));
    }

    @Test
    void grantedContactCanPublishAndTheSignatureVerifies() {
        Contact contact = pairedContact(2, "Ben", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        SignedEnvelope envelope = publicationService.publishProgressSummary(contact.id(), identity, 1, window, now);

        assertTrue(envelope.verifySignature(), "a freshly signed envelope must verify");
        assertTrue(envelope.header().audience().includes(contact.identityId()), "envelope is addressed to this contact");
        assertTrue(envelope.body() instanceof ProgressSummary);
    }

    @Test
    void modifyingASignedFieldBreaksVerification() {
        Contact contact = pairedContact(3, "Cara", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        SignedEnvelope original = publicationService.publishProgressSummary(contact.id(), identity, 1, window, now);

        EnvelopeHeader tamperedHeader = new EnvelopeHeader(original.header().minorVersion(), original.header().author(),
                original.header().objectId(), original.header().epoch(), original.header().sequence(),
                original.header().revision() + 1, original.header().createdAt(), original.header().expiresAt(),
                original.header().audience());
        SignedEnvelope tampered = new SignedEnvelope(new Envelope(tamperedHeader, original.body()), original.signature());

        assertFalse(tampered.verifySignature(), "changing any signed field must break verification");
    }

    @Test
    void aSnapshotSignedForOneContactIsNotAddressedToAnother() {
        Contact contactA = pairedContact(4, "Dee", BASE);
        Contact contactB = pairedContact(5, "Eve", BASE);
        PermissionGrant grant = new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false);
        contactService.updatePermissions(contactA.id(), grant, BASE);
        contactService.updatePermissions(contactB.id(), grant, BASE);
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        SignedEnvelope forA = publicationService.publishProgressSummary(contactA.id(), identity, 1, window, now);
        SignedEnvelope forB = publicationService.publishProgressSummary(contactB.id(), identity, 1, window, now);

        assertFalse(forA.header().audience().includes(contactB.identityId()), "A's envelope must not be addressed to B");
        assertFalse(forB.header().audience().includes(contactA.identityId()), "B's envelope must not be addressed to A");
        assertNotEquals(forA.header().objectId(), forB.header().objectId(),
                "different recipients of the same window must never share one object id / revision stream");
    }

    @Test
    void differentGrantsProduceDifferentPermittedProjectionsOfTheSameLocalState() {
        Contact daily = pairedContact(6, "Finn", BASE);
        Contact weekly = pairedContact(7, "Gia", BASE);
        contactService.updatePermissions(daily.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        contactService.updatePermissions(weekly.id(),
                new PermissionGrant(List.of(SharingScope.WEEKLY_SUMMARY), null, null, false), BASE);

        ComparisonWindow dayWindow = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        ComparisonWindow weekWindow = ComparisonWindow.week(LocalDate.of(2026, 1, 12), UTC, java.time.DayOfWeek.MONDAY);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        // The daily-only contact may receive a day window but not a week window, and vice versa -
        // each contact's own grant decides exactly which projection of the same underlying evidence
        // they may ever be given, enforced before any capture/signing happens.
        SignedEnvelope dailyEnvelope = publicationService.publishProgressSummary(daily.id(), identity, 1, dayWindow, now);
        assertTrue(dailyEnvelope.verifySignature());
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(daily.id(), identity, 1, weekWindow, now));

        SignedEnvelope weeklyEnvelope = publicationService.publishProgressSummary(weekly.id(), identity, 1, weekWindow, now);
        assertTrue(weeklyEnvelope.verifySignature());
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(weekly.id(), identity, 1, dayWindow, now));
    }

    @Test
    void grantWithNoHistoricalWindowNeverSharesEvidenceFromBeforeTheGrant() {
        Contact contact = pairedContact(8, "Hugo", BASE.plusSeconds(10_000));
        Instant grantedAt = BASE.plusSeconds(10_000);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), grantedAt);

        ComparisonWindow beforeGrant = ComparisonWindow.day(LocalDate.ofInstant(BASE, UTC), UTC);
        Instant now = grantedAt.plus(Duration.ofDays(1));

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(contact.id(), identity, 1, beforeGrant, now),
                "a window entirely before the grant was made must never be published, even once authorized going forward");
    }

    @Test
    void blockedContactCannotReceiveANewSnapshotEvenWithAPriorGrant() {
        Contact contact = pairedContact(9, "Ivy", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        contactService.block(contact.id(), false, BASE.plusSeconds(1));

        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(contact.id(), identity, 1, window, now));
    }

    @Test
    void removedContactCannotReceiveANewSnapshotEvenWithAPriorGrant() {
        Contact contact = pairedContact(10, "Jon", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), BASE);
        contactService.remove(contact.id(), false, BASE.plusSeconds(1));

        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant now = LocalDate.of(2026, 1, 15).atTime(23, 0).atZone(UTC).toInstant();

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(contact.id(), identity, 1, window, now));
    }

    @Test
    void expiredGrantCannotReceiveANewSnapshot() {
        Contact contact = pairedContact(11, "Kim", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, BASE.plusSeconds(100), false), BASE);

        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), UTC);
        Instant afterExpiry = BASE.plusSeconds(500);

        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishProgressSummary(contact.id(), identity, 1, window, afterExpiry));
    }

    @Test
    void preparationSnapshotIsAlsoGatedByItsOwnScopeAndSignsCleanly() {
        Contact contact = pairedContact(12, "Lia", BASE);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.PREPARATION_SNAPSHOT), null, null, false), BASE);
        String profileId = new InterviewProfileService().getRevolutJavaProfile().getId();
        Instant now = BASE.plusSeconds(100);

        SignedEnvelope envelope = publicationService.publishPreparationSnapshot(contact.id(), identity, 1, profileId, now);

        assertTrue(envelope.verifySignature());
        assertTrue(envelope.body() instanceof PreparationSnapshot);

        Contact ungranted = pairedContact(13, "Mo", BASE);
        assertThrows(SnapshotPublicationService.NotAuthorizedToPublishException.class,
                () -> publicationService.publishPreparationSnapshot(ungranted.id(), identity, 1, profileId, now));
    }
}

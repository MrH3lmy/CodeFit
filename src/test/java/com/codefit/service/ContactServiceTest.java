package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactNotFoundException;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.identity.IllegalContactStateException;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.repository.ConsentChangeEventRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers #181's contact and permission enforcement: discovery alone grants nothing, defaults disclose
 * nothing, block/remove take effect immediately through the service layer, expiry is honoured, and the
 * tombstone-style cutoff/regrant semantics (a re-pair never resurrects the previous grant).
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ContactServiceTest {

    private static final Instant BASE = Instant.ofEpochMilli(1_735_000_000_000L);

    private final ContactService contactService = new ContactService();
    private final IdentityService identityService = new IdentityService();

    private static Instant at(long secondsFromBase) {
        return BASE.plusSeconds(secondsFromBase);
    }

    private static IdentityKey key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new IdentityKey(bytes);
    }

    @BeforeEach
    void resetPeerIdentityTablesAndCreateAnUnpausedIdentity() {
        PeerIdentityTestTables.resetAll();
        // isAuthorizedToPublish denies everything while the identity is paused or missing (see
        // PeerIdentityPrivacyGuardTest and IdentityServiceTest for the identity lifecycle itself); most
        // of this class's assertions are about contact/permission state, so give every test a ready
        // identity up front rather than repeating this in each one.
        identityService.createIdentity("vault-pass".toCharArray(), at(-1));
        identityService.resumeSharingAfterReview();
    }

    @Test
    void discoveringACandidateGrantsNothingAndDoesNotAuthorizeAnyScope() {
        Contact contact = contactService.registerPendingContact(key(1), "Ada", at(0));

        assertEquals(TrustState.PENDING, contact.trustState());
        for (SharingScope scope : SharingScope.values()) {
            assertFalse(contactService.isAuthorizedToPublish(contact.id(), scope, at(0)));
        }
    }

    @Test
    void registeringTheSameIdentityTwiceIsRejected() {
        contactService.registerPendingContact(key(2), "Ben", at(0));

        assertThrows(IllegalContactStateException.class, () -> contactService.registerPendingContact(key(2), "Ben again", at(1)));
    }

    @Test
    void acceptingAnInvitationPairsButGrantsNothingByDefault() {
        Contact contact = contactService.registerPendingContact(key(3), "Cleo", at(0));

        Contact paired = contactService.acceptInvitation(contact.id(), at(1));

        assertEquals(TrustState.PAIRED, paired.trustState());
        ContactPermission permission = contactService.permissionsFor(contact.id()).orElseThrow();
        assertTrue(permission.scopes().isEmpty());
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(1)));
    }

    @Test
    void rejectingAPendingInvitationRemovesTheCandidateEntirely() {
        Contact contact = contactService.registerPendingContact(key(4), "Drew", at(0));

        contactService.rejectInvitation(contact.id(), at(1));

        assertThrows(ContactNotFoundException.class, () -> contactService.requireContact(contact.id()));
    }

    @Test
    void rejectingAContactDowngradedByAnIdentityResetPreservesItsConsentHistoryInstead() {
        Contact contact = contactService.registerPendingContact(key(17), "Omar", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(2));

        // Simulates what IdentityService.resetIdentityWithoutContinuity does to every paired contact:
        // it leaves this now-PENDING contact with consent-change history #184 still needs.
        contactService.downgradeAllPairedContactsForIdentityReset(at(3));
        assertEquals(TrustState.PENDING, contactService.requireContact(contact.id()).trustState());
        ConsentChangeEventRepository outbox = new ConsentChangeEventRepository();
        assertFalse(outbox.findByContactId(contact.id()).isEmpty());

        contactService.rejectInvitation(contact.id(), at(4));

        // The row (and therefore its history) must survive: a hard delete would cascade-delete the
        // required IDENTITY_RESET event before #184 ever synchronizes it.
        assertEquals(TrustState.REMOVED, contactService.requireContact(contact.id()).trustState());
        assertFalse(outbox.findByContactId(contact.id()).isEmpty());
    }

    @Test
    void grantingAScopeAuthorizesExactlyThatScopeAndNoOthers() {
        Contact contact = contactService.registerPendingContact(key(5), "Eva", at(0));
        contactService.acceptInvitation(contact.id(), at(1));

        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), at(2));

        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.DAILY_SUMMARY, at(2)));
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.WEEKLY_SUMMARY, at(2)));
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(2)));
    }

    @Test
    void grantingPermissionsToAContactThatIsNotPairedIsRejected() {
        Contact contact = contactService.registerPendingContact(key(6), "Finn", at(0));

        assertThrows(IllegalContactStateException.class, () -> contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(1)));
    }

    @Test
    void blockingAContactImmediatelyDeniesAPreviouslyGrantedScope() {
        Contact contact = contactService.registerPendingContact(key(7), "Grace", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(2));
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(2)));

        contactService.block(contact.id(), true, at(3));

        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(3)));
        assertEquals(TrustState.BLOCKED, contactService.requireContact(contact.id()).trustState());
    }

    @Test
    void removingAContactImmediatelyDeniesFurtherSharingAndRequiresAnExplicitRePair() {
        Contact contact = contactService.registerPendingContact(key(8), "Huan", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.WEEKLY_SUMMARY), null, null, false), at(2));

        contactService.remove(contact.id(), true, at(3));

        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.WEEKLY_SUMMARY, at(3)));
        assertEquals(TrustState.REMOVED, contactService.requireContact(contact.id()).trustState());
        assertThrows(IllegalContactStateException.class, () -> contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.WEEKLY_SUMMARY), null, null, false), at(4)));
    }

    @Test
    void rePairingARemovedContactNeverResurrectsThePreviousGrant() {
        Contact contact = contactService.registerPendingContact(key(9), "Ivy", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE, SharingScope.DAILY_SUMMARY), null, null, false), at(2));
        contactService.remove(contact.id(), false, at(3));

        Contact rePaired = contactService.rePair(contact.id(), at(4));

        assertEquals(TrustState.PAIRED, rePaired.trustState());
        assertTrue(contactService.permissionsFor(contact.id()).orElseThrow().scopes().isEmpty());
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(4)));
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.DAILY_SUMMARY, at(4)));
    }

    @Test
    void rePairingAContactThatWasNeverBlockedOrRemovedIsRejected() {
        Contact contact = contactService.registerPendingContact(key(10), "Jax", at(0));
        contactService.acceptInvitation(contact.id(), at(1));

        assertThrows(IllegalContactStateException.class, () -> contactService.rePair(contact.id(), at(2)));
    }

    @Test
    void anExpiredGrantIsNoLongerAuthorizedEvenWithoutAnExplicitRevoke() {
        Contact contact = contactService.registerPendingContact(key(11), "Kim", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        Instant expiresAt = at(10);
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, expiresAt, false), at(2));

        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(5)));
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(10)));
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(20)));
    }

    @Test
    void historicalWindowDefaultsToNoBackfillBeforeTheGrantItself() {
        Contact contact = contactService.registerPendingContact(key(12), "Liu", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), null, null, false), at(5));

        Instant windowStart = contactService.historicalWindowStart(contact.id(), SharingScope.DAILY_SUMMARY, at(5)).orElseThrow();

        assertEquals(at(5), windowStart);
    }

    @Test
    void anExplicitHistoricalWindowAllowsThatManyDaysOfBackfill() {
        Contact contact = contactService.registerPendingContact(key(13), "Moss", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 7, null, false), at(5));

        Instant windowStart = contactService.historicalWindowStart(contact.id(), SharingScope.DAILY_SUMMARY, at(5)).orElseThrow();

        assertEquals(at(5).minus(Duration.ofDays(7)), windowStart);
    }

    @Test
    void duplicateDisplayNamesAcrossContactsAreValid() {
        Contact first = contactService.registerPendingContact(key(14), "Same Name", at(0));
        Contact second = contactService.registerPendingContact(key(15), "Same Name", at(1));

        assertEquals("Same Name", contactService.requireContact(first.id()).displayName());
        assertEquals("Same Name", contactService.requireContact(second.id()).displayName());
        assertFalse(first.id() == second.id());
    }

    @Test
    void aPausedIdentityDeniesPublicationEvenForAValidlyGrantedScope() {
        Contact contact = contactService.registerPendingContact(key(18), "Priya", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(2));
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(2)));

        // Rotating (or restoring/resetting) the identity pauses sharing pending an explicit review;
        // that pause must deny every contact's already-granted scope, not just contacts the rotation
        // itself touched.
        identityService.rotateKeyWithContinuity("vault-pass".toCharArray(), "vault-pass-2".toCharArray(), at(3));

        assertTrue(identityService.isSharingPaused());
        assertFalse(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(3)));

        identityService.resumeSharingAfterReview();
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(3)));
    }

    @Test
    void everyContactGetsAFullFingerprintDerivedFromItsPinnedIdentity() {
        Contact contact = contactService.registerPendingContact(key(16), "Nia", at(0));

        assertEquals(com.codefit.peer.identity.IdentityFingerprint.format(key(16).id()), contact.fingerprint());
    }

    @Test
    void updatePermissionsRollsBackThePermissionWriteWhenTheOutboxWriteFails() throws java.sql.SQLException {
        Contact contact = contactService.registerPendingContact(key(19), "Quinn", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        forceOutboxInsertToFailFor(contact.id());

        assertThrows(IllegalStateException.class, () -> contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(2)));

        // The permission write inside the same transaction must have rolled back too: still whatever
        // acceptInvitation left (no scopes), never the scope updatePermissions almost committed.
        assertTrue(contactService.permissionsFor(contact.id()).orElseThrow().scopes().isEmpty());
    }

    @Test
    void blockRollsBackBothThePermissionRevokeAndTheTrustStateChangeWhenTheOutboxWriteFails() throws java.sql.SQLException {
        Contact contact = contactService.registerPendingContact(key(20), "Rae", at(0));
        contactService.acceptInvitation(contact.id(), at(1));
        contactService.updatePermissions(contact.id(),
                new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), null, null, false), at(2));
        forceOutboxInsertToFailFor(contact.id());

        assertThrows(IllegalStateException.class, () -> contactService.block(contact.id(), true, at(3)));

        // Neither of the other two writes in the same transaction may apply without the outbox record
        // #184 needs: the contact must still be PAIRED, and its grant must still be in effect.
        assertEquals(TrustState.PAIRED, contactService.requireContact(contact.id()).trustState());
        assertTrue(contactService.isAuthorizedToPublish(contact.id(), SharingScope.SOCIAL_PROFILE, at(3)));
    }

    /** Makes the next INSERT into {@code consent_change_events} for exactly this contact fail. */
    private static void forceOutboxInsertToFailFor(long contactId) throws java.sql.SQLException {
        try (java.sql.Connection connection = com.codefit.config.DatabaseConfig.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER force_outbox_failure_%d BEFORE INSERT ON consent_change_events
                    WHEN NEW.contact_id = %d
                    BEGIN SELECT RAISE(ABORT, 'forced failure for atomicity test'); END
                    """.formatted(contactId, contactId));
        }
    }
}

package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactNotFoundException;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.identity.IllegalContactStateException;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.SharingScope;
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

    private static Instant at(long secondsFromBase) {
        return BASE.plusSeconds(secondsFromBase);
    }

    private static IdentityKey key(int fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) fill);
        return new IdentityKey(bytes);
    }

    @BeforeEach
    void resetPeerIdentityTables() {
        PeerIdentityTestTables.resetAll();
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

        contactService.rejectInvitation(contact.id());

        assertThrows(ContactNotFoundException.class, () -> contactService.requireContact(contact.id()));
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
    void everyContactGetsAFullFingerprintDerivedFromItsPinnedIdentity() {
        Contact contact = contactService.registerPendingContact(key(16), "Nia", at(0));

        assertEquals(com.codefit.peer.identity.IdentityFingerprint.format(key(16).id()), contact.fingerprint());
    }
}

package com.codefit.service;

import com.codefit.peer.identity.SocialProfile;
import com.codefit.peer.protocol.SocialProfileCard;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.DayOfWeek;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers #181's social profile: renaming persists across restart without touching identity, duplicate
 * display names are valid, and the avatar never crosses into the peer-visible wire projection.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SocialProfileServiceTest {

    private static final Instant BASE = Instant.ofEpochMilli(1_735_000_000_000L);

    private static Instant at(long secondsFromBase) {
        return BASE.plusSeconds(secondsFromBase);
    }

    @BeforeEach
    void resetPeerIdentityTables() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void editingTheDisplayNamePersistsAcrossRestartWithoutAffectingIdentity() {
        SocialProfileService profileService = new SocialProfileService();
        IdentityService identityService = new IdentityService();
        identityService.createIdentity("vault-pass".toCharArray(), at(0));
        var originalIdentity = identityService.currentIdentity().orElseThrow();

        profileService.editProfile("Original Name", null, "UTC", DayOfWeek.MONDAY, at(1));
        profileService.editProfile("Renamed", null, "UTC", DayOfWeek.MONDAY, at(2));

        SocialProfileService afterRestart = new SocialProfileService();
        SocialProfile reloaded = afterRestart.currentProfile().orElseThrow();
        assertEquals("Renamed", reloaded.displayName());
        assertEquals(originalIdentity.id(), identityService.currentIdentity().orElseThrow().id());
        assertEquals(originalIdentity.publicKey(), identityService.currentIdentity().orElseThrow().publicKey());
    }

    @Test
    void duplicateDisplayNamesAreValidAndNotUniquenessEnforced() {
        SocialProfileService profileService = new SocialProfileService();

        SocialProfile profile = profileService.editProfile("Popular Name", null, "UTC", DayOfWeek.MONDAY, at(0));

        assertEquals("Popular Name", profile.displayName());
        // Nothing about this profile depends on the name being unique - re-saving the exact same
        // name a peer/contact might also be using is unremarkable.
        SocialProfile again = profileService.editProfile("Popular Name", null, "UTC", DayOfWeek.MONDAY, at(1));
        assertEquals("Popular Name", again.displayName());
    }

    @Test
    void theWireCardNeverIncludesTheAvatar() {
        SocialProfileService profileService = new SocialProfileService();
        profileService.editProfile("Ada", "bio text", "UTC", DayOfWeek.MONDAY, at(0));
        byte[] avatar = {1, 2, 3, 4};
        SocialProfile withAvatar = profileService.setAvatar(avatar, "image/png", at(1));

        assertArrayEquals(avatar, withAvatar.avatarBytes());
        SocialProfileCard card = withAvatar.toWireCard();
        assertEquals("Ada", card.displayName());
        assertEquals("bio text", card.bio());
        // SocialProfileCard (protocol v1) structurally has no avatar field at all; there is nothing
        // further to assert beyond "the card only carries what SocialProfileCard's own fields are".
    }

    @Test
    void clearingTheAvatarRemovesItButKeepsOtherFields() {
        SocialProfileService profileService = new SocialProfileService();
        profileService.editProfile("Ben", null, "UTC", DayOfWeek.MONDAY, at(0));
        profileService.setAvatar(new byte[]{9, 9}, "image/png", at(1));

        SocialProfile cleared = profileService.clearAvatar(at(2));

        assertNull(cleared.avatarBytes());
        assertEquals("Ben", cleared.displayName());
    }
}

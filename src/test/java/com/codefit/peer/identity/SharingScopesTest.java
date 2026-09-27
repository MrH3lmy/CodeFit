package com.codefit.peer.identity;

import com.codefit.peer.protocol.SharingScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A grant built out of order, or with a duplicate scope, must still end up in the strictly-ascending,
 * duplicate-free shape {@code com.codefit.peer.protocol.ConsentRevision} requires on the wire — #184
 * cannot serialize anything else. Both {@link PermissionGrant} (the API boundary a caller uses) and
 * {@link ContactPermission} (the persisted/outbox shape) canonicalize independently, so neither one
 * depends on the other having already done it.
 */
class SharingScopesTest {

    @Test
    void permissionGrantCanonicalizesOutOfOrderScopes() {
        PermissionGrant grant = new PermissionGrant(
                List.of(SharingScope.WEEKLY_SUMMARY, SharingScope.DAILY_SUMMARY, SharingScope.SOCIAL_PROFILE),
                null, null, false);

        assertEquals(List.of(SharingScope.SOCIAL_PROFILE, SharingScope.DAILY_SUMMARY, SharingScope.WEEKLY_SUMMARY),
                grant.scopes());
    }

    @Test
    void permissionGrantDeduplicatesRepeatedScopes() {
        PermissionGrant grant = new PermissionGrant(
                List.of(SharingScope.DAILY_SUMMARY, SharingScope.DAILY_SUMMARY, SharingScope.SOCIAL_PROFILE),
                null, null, false);

        assertEquals(List.of(SharingScope.SOCIAL_PROFILE, SharingScope.DAILY_SUMMARY), grant.scopes());
    }

    @Test
    void contactPermissionCanonicalizesOutOfOrderScopesIndependently() {
        ContactPermission permission = new ContactPermission(1L,
                List.of(SharingScope.PREPARATION_SNAPSHOT, SharingScope.SOCIAL_PROFILE), null, null, false, 1,
                Instant.ofEpochMilli(1_735_000_000_000L));

        assertEquals(List.of(SharingScope.SOCIAL_PROFILE, SharingScope.PREPARATION_SNAPSHOT), permission.scopes());
    }
}

package com.codefit.peer.identity;

import com.codefit.peer.protocol.SharingScope;

import java.time.Instant;
import java.util.List;

/**
 * What a learner has explicitly chosen to share with one contact: the requested inputs to a
 * {@link ContactPermission}. Not itself persisted; {@link ContactPermission} is the persisted record
 * (it also carries the revision counter and grant timestamp the service layer manages).
 *
 * @param scopes                requested scopes; empty revokes everything (mirrors
 *                               {@code com.codefit.peer.protocol.ConsentRevision})
 * @param historicalWindowDays  how many days of pre-existing history may be shared once a scope is
 *                               granted; {@code null} shares only data from the grant onward. Never
 *                               defaulted to "unlimited" by the service layer — the caller (eventually
 *                               a #187 UI control) must say so explicitly.
 * @param expiresAt             when the grant stops applying on its own; {@code null} never expires
 * @param allowForwarding       whether a future #188 forwarder may hold this contact's shared data on
 *                               the learner's behalf
 */
public record PermissionGrant(List<SharingScope> scopes, Integer historicalWindowDays, Instant expiresAt,
                               boolean allowForwarding) {

    public PermissionGrant {
        scopes = List.copyOf(scopes);
        if (historicalWindowDays != null && historicalWindowDays < 0) {
            throw new IllegalArgumentException("historicalWindowDays cannot be negative.");
        }
    }

    public static PermissionGrant revokeAll() {
        return new PermissionGrant(List.of(), null, null, false);
    }
}

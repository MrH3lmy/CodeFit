package com.codefit.peer.identity;

import com.codefit.peer.protocol.SharingScope;

import java.time.Instant;
import java.util.List;

/**
 * The persisted sharing grant for one contact: the local analogue of a
 * {@code com.codefit.peer.protocol.ConsentRevision} the sync layer (#183/#184) will eventually publish
 * to that contact. {@code revision} increases on every change so a future publisher can derive the
 * wire envelope's {@code revision} field without guessing.
 *
 * @param scopes               the complete current grant, never a delta (mirrors {@code ConsentRevision})
 * @param historicalWindowDays see {@link PermissionGrant#historicalWindowDays()}
 * @param expiresAt            {@code null} never expires
 * @param allowForwarding      see {@link PermissionGrant#allowForwarding()}
 */
public record ContactPermission(long contactId, List<SharingScope> scopes, Integer historicalWindowDays,
                                 Instant expiresAt, boolean allowForwarding, long revision, Instant updatedAt) {

    public ContactPermission {
        scopes = SharingScopes.canonicalize(scopes);
    }

    /** Default for a newly paired contact: nothing granted. Discovery/pairing alone discloses nothing. */
    public static ContactPermission none(long contactId, Instant now) {
        return new ContactPermission(contactId, List.of(), null, null, false, 0, now);
    }

    public boolean grants(SharingScope scope) {
        return scopes.contains(scope);
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return scopes.isEmpty();
    }
}

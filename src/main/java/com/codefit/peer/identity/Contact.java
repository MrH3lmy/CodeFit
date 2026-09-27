package com.codefit.peer.identity;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

import java.time.Instant;
import java.util.Objects;

/**
 * A locally pinned peer. {@code identityKey}/{@code identityId} are pinned once, at the moment the
 * contact is first known, and never change for this row: only a brand-new {@code Contact} (a fresh
 * pairing) can associate a different key with this person. {@code displayName} is exactly what that
 * peer's {@code SocialProfileCard} last claimed (or claimed out of band before first contact) — it is
 * never unique, is cached for local display only, and is not the identity.
 */
public record Contact(long id, IdentityKey identityKey, IdentityId identityId, String fingerprint,
                       String displayName, String alias, TrustState trustState,
                       Instant createdAt, Instant updatedAt) {

    public Contact {
        Objects.requireNonNull(identityKey, "identityKey");
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(trustState, "trustState");
    }
}

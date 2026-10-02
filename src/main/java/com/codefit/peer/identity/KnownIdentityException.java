package com.codefit.peer.identity;

import com.codefit.peer.protocol.IdentityId;

/**
 * Thrown by {@code NetworkingService.registerPendingContactFromInvitation} when the invitation's
 * identity is already a known contact, in any {@link TrustState}. Ordinary invitation ingestion must
 * never silently create a duplicate contact, and it must never mutate an existing contact's trust
 * state or pinned transport key either — it only ever refuses. This carries the existing contact's id
 * and current trust state so the caller can route to that contact's own explicit flow instead: most
 * commonly {@code NetworkingService.recoverContactTransportKey} for a contact that is already
 * {@link TrustState#PAIRED} (e.g. after both sides independently rotated their transport keys), or
 * {@code ContactService.rePair} first for one that is {@link TrustState#BLOCKED} or
 * {@link TrustState#REMOVED}.
 */
public class KnownIdentityException extends RuntimeException {
    private final long existingContactId;
    private final TrustState trustState;

    public KnownIdentityException(long existingContactId, TrustState trustState, IdentityId identityId) {
        super("Identity " + identityId + " is already contact " + existingContactId + " (" + trustState + ").");
        this.existingContactId = existingContactId;
        this.trustState = trustState;
    }

    public long existingContactId() {
        return existingContactId;
    }

    public TrustState trustState() {
        return trustState;
    }
}

package com.codefit.peer.identity;

import com.codefit.peer.protocol.SharingScope;

import java.time.Instant;
import java.util.List;

/**
 * A local record that a contact's granted scopes changed, kept so #184's sync layer can eventually
 * publish the matching {@code ConsentRevision} (or, for a block/remove, an empty-scope revision plus a
 * request to delete cached copies) even though the contact may have been offline at the moment the
 * change happened. The service layer enforces the new state immediately and independently of whether
 * or when this event is ever synchronized (#181 acceptance criterion: revocation must take effect
 * through the service layer, not merely by eventually reaching the peer).
 */
public record ConsentChangeEvent(long id, long contactId, List<SharingScope> scopes, boolean requestCacheDeletion,
                                  ConsentChangeReason reason, Instant createdAt, boolean synchronized_) {

    public ConsentChangeEvent {
        scopes = List.copyOf(scopes);
    }
}

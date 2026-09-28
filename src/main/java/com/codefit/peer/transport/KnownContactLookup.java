package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityId;

/**
 * What {@link PeerSession} needs from local contact state to decide whether a live, TLS-authenticated
 * peer is someone this device should actually talk to. Kept as a narrow callback so this package never
 * depends on {@code com.codefit.service}/{@code com.codefit.repository} directly.
 */
@FunctionalInterface
public interface KnownContactLookup {
    enum Status {
        /** Never seen before: not even a pending invitation. */
        UNKNOWN,
        /** Known but not yet explicitly accepted (protocol/ADR: discovery alone grants nothing). */
        PENDING,
        /** Explicitly paired: the only status a connection may proceed under. */
        PAIRED,
        /** Explicitly blocked or removed. */
        BLOCKED_OR_REMOVED
    }

    Status statusOf(IdentityId identityId);
}

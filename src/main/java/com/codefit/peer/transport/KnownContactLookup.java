package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

import java.util.Optional;

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

    /**
     * The transport key currently pinned for this contact, if one has ever been observed. Used to reject
     * a stale (older) binding and to decide whether a different key is a legitimate, newer rollover.
     * Defaults to "nothing pinned", i.e. no staleness floor.
     */
    default Optional<PinnedBinding> pinnedBindingOf(IdentityId identityId) {
        return Optional.empty();
    }

    /**
     * Which <em>paired</em> contact is currently pinned to {@code transportKey}, if any. A rollover
     * handshake uses this to learn, from the caller's TLS-proven client key alone, who to prove itself to
     * first - without the caller having to disclose anything unauthenticated. Defaults to "nobody".
     */
    default Optional<IdentityId> pairedContactPinnedTo(IdentityKey transportKey) {
        return Optional.empty();
    }
}

package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;

/**
 * The verdict of one handshake attempt (dial or accept): either successfully authenticated as a specific
 * paired contact, or a specific {@link ConnectionFailureReason} with a human-readable detail - never a
 * bare boolean, matching #182's "expose useful connection and failure states" requirement.
 *
 * <p>On success, {@code remoteBinding} is the remote peer's <em>verified, live</em> {@code IDENTITY_BINDING}
 * (identity-signed, covering now, naming exactly the key the peer's TLS certificate presented on this
 * socket): the one thing a caller may persist to refresh the key it pins for that contact. It is
 * {@code null} on failure.
 */
public record ConnectionOutcome(boolean authenticated, IdentityId remoteIdentityId, IdentityBinding remoteBinding,
                                 ConnectionFailureReason failureReason, String detail) {
    static ConnectionOutcome ok(IdentityId remote, IdentityBinding binding) {
        return new ConnectionOutcome(true, remote, binding, null, null);
    }

    static ConnectionOutcome fail(ConnectionFailureReason reason, String detail) {
        return new ConnectionOutcome(false, null, null, reason, detail);
    }
}

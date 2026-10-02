package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;

/**
 * Receives the remote {@code IDENTITY_BINDING} of every connection that fully authenticated (mutual TLS,
 * identity-signed binding, live-certificate-key match, validity window, paired contact, not stale),
 * inbound or outbound, so the owner of persistent contact state can refresh the key it pins. Never called
 * for a connection that failed any check.
 */
@FunctionalInterface
public interface VerifiedBindingListener {
    void onVerifiedBinding(IdentityId remoteIdentityId, IdentityBinding binding);
}

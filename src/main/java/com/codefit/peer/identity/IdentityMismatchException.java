package com.codefit.peer.identity;

/**
 * Thrown when a signed invitation presented for an existing contact's transport-key recovery
 * ({@code NetworkingService.recoverContactTransportKey}) was not signed by that contact's own pinned
 * identity key. The invitation's signature itself may be perfectly valid — it simply authenticates a
 * different identity, so it can never be used to update this contact's pin.
 */
public class IdentityMismatchException extends RuntimeException {
    public IdentityMismatchException(String message) {
        super(message);
    }
}

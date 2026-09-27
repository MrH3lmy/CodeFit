package com.codefit.peer.identity;

/**
 * Thrown when a contact operation is not valid for the contact's current {@link TrustState}, e.g.
 * granting sharing permissions to a contact that has never been paired, or accepting an invitation for
 * a contact that is already blocked.
 */
public class IllegalContactStateException extends RuntimeException {
    public IllegalContactStateException(String message) {
        super(message);
    }
}

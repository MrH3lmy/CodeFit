package com.codefit.peer.identity;

/**
 * Thrown by identity creation when a local identity already exists. #181 supports a single active
 * identity; replacing one is always an explicit {@code resetIdentity}/{@code rotateKey} operation, never
 * an implicit side effect of calling create again.
 */
public class IdentityAlreadyExistsException extends RuntimeException {
    public IdentityAlreadyExistsException(String message) {
        super(message);
    }
}

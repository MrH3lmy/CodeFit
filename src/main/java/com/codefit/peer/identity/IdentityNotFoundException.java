package com.codefit.peer.identity;

/** Thrown by an operation that requires a local identity to already exist. */
public class IdentityNotFoundException extends RuntimeException {
    public IdentityNotFoundException(String message) {
        super(message);
    }
}

package com.codefit.peer.identity;

/** Thrown by an operation addressed to a contact id that does not exist. */
public class ContactNotFoundException extends RuntimeException {
    public ContactNotFoundException(String message) {
        super(message);
    }
}

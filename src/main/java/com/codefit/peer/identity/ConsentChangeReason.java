package com.codefit.peer.identity;

/** Why a {@link ConsentChangeEvent} was recorded; the sync layer (#184) uses it only for its own logging. */
public enum ConsentChangeReason {
    GRANTED,
    REVOKED,
    BLOCKED,
    REMOVED,
    IDENTITY_RESET
}

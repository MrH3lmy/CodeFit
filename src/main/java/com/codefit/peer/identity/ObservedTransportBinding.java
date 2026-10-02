package com.codefit.peer.identity;

import com.codefit.peer.protocol.IdentityKey;

import java.time.Instant;
import java.util.Objects;

/**
 * The last transport key/{@code IDENTITY_BINDING} validity window observed for a paired contact, either
 * from an accepted invitation (the pairing-time binding) or from a live, authenticated connection. This
 * is exactly what a future dial pins against (#182: "Pin the expected transport key and validate its
 * identity binding and validity"); it is never itself sufficient to authenticate a connection — the live
 * mutual-TLS + {@code IDENTITY_BINDING} handshake does that on every connection attempt.
 */
public record ObservedTransportBinding(long contactId, IdentityKey transportKey, Instant validFrom, Instant validUntil, Instant observedAt) {
    public ObservedTransportBinding {
        Objects.requireNonNull(transportKey, "transportKey");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    public boolean isCurrentlyValid(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }
}

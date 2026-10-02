package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityKey;

import java.time.Instant;
import java.util.Objects;

/**
 * The transport key (and the {@code validFrom} of the {@code IDENTITY_BINDING} that authorized it) this
 * device currently pins for one paired contact. {@code validFrom} is the ordering key for rollover: a
 * different key is only ever accepted if its identity-signed binding is strictly newer, so a captured old
 * binding can never roll a contact back to a superseded key.
 */
public record PinnedBinding(IdentityKey transportKey, Instant validFrom) {
    public PinnedBinding {
        Objects.requireNonNull(transportKey, "transportKey");
        Objects.requireNonNull(validFrom, "validFrom");
    }
}

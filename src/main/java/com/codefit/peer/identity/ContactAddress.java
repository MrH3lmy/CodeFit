package com.codefit.peer.identity;

import com.codefit.peer.transport.PeerAddress;

import java.time.Instant;
import java.util.Objects;

/**
 * One cached, previously-seen reachable address for a paired contact (#182: "Reconnect from a local
 * contact/address cache"). Never itself a trust decision — dialing an address still requires the full
 * mutual-TLS + {@code IDENTITY_BINDING} handshake ({@code com.codefit.peer.transport.PeerSession}) to
 * authenticate whoever answers.
 */
public record ContactAddress(long contactId, PeerAddress address, ContactAddressSource source,
                              Instant addedAt, Instant lastSeenAt) {
    /** Bounded cache per contact: oldest-by-last-seen is evicted past this, mirroring the invitation's own 1-4 address cap. */
    public static final int MAX_ADDRESSES_PER_CONTACT = 8;

    public ContactAddress {
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(addedAt, "addedAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
    }
}

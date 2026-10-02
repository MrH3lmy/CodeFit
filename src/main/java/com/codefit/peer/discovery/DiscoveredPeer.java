package com.codefit.peer.discovery;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.transport.PeerAddress;

import java.time.Instant;
import java.util.Objects;

/** A paired contact recognized via an opt-in LAN announcement, with the address it announced from. */
public record DiscoveredPeer(IdentityId contactIdentityId, PeerAddress address, Instant discoveredAt) {
    public DiscoveredPeer {
        Objects.requireNonNull(contactIdentityId, "contactIdentityId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(discoveredAt, "discoveredAt");
    }
}

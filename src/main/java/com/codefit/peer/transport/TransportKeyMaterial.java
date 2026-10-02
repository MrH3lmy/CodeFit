package com.codefit.peer.transport;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Objects;

/**
 * The raw ingredients of the local transport identity: an Ed25519 key pair and the validity window its
 * {@code IDENTITY_BINDING} will cover. Persistence (encrypting the private key at rest) is
 * {@code com.codefit.service.TransportKeyService}'s job, one package over — this type is the public
 * hand-off between that persistence layer and {@link PeerNetworkService}, which turns it into a live,
 * TLS-capable {@link TransportIdentity} internally.
 */
public record TransportKeyMaterial(KeyPair keyPair, Instant validFrom, Instant validUntil) {
    public TransportKeyMaterial {
        Objects.requireNonNull(keyPair, "keyPair");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("validUntil must be after validFrom.");
        }
    }
}

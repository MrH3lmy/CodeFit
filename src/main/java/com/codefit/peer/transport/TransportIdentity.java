package com.codefit.peer.transport;

import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityKey;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Objects;

/**
 * The local device's live transport key pair, its self-signed TLS certificate, and the validity window
 * the current {@code IDENTITY_BINDING} for this key covers (protocol §6.1). This is a separate key from
 * the identity key: the identity key only ever signs the binding over this key, and never itself
 * touches a socket (ADR-0001 §1). Held in memory only for the lifetime of one enabled networking
 * session; persistence (encrypted at rest, like the identity vault) is {@code TransportKeyService}'s job.
 */
final class TransportIdentity {
    private final KeyPair keyPair;
    private final X509Certificate certificate;
    private final Instant validFrom;
    private final Instant validUntil;

    TransportIdentity(KeyPair keyPair, X509Certificate certificate, Instant validFrom, Instant validUntil) {
        this.keyPair = Objects.requireNonNull(keyPair, "keyPair");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        // Truncated to whole milliseconds because these instants end up as an IDENTITY_BINDING
        // envelope's validFrom/validUntil, and protocol v1 rejects any other precision
        // (INVALID_TIMESTAMP) — Instant.now() on some JVMs carries sub-millisecond precision.
        this.validFrom = Instant.ofEpochMilli(Objects.requireNonNull(validFrom, "validFrom").toEpochMilli());
        this.validUntil = Instant.ofEpochMilli(Objects.requireNonNull(validUntil, "validUntil").toEpochMilli());
    }

    KeyPair keyPair() {
        return keyPair;
    }

    X509Certificate certificate() {
        return certificate;
    }

    IdentityKey publicKey() {
        return new IdentityKey(KeyPairs.rawPublicKey(keyPair.getPublic()));
    }

    Instant validFrom() {
        return validFrom;
    }

    Instant validUntil() {
        return validUntil;
    }

    boolean isCurrentlyValid(Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    IdentityBinding toBinding() {
        return new IdentityBinding(publicKey(), validFrom, validUntil);
    }

    static TransportIdentity from(TransportKeyMaterial material) {
        return new TransportIdentity(material.keyPair(),
                SelfSignedCertificateFactory.create(material.keyPair(), material.validFrom(), material.validUntil()),
                material.validFrom(), material.validUntil());
    }
}

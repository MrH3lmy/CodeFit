package com.codefit.peer.protocol;

import java.time.Instant;
import java.util.Objects;

/**
 * The envelope author's identity key authorizes {@code transportKey} - the Ed25519 key in the device's
 * self-signed TLS certificate (#182) - for {@code [validFrom, validUntil)}. A TLS peer is accepted only
 * if its certificate key equals a transport key bound by the expected identity and currently valid; the
 * identity private key itself never has to be online for transport. Revoking a transport key is a
 * {@link Tombstone} of the binding's object id.
 */
public record IdentityBinding(IdentityKey transportKey, Instant validFrom, Instant validUntil) implements MessageBody {

    public IdentityBinding {
        Objects.requireNonNull(transportKey, "transportKey");
        long from = ProtocolTime.toWireMillis(Objects.requireNonNull(validFrom, "validFrom"), "validFrom");
        long until = ProtocolTime.toWireMillis(Objects.requireNonNull(validUntil, "validUntil"), "validUntil");
        if (until <= from || until - from > ProtocolVersion.MAX_LIFETIME_MILLIS) {
            throw new ProtocolException(RejectionReason.INVALID_TIMESTAMP, "Binding validity must be positive and at most the protocol lifetime.");
        }
    }

    @Override
    public MessageType type() {
        return MessageType.IDENTITY_BINDING;
    }

    @Override
    public void validateAgainst(EnvelopeHeader header) {
        if (transportKey.equals(header.author())) {
            throw new IllegalArgumentException("Transport key must differ from the identity key.");
        }
        if (header.expiresAt().isBefore(validUntil)) {
            throw new IllegalArgumentException("Envelope must not expire before the binding it carries.");
        }
    }

    @Override
    public byte[] encodeBody() {
        return new CanonicalWriter()
                .fixed(transportKey.bytes(), IdentityKey.LENGTH)
                .i64(validFrom.toEpochMilli())
                .i64(validUntil.toEpochMilli())
                .toByteArray();
    }

    static IdentityBinding readFrom(CanonicalReader reader) {
        return new IdentityBinding(new IdentityKey(reader.fixed(IdentityKey.LENGTH)),
                ProtocolTime.fromWireMillis(reader.i64(), "validFrom"),
                ProtocolTime.fromWireMillis(reader.i64(), "validUntil"));
    }
}

package com.codefit.peer.protocol;

import java.util.Arrays;
import java.util.Objects;

/**
 * An envelope with its author's Ed25519 signature. Instances returned by
 * {@link EnvelopeCodec#decodeFrame(byte[])} have been verified; a valid signature means "published by
 * this identity key", never "this score was earned honestly".
 */
public record SignedEnvelope(Envelope envelope, byte[] signature) {

    public SignedEnvelope {
        Objects.requireNonNull(envelope, "envelope");
        signature = ProtocolBytes.copyExact(signature, Ed25519.SIGNATURE_LENGTH, "Signature");
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    public EnvelopeHeader header() {
        return envelope.header();
    }

    public MessageBody body() {
        return envelope.body();
    }

    /** Complete payload: unsigned envelope followed by the 64-byte signature. */
    byte[] encodePayload() {
        byte[] unsigned = envelope.encodeUnsigned();
        byte[] payload = Arrays.copyOf(unsigned, unsigned.length + signature.length);
        System.arraycopy(signature, 0, payload, unsigned.length, signature.length);
        return payload;
    }

    /** SHA-256 of the complete signed payload; the duplicate-detection key. */
    public MessageId messageId() {
        return new MessageId(ProtocolBytes.sha256(encodePayload()));
    }

    public boolean verifySignature() {
        return Ed25519.verify(envelope.header().author(), envelope.signingBytes(), signature);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SignedEnvelope that && envelope.equals(that.envelope) && Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return 31 * envelope.hashCode() + Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
        return "SignedEnvelope[" + envelope + "]";
    }
}

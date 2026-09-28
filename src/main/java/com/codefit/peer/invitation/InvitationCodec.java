package com.codefit.peer.invitation;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.transport.PeerAddress;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Encodes, signs, and verifies the bounded invitation blob (ADR-0001 §4). Every length is checked
 * against a fixed bound before it is read or allocated, mirroring the protocol v1 codec's own rule,
 * even though this is a distinct format with its own signing context ("has its own signature context,
 * distinct from envelope" — ADR-0001 §4) since an invitation is exchanged before any transport session
 * exists. Decoding never does more than parse and verify: it never registers a contact, dials an
 * address, or otherwise trusts the result. That is always a separate, explicit step by the caller.
 */
public final class InvitationCodec {
    private static final byte[] SIGNING_CONTEXT = "CodeFit-P2P-Invitation-v1\0".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_HOST_BYTES = 45; // longest possible IPv6 literal
    /** Upper bound on the encoded blob, checked before any allocation while decoding. */
    private static final int MAX_ENCODED_BYTES = 4096;

    private InvitationCodec() {
    }

    /** @throws IllegalArgumentException {@code invitation.identityKey()} does not match {@code signer}'s public key */
    public static SignedInvitation sign(Invitation invitation, UnlockedIdentity signer) {
        if (!invitation.identityKey().equals(signer.publicKey())) {
            throw new IllegalArgumentException("Invitation identityKey must equal the signer's own public key.");
        }
        byte[] signature = signer.sign(signingBytes(invitation));
        return new SignedInvitation(invitation, signature);
    }

    public static byte[] encode(SignedInvitation signed) {
        byte[] unsigned = unsignedBytes(signed.invitation());
        byte[] out = new byte[unsigned.length + SignedInvitation.SIGNATURE_LENGTH];
        System.arraycopy(unsigned, 0, out, 0, unsigned.length);
        System.arraycopy(signed.signature(), 0, out, unsigned.length, SignedInvitation.SIGNATURE_LENGTH);
        if (out.length > MAX_ENCODED_BYTES) {
            throw new InvitationException(InvitationRejectionReason.OVERSIZED, "Encoded invitation exceeds " + MAX_ENCODED_BYTES + " bytes.");
        }
        return out;
    }

    public static String toBase64(SignedInvitation signed) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encode(signed));
    }

    public static SignedInvitation fromBase64(String text) {
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(text.strip());
        } catch (IllegalArgumentException e) {
            throw new InvitationException(InvitationRejectionReason.MALFORMED, "Not valid base64url: " + e.getMessage());
        }
        return decode(bytes);
    }

    /**
     * Structural decode plus signature verification only. The caller MUST separately check
     * {@link Invitation#isExpired(Instant)}/{@link Invitation#isNotYetValid(Instant)} and MUST NOT treat
     * a successfully decoded invitation as an authenticated network endpoint or a trusted contact by
     * itself (#182: "Parsing an invitation alone must not establish trust").
     */
    public static SignedInvitation decode(byte[] bytes) {
        if (bytes.length > MAX_ENCODED_BYTES) {
            throw new InvitationException(InvitationRejectionReason.OVERSIZED, "Encoded invitation exceeds " + MAX_ENCODED_BYTES + " bytes.");
        }
        Cursor cursor = new Cursor(bytes);
        int formatVersion = cursor.u8();
        if (formatVersion != FORMAT_VERSION) {
            throw new InvitationException(InvitationRejectionReason.UNSUPPORTED_FORMAT_VERSION,
                    "Unsupported invitation format version " + formatVersion);
        }
        IdentityKey identityKey = new IdentityKey(cursor.fixed(IdentityKey.LENGTH));
        IdentityKey transportKey = new IdentityKey(cursor.fixed(IdentityKey.LENGTH));
        Instant bindingValidFrom = Instant.ofEpochMilli(cursor.i64());
        Instant bindingValidUntil = Instant.ofEpochMilli(cursor.i64());
        int addressCount = cursor.u8();
        if (addressCount < Invitation.MIN_ADDRESSES || addressCount > Invitation.MAX_ADDRESSES) {
            throw new InvitationException(InvitationRejectionReason.INVALID_ADDRESS, "Address count out of bounds: " + addressCount);
        }
        List<PeerAddress> addresses = new ArrayList<>(addressCount);
        for (int i = 0; i < addressCount; i++) {
            int hostLength = cursor.u8();
            if (hostLength == 0 || hostLength > MAX_HOST_BYTES) {
                throw new InvitationException(InvitationRejectionReason.OVERSIZED, "Address host length out of bounds: " + hostLength);
            }
            String host = new String(cursor.fixed(hostLength), StandardCharsets.US_ASCII);
            int port = cursor.u16();
            try {
                addresses.add(new PeerAddress(host, port));
            } catch (IllegalArgumentException e) {
                throw new InvitationException(InvitationRejectionReason.INVALID_ADDRESS, e.getMessage());
            }
        }
        byte[] nonce = cursor.fixed(Invitation.NONCE_LENGTH);
        Instant issuedAt = Instant.ofEpochMilli(cursor.i64());
        Instant expiresAt = Instant.ofEpochMilli(cursor.i64());
        int unsignedLength = cursor.position();
        byte[] signature = cursor.fixed(SignedInvitation.SIGNATURE_LENGTH);
        cursor.requireExhausted();

        Invitation invitation;
        try {
            invitation = new Invitation(identityKey, transportKey, bindingValidFrom, bindingValidUntil, addresses, nonce, issuedAt, expiresAt);
        } catch (InvitationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InvitationException(InvitationRejectionReason.MALFORMED, e.getMessage());
        }

        byte[] unsigned = new byte[unsignedLength];
        System.arraycopy(bytes, 0, unsigned, 0, unsignedLength);
        byte[] signingBytes = withContext(unsigned);
        boolean verified = KeyPairs.verify(KeyPairs.publicKeyFromRaw(identityKey.bytes()), signingBytes, signature);
        if (!verified) {
            throw new InvitationException(InvitationRejectionReason.BAD_SIGNATURE, "Signature does not verify for the claimed identity key.");
        }
        return new SignedInvitation(invitation, signature);
    }

    private static byte[] signingBytes(Invitation invitation) {
        return withContext(unsignedBytes(invitation));
    }

    private static byte[] withContext(byte[] unsigned) {
        byte[] out = new byte[SIGNING_CONTEXT.length + unsigned.length];
        System.arraycopy(SIGNING_CONTEXT, 0, out, 0, SIGNING_CONTEXT.length);
        System.arraycopy(unsigned, 0, out, SIGNING_CONTEXT.length, unsigned.length);
        return out;
    }

    private static byte[] unsignedBytes(Invitation invitation) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(FORMAT_VERSION);
        out.writeBytes(invitation.identityKey().bytes());
        out.writeBytes(invitation.transportKey().bytes());
        writeI64(out, invitation.bindingValidFrom().toEpochMilli());
        writeI64(out, invitation.bindingValidUntil().toEpochMilli());
        if (invitation.addresses().size() > Invitation.MAX_ADDRESSES) {
            throw new IllegalArgumentException("Too many addresses.");
        }
        out.write(invitation.addresses().size());
        for (PeerAddress address : invitation.addresses()) {
            byte[] hostBytes = address.host().getBytes(StandardCharsets.US_ASCII);
            if (hostBytes.length == 0 || hostBytes.length > MAX_HOST_BYTES) {
                throw new IllegalArgumentException("Address host length out of bounds: " + hostBytes.length);
            }
            out.write(hostBytes.length);
            out.writeBytes(hostBytes);
            writeU16(out, address.port());
        }
        out.writeBytes(invitation.nonce());
        writeI64(out, invitation.issuedAt().toEpochMilli());
        writeI64(out, invitation.expiresAt().toEpochMilli());
        return out.toByteArray();
    }

    private static void writeI64(ByteArrayOutputStream out, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (value >>> shift));
        }
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    /** Strict, bounds-checked cursor over the decoded byte array; never reads past a declared bound. */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        int position() {
            return position;
        }

        int u8() {
            require(1);
            return data[position++] & 0xFF;
        }

        int u16() {
            require(2);
            int value = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            position += 2;
            return value;
        }

        long i64() {
            require(8);
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (data[position++] & 0xFF);
            }
            return value;
        }

        byte[] fixed(int length) {
            require(length);
            byte[] copy = java.util.Arrays.copyOfRange(data, position, position + length);
            position += length;
            return copy;
        }

        void requireExhausted() {
            if (position != data.length) {
                throw new InvitationException(InvitationRejectionReason.MALFORMED, (data.length - position) + " trailing byte(s).");
            }
        }

        private void require(int length) {
            if (length < 0 || data.length - position < length) {
                throw new InvitationException(InvitationRejectionReason.MALFORMED, "Truncated invitation.");
            }
        }
    }
}

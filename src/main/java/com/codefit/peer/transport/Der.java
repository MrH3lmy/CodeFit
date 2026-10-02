package com.codefit.peer.transport;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * A minimal, generic DER (ASN.1 Distinguished Encoding Rules) writer, just enough to build one X.509v3
 * certificate template for a self-signed Ed25519 transport certificate (#182). JDK 21 has no public
 * X.509 certificate builder (see {@code docs/p2p/adr-0001-decentralized-peer-architecture.md} §5), and
 * the dependency inventory's default preference is the zero-dependency option over adding Bouncy
 * Castle. This writes DER, not BER: every length uses the shortest encoding and every SET is emitted
 * with exactly one element, so there is only one valid byte form to worry about.
 */
final class Der {
    static final int TAG_INTEGER = 0x02;
    static final int TAG_BIT_STRING = 0x03;
    static final int TAG_OCTET_STRING = 0x04;
    static final int TAG_OID = 0x06;
    static final int TAG_UTF8_STRING = 0x0C;
    static final int TAG_SEQUENCE = 0x30;
    static final int TAG_SET = 0x31;
    static final int TAG_UTC_TIME = 0x17;
    static final int TAG_GENERALIZED_TIME = 0x18;
    /** Context-specific, constructed, tag number 0 (X.509 {@code version} field). */
    static final int TAG_CONTEXT_0 = 0xA0;

    /** {@code id-Ed25519} (RFC 8410): {@code 1.3.101.112}. */
    static final byte[] ED25519_OID = tlv(TAG_OID, new byte[]{0x2B, 0x65, 0x70});
    /** {@code id-at-commonName}: {@code 2.5.4.3}. */
    private static final byte[] COMMON_NAME_OID = tlv(TAG_OID, new byte[]{0x55, 0x04, 0x03});

    private Der() {
    }

    static byte[] tlv(int tag, byte[] content) {
        byte[] length = encodeLength(content.length);
        byte[] out = new byte[1 + length.length + content.length];
        out[0] = (byte) tag;
        System.arraycopy(length, 0, out, 1, length.length);
        System.arraycopy(content, 0, out, 1 + length.length, content.length);
        return out;
    }

    static byte[] encodeLength(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Negative DER length: " + length);
        }
        if (length < 0x80) {
            return new byte[]{(byte) length};
        }
        byte[] be = bigEndianMinimal(length);
        byte[] out = new byte[1 + be.length];
        out[0] = (byte) (0x80 | be.length);
        System.arraycopy(be, 0, out, 1, be.length);
        return out;
    }

    private static byte[] bigEndianMinimal(int value) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int shift = 24;
        boolean started = false;
        for (; shift >= 0; shift -= 8) {
            int b = (value >>> shift) & 0xFF;
            if (b != 0 || started || shift == 0) {
                buffer.write(b);
                started = true;
            }
        }
        return buffer.toByteArray();
    }

    static byte[] sequence(byte[]... children) {
        return tlv(TAG_SEQUENCE, concat(children));
    }

    static byte[] contextExplicit(int tagNumber, byte[] inner) {
        return tlv(0xA0 | (tagNumber & 0x1F), inner);
    }

    /** A positive INTEGER: prefixes a {@code 0x00} byte when the high bit would otherwise flip the sign. */
    static byte[] positiveInteger(byte[] magnitude) {
        int offset = 0;
        while (offset < magnitude.length - 1 && magnitude[offset] == 0) {
            offset++;
        }
        boolean needsLeadingZero = magnitude.length > offset && (magnitude[offset] & 0x80) != 0;
        byte[] content = new byte[magnitude.length - offset + (needsLeadingZero ? 1 : 0)];
        int at = 0;
        if (needsLeadingZero) {
            content[at++] = 0;
        }
        System.arraycopy(magnitude, offset, content, at, magnitude.length - offset);
        return tlv(TAG_INTEGER, content);
    }

    /** A BIT STRING holding a whole number of bytes (zero unused bits), e.g. a raw key or a signature. */
    static byte[] bitStringOfBytes(byte[] raw) {
        byte[] content = new byte[raw.length + 1];
        content[0] = 0;
        System.arraycopy(raw, 0, content, 1, raw.length);
        return tlv(TAG_BIT_STRING, content);
    }

    /** {@code AlgorithmIdentifier ::= SEQUENCE { algorithm OBJECT IDENTIFIER }} — no parameters for EdDSA (RFC 8410). */
    static byte[] ed25519AlgorithmIdentifier() {
        return sequence(ED25519_OID);
    }

    /** {@code Name ::= RDNSequence} with a single {@code commonName} RDN. */
    static byte[] commonNameOnly(String commonName) {
        byte[] value = tlv(TAG_UTF8_STRING, commonName.getBytes(StandardCharsets.UTF_8));
        byte[] attributeTypeAndValue = sequence(COMMON_NAME_OID, value);
        byte[] relativeDistinguishedName = tlv(TAG_SET, attributeTypeAndValue);
        return sequence(relativeDistinguishedName);
    }

    /** X.509 {@code Time CHOICE}: UTCTime through 2049 inclusive, GeneralizedTime from 2050 on (RFC 5280 §4.1.2.5). */
    static byte[] time(Instant instant) {
        var utc = instant.atZone(ZoneOffset.UTC);
        if (utc.getYear() >= 1950 && utc.getYear() <= 2049) {
            String formatted = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").format(utc);
            return tlv(TAG_UTC_TIME, formatted.getBytes(StandardCharsets.US_ASCII));
        }
        String formatted = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").format(utc);
        return tlv(TAG_GENERALIZED_TIME, formatted.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}

package com.codefit.peer.protocol;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** Byte-array helpers shared by the fixed-width identifier records. */
final class ProtocolBytes {
    private static final HexFormat HEX = HexFormat.of();

    private ProtocolBytes() {
    }

    static byte[] copyExact(byte[] bytes, int length, String name) {
        if (bytes == null || bytes.length != length) {
            throw new IllegalArgumentException(name + " must be exactly " + length + " bytes.");
        }
        return bytes.clone();
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform.", e);
        }
    }

    static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    static boolean allZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    static int compareUnsigned(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }
}

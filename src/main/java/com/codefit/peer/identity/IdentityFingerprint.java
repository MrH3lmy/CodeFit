package com.codefit.peer.identity;

import com.codefit.peer.protocol.IdentityId;

import java.util.HexFormat;

/**
 * The out-of-band comparison format for an {@link IdentityId}, fixed here as ADR-0001 §1 requires
 * ("UIs show a short fingerprint so users can compare it out of band (format fixed in #181)"): the
 * full 32-byte SHA-256 identity id as lowercase hex, grouped in fours for readability. The full
 * fingerprint is shown (never truncated) because it is what gets pinned at pairing time, and a
 * shortened fingerprint would let two different keys collide in what the user actually compares.
 */
public final class IdentityFingerprint {
    private static final HexFormat HEX = HexFormat.of();

    private IdentityFingerprint() {
    }

    public static String format(IdentityId id) {
        String hex = HEX.formatHex(id.bytes());
        StringBuilder grouped = new StringBuilder(hex.length() + hex.length() / 4);
        for (int i = 0; i < hex.length(); i += 4) {
            if (i > 0) {
                grouped.append(' ');
            }
            grouped.append(hex, i, Math.min(i + 4, hex.length()));
        }
        return grouped.toString();
    }
}

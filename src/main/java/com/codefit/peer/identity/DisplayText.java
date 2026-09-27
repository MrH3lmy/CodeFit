package com.codefit.peer.identity;

import java.text.Normalizer;
import java.nio.charset.StandardCharsets;

/**
 * The same display-text rules {@code com.codefit.peer.protocol.SocialProfileCard} enforces on the
 * wire (NFC, no control/format/bidi characters, no leading/trailing whitespace, a byte-length bound),
 * applied here so a locally saved profile edit can never later be rejected at publish time. That
 * protocol validation lives in a package-private helper (deliberately: v1's rules are pinned by
 * conformance fixtures, not part of #181's public API), so this is a small, intentional duplicate of
 * the same rules rather than a shared dependency across the two packages' boundary.
 */
final class DisplayText {
    private DisplayText() {
    }

    static String validate(String value, String field, int minCodePoints, int maxBytes) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(field + " must be Unicode NFC.");
        }
        if (value.codePointCount(0, value.length()) < minCodePoints
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " length out of bounds.");
        }
        if (!value.strip().equals(value)) {
            throw new IllegalArgumentException(field + " must not have leading/trailing whitespace.");
        }
        value.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (type == Character.CONTROL || type == Character.FORMAT
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(field + " contains a disallowed character U+"
                        + Integer.toHexString(cp).toUpperCase());
            }
        });
        return value;
    }
}

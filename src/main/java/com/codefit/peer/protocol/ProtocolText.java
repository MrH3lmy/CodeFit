package com.codefit.peer.protocol;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Text rules for peer-visible strings. Identifiers are a closed lowercase ASCII grammar so they
 * compare byte-for-byte on every platform. Display text must already be Unicode NFC (so one visible
 * string has one encoding) and may not contain control, format, or bidirectional-override characters
 * that could spoof how a peer's name renders. Unassigned code points are deliberately <em>not</em>
 * rejected, because that set depends on the JDK's Unicode version and two peers on different JDKs must
 * reach the same verdict. Received text is only ever displayed as plain text.
 */
final class ProtocolText {
    static final int MAX_IDENTIFIER_BYTES = 64;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private ProtocolText() {
    }

    static String identifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must match " + IDENTIFIER.pattern() + ": " + value);
        }
        return value;
    }

    static String displayText(String value, String field, int minCodePoints, int maxBytes) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required.");
        }
        if (!Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            throw new ProtocolException(RejectionReason.NON_CANONICAL, field + " must be Unicode NFC.");
        }
        if (value.codePointCount(0, value.length()) < minCodePoints
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + " length out of bounds.");
        }
        if (!value.strip().equals(value)) {
            throw new ProtocolException(RejectionReason.NON_CANONICAL, field + " must not have leading/trailing whitespace.");
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

package com.codefit.ui;

/**
 * Pure helpers for how a peer (or this device's own identity) is named on screen: a human display
 * name first, with the fingerprint demoted to a short, secondary line. The full fingerprint is never
 * discarded - callers keep it for tooltips and for the pairing verification step, where comparing the
 * <em>complete</em> value out of band is the whole point. No JavaFX dependency.
 */
public final class PeerNamePresentation {

    private PeerNamePresentation() {
    }

    /** What to call a contact: its alias, else the name it claims, else a short fingerprint. */
    public static String displayName(String alias, String displayName, String fingerprint) {
        if (alias != null && !alias.isBlank()) {
            return alias.strip();
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName.strip();
        }
        return shortFingerprint(fingerprint);
    }

    /** True when {@link #displayName} had to fall back to the fingerprint (no alias, no claimed name). */
    public static boolean isFingerprintFallback(String alias, String displayName) {
        return (alias == null || alias.isBlank()) && (displayName == null || displayName.isBlank());
    }

    /**
     * "d1bb 752a … dacf b664": the first two and last two four-character groups of a grouped
     * fingerprint. A fingerprint already short enough is returned unchanged.
     */
    public static String shortFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return "";
        }
        String[] groups = fingerprint.strip().split("\\s+");
        if (groups.length <= 4) {
            return String.join(" ", groups);
        }
        return groups[0] + " " + groups[1] + " … " + groups[groups.length - 2] + " " + groups[groups.length - 1];
    }

    /**
     * A short label for a table column or sentence: the first word of a real name ("Ahmed Hassan" ->
     * "Ahmed"), or "Peer" when the contact has no better name than its fingerprint.
     */
    public static String shortName(String name, boolean fingerprintFallback) {
        if (fingerprintFallback || name == null || name.isBlank()) {
            return "Peer";
        }
        String first = name.strip().split("\\s+")[0];
        return first.length() > 14 ? first.substring(0, 13) + "…" : first;
    }

    /** How a sentence refers to the peer: their first name, or "your peer" when all we have is a fingerprint. */
    public static String spokenName(String name, boolean fingerprintFallback) {
        return fingerprintFallback || name == null || name.isBlank() ? "your peer" : shortName(name, false);
    }

    /** Upper-cases the first character, for a sentence that may start with {@link #spokenName}. */
    public static String capitalize(String text) {
        return text == null || text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** The single upper-case letter/digit shown in a peer's avatar circle. */
    public static String initial(String name) {
        if (name != null) {
            for (int i = 0; i < name.length(); ) {
                int codePoint = name.codePointAt(i);
                if (Character.isLetterOrDigit(codePoint)) {
                    return new String(Character.toChars(Character.toUpperCase(codePoint)));
                }
                i += Character.charCount(codePoint);
            }
        }
        return "?";
    }

    /**
     * Turns a presenter reason fragment into a sentence about this peer: "the other device has not
     * imported your invitation yet" -> "Ahmed's device has not imported your invitation yet." (or "their
     * device" when the peer has no better name than a fingerprint).
     */
    public static String sentence(String reason, String name, boolean fallbackName) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        // A long display name makes an unwieldy sentence; "their device" reads better and is just as clear.
        String owner = fallbackName || name.length() > 24 ? "their device" : name + "'s device";
        String text = reason.strip().replace("the other device", owner);
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        return text.endsWith(".") ? text : text + ".";
    }
}

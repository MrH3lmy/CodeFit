package com.codefit.peer.identity;

import com.codefit.peer.protocol.SocialProfileCard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The learner's own editable social profile: display name, optional bio, comparison zone/week start,
 * and an optional bounded local avatar. Deliberately distinct from
 * {@code com.codefit.model.InterviewPreparationProfile} (a company/role competency definition) — the
 * two never reference each other (ADR-0001 §2). {@code avatarBytes} has no counterpart on the wire:
 * {@link SocialProfileCard} has no avatar field in protocol v1, so {@link #toWireCard()} can only ever
 * produce a card without one; the avatar stays local by construction, not by a filter someone could
 * forget to apply.
 */
public record SocialProfile(String displayName, String bio, String comparisonZoneId, DayOfWeek weekStart,
                             byte[] avatarBytes, String avatarMimeType, long revision, Instant updatedAt) {

    public static final int MAX_DISPLAY_NAME_BYTES = 64;
    public static final int MAX_BIO_BYTES = 280;
    /** Bounded so an avatar can never become a de facto free-form blob store; enforced on write. */
    public static final int MAX_AVATAR_BYTES = 32 * 1024;
    private static final Pattern ZONE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*");

    public SocialProfile {
        DisplayText.validate(displayName, "Display name", 1, MAX_DISPLAY_NAME_BYTES);
        if (bio != null) {
            DisplayText.validate(bio, "Bio", 1, MAX_BIO_BYTES);
        }
        if (comparisonZoneId == null || comparisonZoneId.length() > 64 || !ZONE_ID.matcher(comparisonZoneId).matches()) {
            throw new IllegalArgumentException("Comparison zone must be an IANA-style region id: " + comparisonZoneId);
        }
        Objects.requireNonNull(weekStart, "weekStart");
        if (avatarBytes != null) {
            if (avatarBytes.length > MAX_AVATAR_BYTES) {
                throw new IllegalArgumentException("Avatar exceeds " + MAX_AVATAR_BYTES + " bytes.");
            }
            avatarBytes = avatarBytes.clone();
            if (avatarMimeType == null || avatarMimeType.isBlank()) {
                throw new IllegalArgumentException("Avatar mime type is required when an avatar is set.");
            }
        }
    }

    @Override
    public byte[] avatarBytes() {
        return avatarBytes == null ? null : avatarBytes.clone();
    }

    /**
     * The peer-visible projection of this profile. Never includes {@link #avatarBytes()}: protocol v1
     * has no avatar field (docs/p2p/protocol-v1.md §6.2), so avatars can only ever stay local.
     */
    public SocialProfileCard toWireCard() {
        return new SocialProfileCard(displayName, bio, comparisonZoneId, weekStart);
    }
}

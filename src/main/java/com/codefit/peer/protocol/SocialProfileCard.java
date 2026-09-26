package com.codefit.peer.protocol;

import java.time.DayOfWeek;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The peer-visible part of a learner's <em>social</em> profile. This is deliberately unrelated to
 * {@link com.codefit.model.InterviewPreparationProfile}, which is a company/role competency definition;
 * a social profile says who a peer claims to be and how they want periods compared, nothing about what
 * they are preparing for.
 *
 * <p>Every field is self-asserted: display names are not unique or verified. There is no avatar or
 * URL field in v1, so a receiver never fetches anything on a peer's behalf. The object id is derived
 * from the author ({@link #objectIdFor(IdentityId)}), making the card a single revisable object.
 *
 * @param comparisonZoneId IANA region id (syntax-checked only; an id unknown to the receiver's tzdata
 *                         makes local-time presentation unavailable, not the message invalid)
 * @param weekStart        first day of the author's comparison week
 */
public record SocialProfileCard(String displayName, String bio, String comparisonZoneId, DayOfWeek weekStart)
        implements MessageBody {
    static final int MAX_DISPLAY_NAME_BYTES = 64;
    static final int MAX_BIO_BYTES = 280;
    static final int MAX_ZONE_ID_BYTES = 64;
    private static final Pattern ZONE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_+-]*(/[A-Za-z0-9_+-]+)*");

    public SocialProfileCard {
        ProtocolText.displayText(displayName, "Display name", 1, MAX_DISPLAY_NAME_BYTES);
        if (bio != null) {
            ProtocolText.displayText(bio, "Bio", 1, MAX_BIO_BYTES);
        }
        comparisonZoneId = zoneId(comparisonZoneId);
        Objects.requireNonNull(weekStart, "weekStart");
    }

    static String zoneId(String zoneId) {
        if (zoneId == null || zoneId.length() > MAX_ZONE_ID_BYTES || !ZONE_ID.matcher(zoneId).matches()) {
            throw new IllegalArgumentException("Comparison zone must be an IANA-style region id: " + zoneId);
        }
        return zoneId;
    }

    public static ObjectId objectIdFor(IdentityId author) {
        return DerivedObjectIds.derive("CodeFit-Social-Profile-Card-v1", author);
    }

    @Override
    public MessageType type() {
        return MessageType.SOCIAL_PROFILE_CARD;
    }

    @Override
    public void validateAgainst(EnvelopeHeader header) {
        if (!header.objectId().equals(objectIdFor(header.author().id()))) {
            throw new IllegalArgumentException("Profile card object id must be derived from the author.");
        }
    }

    @Override
    public byte[] encodeBody() {
        return new CanonicalWriter()
                .string(displayName, MAX_DISPLAY_NAME_BYTES)
                .optionalString(bio, MAX_BIO_BYTES)
                .string(comparisonZoneId, MAX_ZONE_ID_BYTES)
                .u8(weekStart.getValue())
                .toByteArray();
    }

    static SocialProfileCard readFrom(CanonicalReader reader) {
        String displayName = reader.string(MAX_DISPLAY_NAME_BYTES);
        String bio = reader.optionalString(MAX_BIO_BYTES);
        String zone = reader.string(MAX_ZONE_ID_BYTES);
        return new SocialProfileCard(displayName, bio, zone, ComparisonWindow.dayOfWeek(reader.u8()));
    }
}

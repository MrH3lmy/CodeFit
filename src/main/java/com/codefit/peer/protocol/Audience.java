package com.codefit.peer.protocol;

import java.util.List;
import java.util.Objects;

/**
 * The explicit recipient set an envelope is authorized for, bound into the signature. Recipients are
 * sorted ascending by unsigned bytes with no duplicates (one canonical form), never include the author,
 * and number 1..{@link ProtocolVersion#MAX_RECIPIENTS} (DIRECT) or 2..max (GROUP, which also carries a
 * {@link GroupId}). A receiver not listed rejects the envelope as {@link RejectionReason#UNAUTHORIZED_AUDIENCE}
 * even if it obtained the bytes via forwarding.
 */
public record Audience(AudienceKind kind, GroupId groupId, List<IdentityId> recipients) {

    public Audience {
        Objects.requireNonNull(kind, "Audience kind is required.");
        Objects.requireNonNull(recipients, "Audience recipients are required.");
        recipients = List.copyOf(recipients);
        int min = kind == AudienceKind.GROUP ? 2 : 1;
        if (recipients.size() < min || recipients.size() > ProtocolVersion.MAX_RECIPIENTS) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE,
                    kind + " audience needs " + min + ".." + ProtocolVersion.MAX_RECIPIENTS + " recipients, got " + recipients.size());
        }
        if ((kind == AudienceKind.GROUP) != (groupId != null)) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "A group id is required for GROUP and forbidden for DIRECT.");
        }
        for (int i = 1; i < recipients.size(); i++) {
            if (recipients.get(i - 1).compareTo(recipients.get(i)) >= 0) {
                throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "Recipients must be strictly ascending (sorted, no duplicates).");
            }
        }
    }

    public static Audience direct(List<IdentityId> recipients) {
        return new Audience(AudienceKind.DIRECT, null, recipients.stream().sorted().toList());
    }

    public static Audience group(GroupId groupId, List<IdentityId> recipients) {
        return new Audience(AudienceKind.GROUP, groupId, recipients.stream().sorted().toList());
    }

    public boolean includes(IdentityId identity) {
        return recipients.contains(identity);
    }

    void writeTo(CanonicalWriter writer) {
        writer.u8(kind.code());
        if (groupId != null) {
            writer.fixed(groupId.bytes(), GroupId.LENGTH);
        }
        writer.u8(recipients.size());
        for (IdentityId recipient : recipients) {
            writer.fixed(recipient.bytes(), IdentityId.LENGTH);
        }
    }

    static Audience readFrom(CanonicalReader reader) {
        int kindCode = reader.u8();
        AudienceKind kind;
        try {
            kind = WireCode.fromCode(AudienceKind.class, kindCode);
        } catch (ProtocolException e) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "Unknown audience kind " + kindCode);
        }
        GroupId groupId = null;
        if (kind == AudienceKind.GROUP) {
            byte[] raw = reader.fixed(GroupId.LENGTH);
            if (ProtocolBytes.allZero(raw)) {
                throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "Group id must not be all-zero.");
            }
            groupId = new GroupId(raw);
        }
        int count = reader.u8();
        if (count > ProtocolVersion.MAX_RECIPIENTS) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "Too many recipients: " + count);
        }
        IdentityId[] recipients = new IdentityId[count];
        for (int i = 0; i < count; i++) {
            recipients[i] = new IdentityId(reader.fixed(IdentityId.LENGTH));
        }
        return new Audience(kind, groupId, List.of(recipients));
    }
}

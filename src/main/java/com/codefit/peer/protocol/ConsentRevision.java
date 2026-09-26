package com.codefit.peer.protocol;

import java.util.List;

/**
 * The complete set of scopes the author shares with exactly one recipient, as of this revision. Not a
 * delta: a later revision replaces an earlier one entirely, and an empty set revokes everything. The
 * object id is derived from (author, recipient), so envelope revision numbers give one total order per
 * pair. Revocation stops <em>future</em> sharing; copies already received can only be asked to be
 * deleted with a {@link Tombstone}, never guaranteed erased.
 *
 * @param scopes strictly ascending by wire code, possibly empty
 */
public record ConsentRevision(List<SharingScope> scopes) implements MessageBody {

    public ConsentRevision {
        scopes = List.copyOf(scopes);
        for (int i = 1; i < scopes.size(); i++) {
            if (scopes.get(i - 1).code() >= scopes.get(i).code()) {
                throw new ProtocolException(RejectionReason.NON_CANONICAL, "Scopes must be strictly ascending by code.");
            }
        }
    }

    public static ObjectId objectIdFor(IdentityId author, IdentityId recipient) {
        return DerivedObjectIds.derive("CodeFit-Consent-v1", author, recipient);
    }

    public boolean grants(SharingScope scope) {
        return scopes.contains(scope);
    }

    @Override
    public MessageType type() {
        return MessageType.CONSENT_REVISION;
    }

    @Override
    public void validateAgainst(EnvelopeHeader header) {
        Audience audience = header.audience();
        if (audience.kind() != AudienceKind.DIRECT || audience.recipients().size() != 1) {
            throw new ProtocolException(RejectionReason.INVALID_AUDIENCE, "Consent is addressed to exactly one DIRECT recipient.");
        }
        if (!header.objectId().equals(objectIdFor(header.author().id(), audience.recipients().get(0)))) {
            throw new IllegalArgumentException("Consent object id must be derived from (author, recipient).");
        }
    }

    @Override
    public byte[] encodeBody() {
        return new CanonicalWriter()
                .list(scopes, SharingScope.values().length, (w, scope) -> w.u8(scope.code()))
                .toByteArray();
    }

    static ConsentRevision readFrom(CanonicalReader reader) {
        return new ConsentRevision(reader.list(SharingScope.values().length,
                r -> WireCode.fromCode(SharingScope.class, r.u8())));
    }
}

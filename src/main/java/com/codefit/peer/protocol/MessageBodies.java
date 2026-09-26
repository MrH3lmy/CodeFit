package com.codefit.peer.protocol;

import java.util.Arrays;

/** Dispatches body bytes to the schema-1 codec of each implemented message type. */
final class MessageBodies {
    private MessageBodies() {
    }

    static MessageBody decode(MessageType type, byte[] bytes) {
        CanonicalReader reader = new CanonicalReader(bytes);
        MessageBody body;
        try {
            body = switch (type) {
                case IDENTITY_BINDING -> IdentityBinding.readFrom(reader);
                case SOCIAL_PROFILE_CARD -> SocialProfileCard.readFrom(reader);
                case PROGRESS_SUMMARY -> ProgressSummary.readFrom(reader);
                case PREPARATION_SNAPSHOT -> PreparationSnapshot.readFrom(reader);
                case CONSENT_REVISION -> ConsentRevision.readFrom(reader);
                case TOMBSTONE -> Tombstone.readFrom(reader);
                case CHALLENGE_MANIFEST, FORWARDED_CIPHERTEXT ->
                        throw new ProtocolException(RejectionReason.UNSUPPORTED_MESSAGE_TYPE, type + " is reserved.");
            };
            reader.finish();
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(RejectionReason.INCONSISTENT_BODY, e.getMessage());
        }
        if (!Arrays.equals(body.encodeBody(), bytes)) {
            throw new ProtocolException(RejectionReason.NON_CANONICAL, type + " body is not in canonical form.");
        }
        return body;
    }
}

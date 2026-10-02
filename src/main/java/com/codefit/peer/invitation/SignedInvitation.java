package com.codefit.peer.invitation;

import java.util.Arrays;
import java.util.Objects;

/** An {@link Invitation} together with the identity key's Ed25519 signature over it. */
public record SignedInvitation(Invitation invitation, byte[] signature) {
    public static final int SIGNATURE_LENGTH = 64;

    public SignedInvitation {
        Objects.requireNonNull(invitation, "invitation");
        if (signature == null || signature.length != SIGNATURE_LENGTH) {
            throw new InvitationException(InvitationRejectionReason.MALFORMED, "Signature must be exactly " + SIGNATURE_LENGTH + " bytes.");
        }
        signature = signature.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SignedInvitation that && invitation.equals(that.invitation) && Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return 31 * invitation.hashCode() + Arrays.hashCode(signature);
    }
}

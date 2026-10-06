package com.codefit.ui;

import com.codefit.peer.identity.ClockBehindPreviousEpochException;
import com.codefit.peer.identity.IdentityAlreadyExistsException;
import com.codefit.peer.identity.KnownIdentityException;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.invitation.InvitationException;
import com.codefit.peer.invitation.InvitationRejectionReason;
import com.codefit.peer.protocol.IdentityId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerErrorPresentationTest {

    @Test
    void everyInvitationRejectionReasonHasAHumanMessageWithoutTheEnumName() {
        for (InvitationRejectionReason reason : InvitationRejectionReason.values()) {
            String message = PeerErrorPresentation.message(new InvitationException(reason, "internal detail"), "fallback");
            assertFalse(message.contains(reason.name()), reason + " leaked: " + message);
            assertFalse(message.contains("internal detail"));
            assertFalse(message.equals("fallback"), reason + " has no specific message");
        }
        assertTrue(PeerErrorPresentation.message(new InvitationException(InvitationRejectionReason.EXPIRED, "x"), "f").contains("expired"));
    }

    @Test
    void knownIdentityAndVaultErrorsGetSpecificGuidance() {
        assertTrue(PeerErrorPresentation.message(new VaultAuthenticationException("bad"), "f").contains("passphrase"));
        assertTrue(PeerErrorPresentation.message(new IdentityAlreadyExistsException("x"), "f").contains("already have an identity"));
        assertTrue(PeerErrorPresentation.message(new ClockBehindPreviousEpochException("x"), "f").contains("clock"));
        IdentityId id = new IdentityId(new byte[32]);
        assertEquals("You're already paired with this person.",
                PeerErrorPresentation.message(new KnownIdentityException(1, TrustState.PAIRED, id), "f"));
        assertEquals("This person is already in your contacts.",
                PeerErrorPresentation.message(new KnownIdentityException(1, TrustState.BLOCKED, id), "f"));
    }

    @Test
    void anUnrecognisedFailureNeverLeaksItsClassNameOrMessage() {
        String message = PeerErrorPresentation.message(new IllegalStateException("SQLITE_BUSY at PeerRepository.java:42"), "Couldn't do that.");
        assertEquals("Couldn't do that.", message);
    }
}

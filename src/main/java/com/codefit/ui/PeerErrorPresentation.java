package com.codefit.ui;

import com.codefit.peer.identity.ClockBehindPreviousEpochException;
import com.codefit.peer.identity.IdentityAlreadyExistsException;
import com.codefit.peer.identity.KnownIdentityException;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.identity.VaultAuthenticationException;
import com.codefit.peer.invitation.InvitationException;

/**
 * Pure mapping from a backend failure to one short, product-language sentence for the Peers screen.
 * Exception class names, enum names ({@code MALFORMED: ...}) and raw service messages are never shown
 * to a learner: an exception this class does not recognise gets the caller's own context-specific
 * {@code fallback} instead. No JavaFX dependency.
 */
public final class PeerErrorPresentation {

    private PeerErrorPresentation() {
    }

    /** The message to show for {@code error}; {@code fallback} is used for anything not explicitly recognised. */
    public static String message(Throwable error, String fallback) {
        if (error instanceof InvitationException invitation) {
            return invitationMessage(invitation);
        }
        if (error instanceof VaultAuthenticationException) {
            return "That passphrase didn't unlock your vault. Check it and try again.";
        }
        if (error instanceof IdentityAlreadyExistsException) {
            return "You already have an identity on this device.";
        }
        if (error instanceof KnownIdentityException known) {
            return known.trustState() == TrustState.PAIRED
                    ? "You're already paired with this person."
                    : "This person is already in your contacts.";
        }
        if (error instanceof ClockBehindPreviousEpochException) {
            return "Your computer's clock is behind this identity's last use. Fix the date and time, then try again.";
        }
        return fallback;
    }

    private static String invitationMessage(InvitationException error) {
        return switch (error.reason()) {
            case EXPIRED -> "This invitation has expired. Ask them to create a new one.";
            case NOT_YET_VALID, INVALID_TIMESTAMPS ->
                    "This invitation isn't valid yet. Check the date and time on both computers.";
            case BAD_SIGNATURE -> "This invitation couldn't be verified. Ask them to send a fresh one.";
            case UNSUPPORTED_FORMAT_VERSION -> "This invitation comes from an incompatible version of CodeFit.";
            case MALFORMED, OVERSIZED, INVALID_ADDRESS, TRANSPORT_KEY_EQUALS_IDENTITY_KEY ->
                    "That doesn't look like a CodeFit invitation. Paste the whole text they sent you.";
        };
    }
}

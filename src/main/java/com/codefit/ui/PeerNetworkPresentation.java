package com.codefit.ui;

import java.util.Optional;

/**
 * Pure view-model for the Peers screen's top status strip: which of the three setup stages the learner
 * is at, and how identity and network each read at that stage. The stage decides which controls the
 * strip shows - first-time setup, "go online", or a compact running state - so the screen visibly
 * changes character once an identity exists and networking is up. No JavaFX dependency.
 */
public record PeerNetworkPresentation(Stage stage, String identityText, String identityFull, String networkTitle,
                                      String networkDetail, boolean online, String setupHint) {

    public enum Stage {
        NO_IDENTITY, NETWORK_OFF, ONLINE
    }

    /**
     * @param formattedIdentity the grouped fingerprint of this device's identity ({@code null} when none exists yet)
     * @param port              the listening port, when networking is enabled
     */
    public static PeerNetworkPresentation of(String formattedIdentity, boolean networkingEnabled, Optional<Integer> port) {
        if (formattedIdentity == null) {
            return new PeerNetworkPresentation(Stage.NO_IDENTITY, "Not created yet", null, "Not set up",
                    "Create an identity to start pairing with other CodeFit users.", false,
                    "Choose a vault passphrase. It protects your keys and never leaves this device.");
        }
        String shortText = PeerNamePresentation.shortFingerprint(formattedIdentity);
        if (!networkingEnabled) {
            return new PeerNetworkPresentation(Stage.NETWORK_OFF, shortText, formattedIdentity, "Offline",
                    "Peer networking is off.", false, "Enter your vault passphrase to go online.");
        }
        String detail = port.isPresent() ? "Listening on port " + port.get() : "Listening for peers";
        return new PeerNetworkPresentation(Stage.ONLINE, shortText, formattedIdentity, "Online", detail, true, null);
    }
}

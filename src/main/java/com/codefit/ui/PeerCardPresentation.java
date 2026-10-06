package com.codefit.ui;

import java.util.Optional;

/**
 * Pure view-model for the header and action row of one peer card: who the peer is, whether they are
 * reachable, what the one primary action is, and whether Disconnect is on offer. Every input is
 * already-decided backend fact (connected / dialing / networking enabled - read from the same
 * {@code NetworkingService}/{@code PeerDialGate}/{@link PeerConnectionPresenter} calls as before);
 * this class only decides how those facts read on screen. No JavaFX dependency.
 */
public record PeerCardPresentation(String name, String shortName, String spokenName, boolean fingerprintAsName, String initial, String shortFingerprint, String fullFingerprint,
                                   Connection connection, String statusText, String statusDetail,
                                   PrimaryAction primaryAction, boolean canDisconnect) {

    /** The connection pill's semantic state: success / warning / neutral - never red for plain "offline". */
    public enum Connection {
        CONNECTED, CONNECTING, OFFLINE
    }

    /**
     * The contextual lead action. {@link #NONE} while connected: the card's lead action is then
     * "Compare today", and Disconnect lives in the overflow menu instead.
     */
    public enum PrimaryAction {
        NONE, CONNECT, CONNECT_UNAVAILABLE, CONNECTING
    }

    /**
     * @param presenterConnecting the transport presenter's last event says an attempt is underway
     * @param offlineReason       the presenter's reason this contact is offline, if it has one
     */
    public static PeerCardPresentation of(String alias, String claimedName, String fingerprint, boolean networkingEnabled,
                                          boolean connected, boolean dialing, boolean presenterConnecting,
                                          Optional<String> offlineReason) {
        String name = PeerNamePresentation.displayName(alias, claimedName, fingerprint);
        boolean fallbackName = PeerNamePresentation.isFingerprintFallback(alias, claimedName);
        String shortName = PeerNamePresentation.shortName(name, fallbackName);
        String spokenName = PeerNamePresentation.spokenName(name, fallbackName);

        if (connected) {
            return new PeerCardPresentation(name, shortName, spokenName, fallbackName, PeerNamePresentation.initial(name),
                    PeerNamePresentation.shortFingerprint(fingerprint), fingerprint, Connection.CONNECTED,
                    "Connected", null, PrimaryAction.NONE, true);
        }
        if (dialing || presenterConnecting) {
            return new PeerCardPresentation(name, shortName, spokenName, fallbackName, PeerNamePresentation.initial(name),
                    PeerNamePresentation.shortFingerprint(fingerprint), fingerprint, Connection.CONNECTING,
                    "Connecting…", null, dialing ? PrimaryAction.CONNECTING : networkingAction(networkingEnabled), false);
        }
        String detail;
        if (!networkingEnabled) {
            detail = "Turn on networking above to connect.";
        } else {
            detail = offlineReason.map(reason -> PeerNamePresentation.sentence(reason, name, fallbackName)).orElse(null);
        }
        return new PeerCardPresentation(name, shortName, spokenName, fallbackName, PeerNamePresentation.initial(name),
                PeerNamePresentation.shortFingerprint(fingerprint), fingerprint, Connection.OFFLINE,
                "Offline", detail, networkingAction(networkingEnabled), false);
    }

    private static PrimaryAction networkingAction(boolean networkingEnabled) {
        return networkingEnabled ? PrimaryAction.CONNECT : PrimaryAction.CONNECT_UNAVAILABLE;
    }
}

package com.codefit.ui;

import com.codefit.ui.PeerCardPresentation.Connection;
import com.codefit.ui.PeerCardPresentation.PrimaryAction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerCardPresentationTest {
    private static final String FP = "d1bb 752a 4f3e 99aa 00bb 11cc 22dd 33ee 44ff 55aa 66bb 77cc 88dd 99ee dacf b664";

    private static PeerCardPresentation card(String alias, boolean networking, boolean connected, boolean dialing, boolean presenterConnecting,
                                             Optional<String> reason) {
        return PeerCardPresentation.of(alias, "", FP, networking, connected, dialing, presenterConnecting, reason);
    }

    @Test
    void aConnectedPeerLeadsWithCompareAndOffersDisconnect() {
        PeerCardPresentation card = card("Ahmed", true, true, false, false, Optional.empty());
        assertEquals(Connection.CONNECTED, card.connection());
        assertEquals("Connected", card.statusText());
        assertEquals(PrimaryAction.NONE, card.primaryAction(), "Connect makes no sense while connected");
        assertTrue(card.canDisconnect());
        assertNull(card.statusDetail());
    }

    @Test
    void anOfflinePeerOffersConnectAndNoDisconnectAndIsNeutralNotAlarming() {
        PeerCardPresentation card = card("Ahmed", true, false, false, false, Optional.empty());
        assertEquals(Connection.OFFLINE, card.connection());
        assertEquals("Offline", card.statusText());
        assertEquals(PrimaryAction.CONNECT, card.primaryAction());
        assertFalse(card.canDisconnect());
        assertNull(card.statusDetail(), "no reason known, nothing to explain");
    }

    @Test
    void anOfflineReasonBecomesASentenceAboutThatPeersDevice() {
        PeerCardPresentation card = card("Ahmed", true, false, false, false,
                Optional.of("the other device has not imported your invitation yet"));
        assertEquals("Offline", card.statusText(), "the pill stays short");
        assertEquals("Ahmed's device has not imported your invitation yet.", card.statusDetail());
    }

    @Test
    void withNetworkingOffConnectIsUnavailableAndTheCardSaysWhy() {
        PeerCardPresentation card = card("Ahmed", false, false, false, false, Optional.of("could not reach the other device"));
        assertEquals(PrimaryAction.CONNECT_UNAVAILABLE, card.primaryAction());
        assertEquals("Turn on networking above to connect.", card.statusDetail(), "the actionable reason wins over a stale transport reason");
    }

    @Test
    void aManualDialInFlightShowsConnectingAndDisablesTheAction() {
        PeerCardPresentation card = card("Ahmed", true, false, true, false, Optional.empty());
        assertEquals(Connection.CONNECTING, card.connection());
        assertEquals("Connecting…", card.statusText());
        assertEquals(PrimaryAction.CONNECTING, card.primaryAction());
    }

    @Test
    void aTransportConnectingEventAloneShowsConnectingButStillOffersConnect() {
        // Same as before the redesign: only this UI's own in-flight dial disables the button.
        PeerCardPresentation card = card("Ahmed", true, false, false, true, Optional.empty());
        assertEquals(Connection.CONNECTING, card.connection());
        assertEquals(PrimaryAction.CONNECT, card.primaryAction());
    }

    @Test
    void groundTruthConnectedWinsOverAnyDialingOrPresenterState() {
        PeerCardPresentation card = card("Ahmed", true, true, true, true, Optional.of("rejected"));
        assertEquals(Connection.CONNECTED, card.connection());
    }

    @Test
    void aPeerWithNoNameIsTitledByAShortFingerprintAndNeverShowsItTwice() {
        PeerCardPresentation card = card("", true, false, false, false, Optional.empty());
        assertEquals("d1bb 752a … dacf b664", card.name());
        assertTrue(card.fingerprintAsName());
        assertEquals(FP, card.fullFingerprint(), "the full value is kept for the tooltip");
        assertEquals("your peer", card.spokenName());
        assertEquals("Peer", card.shortName());
        assertEquals("D", card.initial());
    }

    @Test
    void aNamedPeerGetsAFirstNameForTablesAndSentences() {
        PeerCardPresentation card = PeerCardPresentation.of("Ahmed Hassan", "", FP, true, true, false, false, Optional.empty());
        assertEquals("Ahmed Hassan", card.name());
        assertEquals("Ahmed", card.shortName());
        assertEquals("Ahmed", card.spokenName());
        assertEquals("d1bb 752a … dacf b664", card.shortFingerprint());
        assertFalse(card.fingerprintAsName());
    }
}

package com.codefit.ui;

import com.codefit.ui.PeerNetworkPresentation.Stage;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerNetworkPresentationTest {
    private static final String FP = "d1bb 752a 4f3e 99aa 00bb 11cc 22dd 33ee 44ff 55aa 66bb 77cc 88dd 99ee dacf b664";

    @Test
    void withoutAnIdentityTheScreenIsInFirstTimeSetup() {
        PeerNetworkPresentation view = PeerNetworkPresentation.of(null, false, Optional.empty());
        assertEquals(Stage.NO_IDENTITY, view.stage());
        assertEquals("Not created yet", view.identityText());
        assertNull(view.identityFull());
        assertFalse(view.online());
        assertTrue(view.setupHint().contains("passphrase"));
    }

    @Test
    void anIdentityWithNetworkingOffAsksToGoOnline() {
        PeerNetworkPresentation view = PeerNetworkPresentation.of(FP, false, Optional.empty());
        assertEquals(Stage.NETWORK_OFF, view.stage());
        assertEquals("d1bb 752a … dacf b664", view.identityText());
        assertEquals(FP, view.identityFull(), "the full fingerprint is kept - it is what gets compared out of band");
        assertEquals("Offline", view.networkTitle());
        assertEquals("Peer networking is off.", view.networkDetail());
        assertFalse(view.online());
    }

    @Test
    void anOnlineIdentityIsCompactAndNeedsNoSetup() {
        PeerNetworkPresentation view = PeerNetworkPresentation.of(FP, true, Optional.of(51234));
        assertEquals(Stage.ONLINE, view.stage());
        assertEquals("Online", view.networkTitle());
        assertEquals("Listening on port 51234", view.networkDetail());
        assertTrue(view.online());
        assertNull(view.setupHint());
        assertEquals("Listening for peers", PeerNetworkPresentation.of(FP, true, Optional.empty()).networkDetail());
    }
}

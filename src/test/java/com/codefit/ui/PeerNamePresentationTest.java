package com.codefit.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerNamePresentationTest {
    private static final String FULL = "d1bb 752a 4f3e 99aa 00bb 11cc 22dd 33ee 44ff 55aa 66bb 77cc 88dd 99ee dacf b664";

    @Test
    void anAliasWinsOverTheClaimedNameWhichWinsOverTheFingerprint() {
        assertEquals("Ahmed", PeerNamePresentation.displayName("Ahmed", "Ahmed Hassan", FULL));
        assertEquals("Ahmed Hassan", PeerNamePresentation.displayName("", "Ahmed Hassan", FULL));
        assertEquals("Ahmed Hassan", PeerNamePresentation.displayName(null, "  Ahmed Hassan ", FULL));
        assertEquals("d1bb 752a … dacf b664", PeerNamePresentation.displayName(" ", "", FULL));
        assertTrue(PeerNamePresentation.isFingerprintFallback(null, ""));
        assertFalse(PeerNamePresentation.isFingerprintFallback("Ahmed", ""));
    }

    @Test
    void theShortFingerprintKeepsTheFirstAndLastTwoGroupsAndNeverTheWholeThing() {
        assertEquals("d1bb 752a … dacf b664", PeerNamePresentation.shortFingerprint(FULL));
        assertEquals("d1bb 752a", PeerNamePresentation.shortFingerprint("d1bb 752a"));
        assertEquals("", PeerNamePresentation.shortFingerprint(null));
    }

    @Test
    void shortAndSpokenNamesFallBackGracefullyAndLongNamesAreBounded() {
        assertEquals("Ahmed", PeerNamePresentation.shortName("Ahmed Hassan", false));
        assertEquals("Peer", PeerNamePresentation.shortName("d1bb 752a … dacf b664", true));
        assertEquals("your peer", PeerNamePresentation.spokenName("d1bb 752a … dacf b664", true));
        assertEquals("Ahmed", PeerNamePresentation.spokenName("Ahmed Hassan", false));
        String longWord = "Supercalifragilisticexpialidocious";
        assertTrue(PeerNamePresentation.shortName(longWord, false).length() <= 14);
        assertEquals("Your peer", PeerNamePresentation.capitalize("your peer"));
    }

    @Test
    void theAvatarInitialIsTheFirstLetterOrDigitUpperCased() {
        assertEquals("A", PeerNamePresentation.initial("ahmed"));
        assertEquals("4", PeerNamePresentation.initial("  4fe"));
        assertEquals("É", PeerNamePresentation.initial("émile"));
        assertEquals("?", PeerNamePresentation.initial("…"));
        assertEquals("?", PeerNamePresentation.initial(null));
    }

    @Test
    void aReasonBecomesASentenceAboutThePeersDeviceAndLongNamesDoNotBloatIt() {
        assertEquals("Ahmed's device has not imported your invitation yet.",
                PeerNamePresentation.sentence("the other device has not imported your invitation yet", "Ahmed", false));
        assertEquals("Their device has not imported your invitation yet.",
                PeerNamePresentation.sentence("the other device has not imported your invitation yet", "d1bb … b664", true));
        assertEquals("Their device has not imported your invitation yet.",
                PeerNamePresentation.sentence("the other device has not imported your invitation yet",
                        "Mohamed Abdelrahman El-Sayed Ibrahim", false));
        assertEquals("Rejected.", PeerNamePresentation.sentence("rejected.", "Ahmed", false));
        assertNull(PeerNamePresentation.sentence(" ", "Ahmed", false));
    }
}

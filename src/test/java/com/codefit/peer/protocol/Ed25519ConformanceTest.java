package com.codefit.peer.protocol;

import org.junit.jupiter.api.Test;

import static com.codefit.peer.protocol.ProtocolFixtures.HEX;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JDK's built-in Ed25519 reproduces RFC 8032 section 7.1 test vectors used by the fixtures. */
class Ed25519ConformanceTest {

    @Test
    void jdkEd25519MatchesRfc8032Test1EmptyMessage() {
        byte[] expected = HEX.parseHex("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
                + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");
        byte[] signature = Ed25519.sign(ProtocolFixtures.privateKey(ProtocolFixtures.AUTHOR_SEED), new byte[0]);
        assertArrayEquals(expected, signature);
        assertTrue(Ed25519.verify(ProtocolFixtures.AUTHOR, new byte[0], signature));
    }

    @Test
    void jdkEd25519MatchesRfc8032Test2And3() {
        byte[] test2 = HEX.parseHex("92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da"
                + "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00");
        assertArrayEquals(test2, Ed25519.sign(ProtocolFixtures.privateKey(ProtocolFixtures.PEER_B_SEED), HEX.parseHex("72")));
        assertTrue(Ed25519.verify(ProtocolFixtures.PEER_B, HEX.parseHex("72"), test2));

        byte[] test3 = HEX.parseHex("6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac"
                + "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a");
        assertArrayEquals(test3, Ed25519.sign(ProtocolFixtures.privateKey(ProtocolFixtures.PEER_C_SEED), HEX.parseHex("af82")));
        assertTrue(Ed25519.verify(ProtocolFixtures.PEER_C, HEX.parseHex("af82"), test3));
    }

    @Test
    void verificationFailsClosedForWrongKeyTamperedMessageAndGarbageKey() {
        byte[] signature = Ed25519.sign(ProtocolFixtures.privateKey(ProtocolFixtures.AUTHOR_SEED), HEX.parseHex("72"));
        assertFalse(Ed25519.verify(ProtocolFixtures.PEER_B, HEX.parseHex("72"), signature));
        assertFalse(Ed25519.verify(ProtocolFixtures.AUTHOR, HEX.parseHex("73"), signature));
        byte[] notAPoint = new byte[IdentityKey.LENGTH];
        java.util.Arrays.fill(notAPoint, (byte) 0xff);
        assertFalse(Ed25519.verify(new IdentityKey(notAPoint), HEX.parseHex("72"), signature));
        assertFalse(Ed25519.verify(ProtocolFixtures.AUTHOR, HEX.parseHex("72"), new byte[10]));
    }
}

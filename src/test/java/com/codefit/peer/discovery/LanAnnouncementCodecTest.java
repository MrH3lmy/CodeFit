package com.codefit.peer.discovery;

import com.codefit.peer.protocol.IdentityId;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LanAnnouncementCodecTest {

    private static IdentityId randomId() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return new IdentityId(bytes);
    }

    @Test
    void recognitionKeyIsSymmetric() {
        IdentityId a = randomId();
        IdentityId b = randomId();
        assertArrayEquals(LanAnnouncementCodec.recognitionKey(a, b), LanAnnouncementCodec.recognitionKey(b, a));
    }

    @Test
    void differentPairsGetDifferentKeys() {
        IdentityId a = randomId();
        IdentityId b = randomId();
        IdentityId c = randomId();
        assertFalse(java.util.Arrays.equals(LanAnnouncementCodec.recognitionKey(a, b), LanAnnouncementCodec.recognitionKey(a, c)));
    }

    @Test
    void encodeThenDecodeRoundTripsAndRecognizes() {
        IdentityId a = randomId();
        IdentityId b = randomId();
        byte[] key = LanAnnouncementCodec.recognitionKey(a, b);
        Instant now = Instant.now();
        long slot = LanAnnouncementCodec.currentTimeSlot(now);
        byte[] tag = LanAnnouncementCodec.computeTag(key, slot, a);
        byte[] packet = LanAnnouncementCodec.encode(slot, 5555, tag);

        Optional<LanAnnouncement> decoded = LanAnnouncementCodec.decode(packet, packet.length,
                new InetSocketAddress("192.168.1.42", 12345), now);
        assertTrue(decoded.isPresent());
        assertEquals(5555, decoded.get().senderPort());
        assertTrue(LanAnnouncementCodec.recognizes(key, decoded.get(), a, now));
    }

    @Test
    void aStrangerWithoutTheRecognitionKeyCannotRecognizeTheAnnouncement() {
        IdentityId a = randomId();
        IdentityId b = randomId();
        IdentityId stranger = randomId();
        byte[] key = LanAnnouncementCodec.recognitionKey(a, b);
        Instant now = Instant.now();
        long slot = LanAnnouncementCodec.currentTimeSlot(now);
        byte[] tag = LanAnnouncementCodec.computeTag(key, slot, a);
        byte[] packet = LanAnnouncementCodec.encode(slot, 5555, tag);
        LanAnnouncement announcement = LanAnnouncementCodec.decode(packet, packet.length,
                new InetSocketAddress("192.168.1.42", 12345), now).orElseThrow();

        byte[] strangerGuessAtKey = LanAnnouncementCodec.recognitionKey(a, stranger);
        assertFalse(LanAnnouncementCodec.recognizes(strangerGuessAtKey, announcement, a, now));
    }

    @Test
    void toleratesOneSlotOfClockSkewButNotMore() {
        IdentityId a = randomId();
        IdentityId b = randomId();
        byte[] key = LanAnnouncementCodec.recognitionKey(a, b);
        Instant now = Instant.now();
        long senderSlot = LanAnnouncementCodec.currentTimeSlot(now) - 1;
        byte[] tag = LanAnnouncementCodec.computeTag(key, senderSlot, a);
        byte[] packet = LanAnnouncementCodec.encode(senderSlot, 1, tag);
        LanAnnouncement announcement = LanAnnouncementCodec.decode(packet, packet.length,
                new InetSocketAddress("192.168.1.42", 1), now).orElseThrow();
        assertTrue(LanAnnouncementCodec.recognizes(key, announcement, a, now));

        long farSlot = LanAnnouncementCodec.currentTimeSlot(now) - 5;
        byte[] farTag = LanAnnouncementCodec.computeTag(key, farSlot, a);
        byte[] farPacket = LanAnnouncementCodec.encode(farSlot, 1, farTag);
        LanAnnouncement farAnnouncement = LanAnnouncementCodec.decode(farPacket, farPacket.length,
                new InetSocketAddress("192.168.1.42", 1), now).orElseThrow();
        assertFalse(LanAnnouncementCodec.recognizes(key, farAnnouncement, a, now));
    }

    /**
     * {@link LanAnnouncementCodec#recognitionKey} is symmetric, so without folding the sender's identity
     * into the tag, a device could mistake its own announcement echoed back to it (loopback delivery, or
     * a switch reflecting multicast) for a real announcement from the very contact it was announcing to.
     */
    @Test
    void aDeviceNeverMistakesItsOwnAnnouncementForOneFromTheContactItIsAnnouncingTo() {
        IdentityId alice = randomId();
        IdentityId bob = randomId();
        byte[] key = LanAnnouncementCodec.recognitionKey(alice, bob);
        Instant now = Instant.now();
        long slot = LanAnnouncementCodec.currentTimeSlot(now);
        // Alice sends this, announcing herself to bob.
        byte[] tag = LanAnnouncementCodec.computeTag(key, slot, alice);
        byte[] packet = LanAnnouncementCodec.encode(slot, 1, tag);
        LanAnnouncement echoedBackToAlice = LanAnnouncementCodec.decode(packet, packet.length,
                new InetSocketAddress("127.0.0.1", 1), now).orElseThrow();

        // Alice must not interpret her own echoed packet as coming from bob.
        assertFalse(LanAnnouncementCodec.recognizes(key, echoedBackToAlice, bob, now));
        // Bob, receiving the same bytes for real, does recognize it as being from alice.
        assertTrue(LanAnnouncementCodec.recognizes(key, echoedBackToAlice, alice, now));
    }

    @Test
    void rejectsMalformedPackets() {
        assertTrue(LanAnnouncementCodec.decode(new byte[]{1, 2, 3}, 3, new InetSocketAddress("10.0.0.1", 1), Instant.now()).isEmpty());
        byte[] wrongMagic = new byte[LanAnnouncementCodec.PACKET_LENGTH];
        assertTrue(LanAnnouncementCodec.decode(wrongMagic, wrongMagic.length, new InetSocketAddress("10.0.0.1", 1), Instant.now()).isEmpty());
    }

    @Test
    void rejectsOversizedGarbageWithoutAllocatingBeyondTheFixedLength() {
        byte[] tooLong = new byte[10_000];
        assertTrue(LanAnnouncementCodec.decode(tooLong, tooLong.length, new InetSocketAddress("10.0.0.1", 1), Instant.now()).isEmpty());
    }
}

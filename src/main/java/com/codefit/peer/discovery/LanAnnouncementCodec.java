package com.codefit.peer.discovery;

import com.codefit.peer.protocol.IdentityId;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Encodes and recognizes #182's optional, opt-in LAN discovery announcements (ADR-0001 §4): a fixed-size
 * link-local multicast UDP packet that reveals no cleartext identity. A stranger on the LAN learns only
 * that some device announced, and when (protocol/ADR "announcements are recognizable only to paired
 * peers"); it cannot tell which identity sent it or match two announcements from the same device without
 * already knowing the pairwise recognition key described below.
 *
 * <p><strong>Recognition key.</strong> Because #182's identity keys are Ed25519 signing keys, not
 * Diffie-Hellman key-agreement keys, there is no ECDH shared secret available without adding a second
 * key-agreement key pair, which is out of scope for this slice. Instead, once two devices are paired,
 * each already knows the <em>other's full public identity key</em> — data a LAN stranger does not have
 * unless they too are a contact who separately learned it. The recognition key is therefore
 * {@code SHA-256(min(idA, idB) || max(idA, idB))}: a value only the two paired devices (or someone who
 * already knows both public keys through some other channel) can compute. This is documented as a
 * deliberate, simpler alternative to a true per-pair secret exchanged at pairing time, not a claim of
 * unlinkability against an adversary who already possesses both identity keys.
 */
final class LanAnnouncementCodec {
    static final int TAG_LENGTH = 32;
    /** magic(4) + version(1) + timeSlot(8) + port(2) + tag(32). */
    static final int PACKET_LENGTH = 4 + 1 + 8 + 2 + TAG_LENGTH;
    /** Time bucket width: an announcement is recognizable for this long, tolerating modest clock skew. */
    static final long SLOT_SECONDS = 30;

    private static final byte[] MAGIC = {'C', 'F', 'L', 'D'};
    private static final int VERSION = 1;
    private static final byte[] MAC_CONTEXT = "CodeFit-LAN-Discovery-v1\0".getBytes(StandardCharsets.US_ASCII);

    private LanAnnouncementCodec() {
    }

    static byte[] recognitionKey(IdentityId a, IdentityId b) {
        byte[] lower = a.compareTo(b) <= 0 ? a.bytes() : b.bytes();
        byte[] higher = a.compareTo(b) <= 0 ? b.bytes() : a.bytes();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(lower);
            digest.update(higher);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java SE platform.", e);
        }
    }

    static long currentTimeSlot(Instant now) {
        return Math.floorDiv(now.getEpochSecond(), SLOT_SECONDS);
    }

    /**
     * {@code senderIdentityId} is never transmitted — both sides already know it locally (the sender
     * because it is their own identity, a receiver because it is trying a specific known contact as the
     * hypothesis) — but folding it into the tag makes the tag <em>directional</em> rather than a pure
     * function of the unordered pair. Without this, {@link #recognitionKey} is symmetric
     * ({@code key(a,b) == key(b,a)}), so a device could misinterpret its own announcement echoed back to
     * it (loopback delivery, or a switch that reflects multicast) as a real announcement from the very
     * contact it was announcing itself to.
     */
    static byte[] computeTag(byte[] recognitionKey, long timeSlot, IdentityId senderIdentityId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(recognitionKey, "HmacSHA256"));
            mac.update(MAC_CONTEXT);
            byte[] slotBytes = new byte[8];
            for (int shift = 56, i = 0; shift >= 0; shift -= 8, i++) {
                slotBytes[i] = (byte) (timeSlot >>> shift);
            }
            mac.update(slotBytes);
            mac.update(senderIdentityId.bytes());
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required by every Java SE platform (SunJCE).", e);
        }
    }

    static byte[] encode(long timeSlot, int listenPort, byte[] tag) {
        if (tag.length != TAG_LENGTH) {
            throw new IllegalArgumentException("tag must be exactly " + TAG_LENGTH + " bytes.");
        }
        if (listenPort < 1 || listenPort > 65535) {
            throw new IllegalArgumentException("listenPort out of range: " + listenPort);
        }
        byte[] out = new byte[PACKET_LENGTH];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        out[4] = (byte) VERSION;
        for (int shift = 56, i = 0; shift >= 0; shift -= 8, i++) {
            out[5 + i] = (byte) (timeSlot >>> shift);
        }
        out[13] = (byte) (listenPort >>> 8);
        out[14] = (byte) listenPort;
        System.arraycopy(tag, 0, out, 15, TAG_LENGTH);
        return out;
    }

    /** Best-effort parse: malformed or foreign UDP traffic (garbage, a different protocol) is silently dropped. */
    static Optional<LanAnnouncement> decode(byte[] packet, int length, InetSocketAddress sender, Instant receivedAt) {
        if (length != PACKET_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < 4; i++) {
            if (packet[i] != MAGIC[i]) {
                return Optional.empty();
            }
        }
        if ((packet[4] & 0xFF) != VERSION) {
            return Optional.empty();
        }
        long timeSlot = 0;
        for (int i = 0; i < 8; i++) {
            timeSlot = (timeSlot << 8) | (packet[5 + i] & 0xFF);
        }
        int port = ((packet[13] & 0xFF) << 8) | (packet[14] & 0xFF);
        byte[] tag = Arrays.copyOfRange(packet, 15, 15 + TAG_LENGTH);
        try {
            return Optional.of(new LanAnnouncement(tag, timeSlot, sender.getAddress().getHostAddress(), port, receivedAt));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Constant-time comparison against the current and adjacent time slots (tolerating modest clock
     * skew), assuming {@code hypothesizedSenderId} is who sent it. A receiver tries this once per paired
     * contact; only the actual sender's identity produces a matching tag (see {@link #computeTag}).
     */
    static boolean recognizes(byte[] recognitionKey, LanAnnouncement announcement, IdentityId hypothesizedSenderId, Instant now) {
        long currentSlot = currentTimeSlot(now);
        for (long slot = currentSlot - 1; slot <= currentSlot + 1; slot++) {
            if (slot == announcement.timeSlot()
                    && MessageDigest.isEqual(computeTag(recognitionKey, slot, hypothesizedSenderId), announcement.recognitionTag())) {
                return true;
            }
        }
        return false;
    }
}

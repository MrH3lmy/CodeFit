package com.codefit.peer.discovery;

import java.time.Instant;
import java.util.Objects;

/**
 * A received link-local multicast announcement, before it is matched against any known contact. Carries
 * no cleartext identity: {@code recognitionTag} is an opaque MAC only a paired contact holding the
 * matching pairwise recognition key can compute and compare, per ADR-0001 §4 ("recognizable only to
 * paired peers"). {@code timeSlot} is the sender's claimed time bucket, echoed back so a receiver can
 * recompute the same tag without guessing which of several nearby slots the sender used.
 */
public record LanAnnouncement(byte[] recognitionTag, long timeSlot, String senderHost, int senderPort, Instant receivedAt) {

    public LanAnnouncement {
        Objects.requireNonNull(recognitionTag, "recognitionTag");
        if (recognitionTag.length != LanAnnouncementCodec.TAG_LENGTH) {
            throw new IllegalArgumentException("recognitionTag must be exactly " + LanAnnouncementCodec.TAG_LENGTH + " bytes.");
        }
        recognitionTag = recognitionTag.clone();
        Objects.requireNonNull(senderHost, "senderHost");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    @Override
    public byte[] recognitionTag() {
        return recognitionTag.clone();
    }
}

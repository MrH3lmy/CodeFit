package com.codefit.peer.protocol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a receiver remembers about one author to classify the next envelope: highest accepted epoch,
 * which message occupied each {@code (epoch, sequence)} slot, latest revision per object, and
 * tombstoned objects. In-memory only in this slice; #184 persists it in SQLite. Entries for a message
 * may be pruned after its {@code expiresAt}, because an expired copy is rejected before replay checks.
 */
public final class AuthorReplayState {
    private final IdentityKey author;
    private long highestEpoch;
    private final Set<MessageId> acceptedMessageIds = new HashSet<>();
    private final Map<Slot, MessageId> slots = new HashMap<>();
    private final Map<ObjectId, Long> objectRevisions = new HashMap<>();
    private final Set<ObjectId> tombstoned = new HashSet<>();

    public AuthorReplayState(IdentityKey author) {
        this.author = author;
    }

    public IdentityKey author() {
        return author;
    }

    public long highestEpoch() {
        return highestEpoch;
    }

    boolean hasAccepted(MessageId id) {
        return acceptedMessageIds.contains(id);
    }

    MessageId slotOccupant(long epoch, long sequence) {
        return slots.get(new Slot(epoch, sequence));
    }

    Long revisionOf(ObjectId objectId) {
        return objectRevisions.get(objectId);
    }

    public boolean isTombstoned(ObjectId objectId) {
        return tombstoned.contains(objectId);
    }

    void record(SignedEnvelope envelope, MessageId id) {
        EnvelopeHeader header = envelope.header();
        highestEpoch = Math.max(highestEpoch, header.epoch());
        acceptedMessageIds.add(id);
        slots.put(new Slot(header.epoch(), header.sequence()), id);
        objectRevisions.put(header.objectId(), header.revision());
        if (envelope.body() instanceof Tombstone) {
            tombstoned.add(header.objectId());
        }
    }

    private record Slot(long epoch, long sequence) {
    }
}

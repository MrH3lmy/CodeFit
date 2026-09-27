package com.codefit.peer.protocol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What a receiver remembers about one author so it can classify that author's next envelope:
 * <ul>
 *   <li>the highest accepted epoch;</li>
 *   <li>which message filled each {@code (epoch, sequence)} slot;</li>
 *   <li>the latest {@code (epoch, revision)} of each object, and whether that latest version is a
 *       tombstone.</li>
 * </ul>
 * This slice keeps the state in memory only; #184 persists it in SQLite. A message's entries may be
 * pruned after its {@code expiresAt}, because an expired copy is rejected before the replay checks
 * run.
 */
public final class AuthorReplayState {
    private final IdentityKey author;
    private long highestEpoch;
    private final Set<MessageId> acceptedMessageIds = new HashSet<>();
    private final Map<Slot, MessageId> slots = new HashMap<>();
    private final Map<ObjectId, ObjectVersion> objects = new HashMap<>();

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

    ObjectVersion versionOf(ObjectId objectId) {
        return objects.get(objectId);
    }

    /** True when the newest accepted version of the object is a tombstone (its cached copy is gone). */
    public boolean isTombstoned(ObjectId objectId) {
        ObjectVersion version = objects.get(objectId);
        return version != null && version.tombstone();
    }

    void record(SignedEnvelope envelope, MessageId id) {
        EnvelopeHeader header = envelope.header();
        highestEpoch = Math.max(highestEpoch, header.epoch());
        acceptedMessageIds.add(id);
        slots.put(new Slot(header.epoch(), header.sequence()), id);
        objects.put(header.objectId(), new ObjectVersion(header.epoch(), header.revision(), envelope.body() instanceof Tombstone));
    }

    private record Slot(long epoch, long sequence) {
    }

    /** Lexicographic {@code (epoch, revision)}: a newer writer epoch supersedes any revision of an older one. */
    record ObjectVersion(long epoch, long revision, boolean tombstone) {
        boolean isNewerThanOrEqualTo(long otherEpoch, long otherRevision) {
            return epoch > otherEpoch || (epoch == otherEpoch && revision >= otherRevision);
        }
    }
}

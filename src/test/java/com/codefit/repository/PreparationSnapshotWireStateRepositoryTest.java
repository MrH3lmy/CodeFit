package com.codefit.repository;

import com.codefit.peer.protocol.ObjectId;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The wire-facing preparation-snapshot object's own revision counter, independent of any local
 * per-day checkpoint revision (see {@code SchemaMigrator.createPreparationSnapshotWireStateTable} for
 * why these must be two different counters).
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PreparationSnapshotWireStateRepositoryTest {

    private final PreparationSnapshotWireStateRepository repository = new PreparationSnapshotWireStateRepository();

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
    }

    private static ObjectId objectId(int fill) {
        byte[] bytes = new byte[ObjectId.LENGTH];
        Arrays.fill(bytes, (byte) fill);
        return new ObjectId(bytes);
    }

    private static byte[] fingerprint(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void firstPublishOfAnObjectGetsRevisionOne() {
        assertEquals(1, repository.nextRevision(objectId(1), fingerprint("day-1-body")));
    }

    @Test
    void unchangedBodyReusesTheSameRevision() {
        ObjectId id = objectId(2);
        long first = repository.nextRevision(id, fingerprint("same-body"));
        long resend = repository.nextRevision(id, fingerprint("same-body"));
        assertEquals(first, resend, "an identical fingerprint must never bump the revision");
    }

    @Test
    void changedBodyBumpsTheRevision() {
        ObjectId id = objectId(3);
        long first = repository.nextRevision(id, fingerprint("day-1-body"));
        long second = repository.nextRevision(id, fingerprint("day-2-body"));
        assertEquals(1, first);
        assertEquals(2, second);
    }

    @Test
    void twoDifferentLocalDaysPublishedToTheSameRecipientNeverCollideOnOneRevision() {
        // The exact bug this table exists to fix: two different UTC days each produce their own local
        // checkpoint, each starting at local revision 1 (see LocalPreparationCheckpointRepositoryTest's
        // differentDaysAreIndependentRevisionStreamsEachStartingAtOne). The wire object for one
        // (author, recipient, profile) is the SAME object id across both days, so reusing the local
        // per-day revision as the wire revision would publish day 2's content as revision 1 again -
        // which a receiver that already accepted day 1's revision 1 would reject as stale/duplicate.
        ObjectId wireObjectId = objectId(4); // one recipient's object id for this profile, constant across days
        long day1WireRevision = repository.nextRevision(wireObjectId, fingerprint("day-1-readiness-content"));
        long day2WireRevision = repository.nextRevision(wireObjectId, fingerprint("day-2-readiness-content"));

        assertEquals(1, day1WireRevision);
        assertEquals(2, day2WireRevision, "day 2's publish must get a strictly higher wire revision than day 1's, "
                + "even though each day's LOCAL checkpoint revision independently started at 1");
    }

    @Test
    void independentObjectsHaveIndependentRevisionStreams() {
        ObjectId recipientA = objectId(5);
        ObjectId recipientB = objectId(6);
        long forA = repository.nextRevision(recipientA, fingerprint("content"));
        long forB = repository.nextRevision(recipientB, fingerprint("content"));
        assertEquals(1, forA);
        assertEquals(1, forB, "a different recipient's object id starts its own stream at 1, independent of A's");
    }
}

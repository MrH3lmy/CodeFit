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
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #183 review fix: {@link PreparationSnapshotWireStateRepository#nextRevision} must stay correct when
 * two threads race to publish the same wire object with genuinely different content concurrently.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PreparationSnapshotWireStateRepositoryConcurrencyTest {

    private final PreparationSnapshotWireStateRepository repository = new PreparationSnapshotWireStateRepository();

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void twoConcurrentRevisionAssignmentsForTheSameObjectNeverCollide() throws Exception {
        byte[] bytes = new byte[ObjectId.LENGTH];
        Arrays.fill(bytes, (byte) 7);
        ObjectId objectId = new ObjectId(bytes);

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> assignAfterBarrier(barrier, objectId, "day-1-content"));
            Future<Long> second = executor.submit(() -> assignAfterBarrier(barrier, objectId, "day-2-content"));

            long revisionA = first.get(10, TimeUnit.SECONDS);
            long revisionB = second.get(10, TimeUnit.SECONDS);

            assertEquals(Set.of(1L, 2L), Set.of(revisionA, revisionB),
                    "two concurrent, genuinely different publishes of the same wire object must never collide on one revision");
        } finally {
            executor.shutdownNow();
        }
    }

    private long assignAfterBarrier(CyclicBarrier barrier, ObjectId objectId, String content) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return repository.nextRevision(objectId, content.getBytes(StandardCharsets.UTF_8));
    }
}

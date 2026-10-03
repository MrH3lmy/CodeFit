package com.codefit.repository;

import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricUnit;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.TimestampBasis;
import com.codefit.peer.snapshot.LocalProgressSnapshot;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #183 review fix: {@link LocalProgressSnapshotRepository#save} must stay correct even when two
 * threads race to capture the <em>same logical window</em> concurrently - opening separate connections
 * could otherwise let both read the same existing revision before either commits, and each
 * independently compute the same "next" revision, silently losing whichever one commits first.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class LocalProgressSnapshotRepositoryConcurrencyTest {

    private final LocalProgressSnapshotRepository repository = new LocalProgressSnapshotRepository();

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
    }

    @Test
    void twoConcurrentSavesOfTheSameWindowNeverReceiveTheSameRevisionAndNeverLoseContent() throws Exception {
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), ZoneId.of("UTC"));
        Instant cutoff = window.end();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LocalProgressSnapshotRepository.SaveResult> first = executor.submit(
                    () -> saveAfterBarrier(barrier, window, cutoff, 1));
            Future<LocalProgressSnapshotRepository.SaveResult> second = executor.submit(
                    () -> saveAfterBarrier(barrier, window, cutoff, 2));

            LocalProgressSnapshotRepository.SaveResult resultA = first.get(10, TimeUnit.SECONDS);
            LocalProgressSnapshotRepository.SaveResult resultB = second.get(10, TimeUnit.SECONDS);

            Set<LocalProgressSnapshotRepository.SaveOutcome> outcomes = Set.of(resultA.outcome(), resultB.outcome());
            assertEquals(Set.of(LocalProgressSnapshotRepository.SaveOutcome.RECORDED_FIRST,
                    LocalProgressSnapshotRepository.SaveOutcome.REPLACED), outcomes,
                    "one save creates the row, the other (genuinely different content) corrects it - never both RECORDED_FIRST");

            Set<Long> revisions = Set.of(resultA.snapshot().revision(), resultB.snapshot().revision());
            assertEquals(Set.of(1L, 2L), revisions,
                    "two concurrent, genuinely different captures of the same window must never collide on one revision number");

            LocalProgressSnapshot finalState = repository.findByWindow(window).orElseThrow();
            assertEquals(2L, finalState.revision(), "the row left behind must be the later, higher-revision content, not lost");
        } finally {
            executor.shutdownNow();
        }
    }

    private LocalProgressSnapshotRepository.SaveResult saveAfterBarrier(CyclicBarrier barrier, ComparisonWindow window,
                                                                          Instant cutoff, int distinctValue) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        List<MetricValue> metrics = List.of(new MetricValue("review.attempts", 1, MetricUnit.COUNT,
                MetricAvailability.MEASURED, distinctValue, distinctValue, MetricProvenance.LOCAL_RECORD, TimestampBasis.LEGACY_SQLITE_UTC));
        return repository.save(window, cutoff, cutoff, metrics);
    }
}

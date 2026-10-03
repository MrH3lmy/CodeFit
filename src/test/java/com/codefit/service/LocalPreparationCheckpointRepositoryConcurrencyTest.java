package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationSnapshots;
import com.codefit.repository.LocalPreparationCheckpointRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * #183 review fix: {@link LocalPreparationCheckpointRepository#save} must stay correct when two
 * threads race to capture the same {@code (profileId, checkpointDateUtc)} checkpoint with genuinely
 * different content concurrently.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class LocalPreparationCheckpointRepositoryConcurrencyTest {
    private static final String PROFILE_ID = "concurrency-test-profile";

    private final LocalPreparationCheckpointRepository repository = new LocalPreparationCheckpointRepository();

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
    }

    private static PreparationSnapshot snapshotWithScore(int score, Instant capturedAt) {
        InterviewRequirement requirement = InterviewRequirement.available("r1", "R", null, InterviewMaterialType.DECK, "k1");
        InterviewDomain domain = new InterviewDomain("d1", "D", null, 100, false, null, List.of(requirement));
        InterviewRequirementReadiness readiness =
                InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.DECK, score, "s");
        InterviewDomainReadiness domainReadiness = InterviewReadinessService.buildDomainReadiness(domain, List.of(readiness));
        InterviewPreparationProfile profile = new InterviewPreparationProfile(PROFILE_ID, "Concurrency Test", null, List.of(domain));
        InterviewReadinessResult result =
                InterviewReadinessService.buildResult(profile, List.of(domainReadiness), InterviewReadinessService.DEFAULT_POLICY);
        return PreparationSnapshots.fromReadiness(profile, result,
                InterviewReadinessService.DEFAULT_POLICY.overallReadinessThresholdPercent(), capturedAt);
    }

    @Test
    void twoConcurrentSavesOfTheSameCheckpointNeverCollideOnOneRevision() throws Exception {
        LocalDate day = LocalDate.of(2026, 1, 15);
        Instant capturedAt = Instant.parse("2026-01-15T12:00:00Z");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<LocalPreparationCheckpointRepository.SaveResult> first = executor.submit(
                    () -> saveAfterBarrier(barrier, day, capturedAt, 70));
            Future<LocalPreparationCheckpointRepository.SaveResult> second = executor.submit(
                    () -> saveAfterBarrier(barrier, day, capturedAt, 90));

            LocalPreparationCheckpointRepository.SaveResult resultA = first.get(10, TimeUnit.SECONDS);
            LocalPreparationCheckpointRepository.SaveResult resultB = second.get(10, TimeUnit.SECONDS);

            Set<LocalPreparationCheckpointRepository.SaveOutcome> outcomes = Set.of(resultA.outcome(), resultB.outcome());
            assertEquals(Set.of(LocalPreparationCheckpointRepository.SaveOutcome.RECORDED_FIRST,
                    LocalPreparationCheckpointRepository.SaveOutcome.REPLACED), outcomes);

            Set<Long> revisions = Set.of(resultA.checkpoint().revision(), resultB.checkpoint().revision());
            assertEquals(Set.of(1L, 2L), revisions,
                    "two concurrent, genuinely different captures of the same checkpoint must never collide on one revision");
        } finally {
            executor.shutdownNow();
        }
    }

    private LocalPreparationCheckpointRepository.SaveResult saveAfterBarrier(CyclicBarrier barrier, LocalDate day,
                                                                               Instant capturedAt, int score) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return repository.save(PROFILE_ID, day, snapshotWithScore(score, capturedAt));
    }
}

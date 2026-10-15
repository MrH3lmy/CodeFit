package com.codefit.service;

import static com.codefit.evolution.evidence.EvidenceTypes.SourceType.*;

import com.codefit.evolution.evidence.EvidenceTypes.SourceType;
import com.codefit.repository.EvidenceMappingRepository;

import java.util.concurrent.*;
import java.util.logging.Logger;

/** Desktop-owned daemon: at most 64 rows per source per tick; DB initialization never starts it. */
public final class EvidenceBackfillWorker implements AutoCloseable {
    private final ScheduledExecutorService executor;

    public EvidenceBackfillWorker() {
        this(
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            var t = new Thread(r, "local-evidence-backfill");
                            t.setDaemon(true);
                            return t;
                        }));
    }

    EvidenceBackfillWorker(ScheduledExecutorService executor) {
        this.executor = executor;
        executor.scheduleWithFixedDelay(this::tick, 5, 30, TimeUnit.SECONDS);
    }

    private void tick() {
        for (var source : new SourceType[] {PROBLEM_ATTEMPT, REVIEW, ASSESSMENT_ATTEMPT}) {
            if (Thread.currentThread().isInterrupted()) return;
            try {
                new EvidenceIngestionService()
                        .ingestBatch(EvidenceMappingRepository.INITIAL_MAPPING, source, 64);
            } catch (RuntimeException failure) {
                Logger.getLogger(EvidenceBackfillWorker.class.getName())
                        .warning(
                                "Local evidence "
                                        + source
                                        + " batch deferred; learning records are unchanged");
            }
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(6, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

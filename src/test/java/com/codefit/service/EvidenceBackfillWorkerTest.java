package com.codefit.service;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.util.concurrent.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class EvidenceBackfillWorkerTest {
    @Test
    void desktopLifecycleSchedulesFiniteTicksAndStopsWithoutWaitingForTimer() throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.execute(
                    "INSERT INTO problems(platform,external_code,title)"
                        + " VALUES('EVWORKER','p','Fixture')");
            for (int i = 1; i <= 70; i++) {
                s.execute(
                        "INSERT INTO"
                            + " problem_attempts(problem_id,attempt_number,submission_result,completion_origin)"
                            + " SELECT MAX(id),"
                                + i
                                + ",'AC','FRESH_ATTEMPT' FROM problems");
                s.execute(
                        "INSERT INTO"
                            + " assessment_attempts(assessment_item_id,variant_index,skill_category,module_name,correct)"
                            + " SELECT MIN(id),0,'Concurrency','m',1 FROM assessment_items");
            }
        }
        var scheduler = new CapturingScheduler();
        try (var worker = new EvidenceBackfillWorker(scheduler)) {
            assertEquals(5, scheduler.initial);
            assertEquals(30, scheduler.delay);
            assertEquals(TimeUnit.SECONDS, scheduler.unit);
            assertEquals(0, count());
            scheduler.tick.run();
            assertEquals(128, count());
            scheduler.tick.run();
            assertEquals(140, count());
            scheduler.tick.run();
            assertEquals(140, count());
        }
        assertTrue(scheduler.isShutdown());
        assertTrue(scheduler.awaitTermination(1, TimeUnit.SECONDS));
    }

    private long count() throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM evolution_evidence_observations")) {
            r.next();
            return r.getLong(1);
        }
    }

    private static final class CapturingScheduler extends ScheduledThreadPoolExecutor {
        Runnable tick;
        long initial, delay;
        TimeUnit unit;

        CapturingScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initial, long delay, TimeUnit unit) {
            this.tick = command;
            this.initial = initial;
            this.delay = delay;
            this.unit = unit;
            return super.scheduleWithFixedDelay(() -> {}, 1, 1, TimeUnit.DAYS);
        }
    }
}

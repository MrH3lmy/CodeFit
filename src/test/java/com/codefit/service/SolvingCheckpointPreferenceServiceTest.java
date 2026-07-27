package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the solving workspace's coaching-checkpoint preference (#145): default thresholds,
 * enabling/disabling, changing the thresholds, and detecting a newly-crossed checkpoint. Touches the
 * shared local database like every other preference-backed test in this suite, always restoring the
 * default checkpoint configuration afterward so it doesn't leak into unrelated tests.
 */
class SolvingCheckpointPreferenceServiceTest {

    private final SolvingCheckpointPreferenceService checkpointService = new SolvingCheckpointPreferenceService();

    @BeforeAll
    static void initializeDatabase() {
        DatabaseConfig.initialize();
    }

    @Test
    void defaultCheckpointsAreTwentySixtyAndOneHundredTwentyMinutesAndEnabled() {
        checkpointService.setCheckpointsEnabled(true);
        checkpointService.setCheckpointMinutes(List.of(20, 60, 120));

        assertTrue(checkpointService.isCheckpointsEnabled());
        assertEquals(List.of(20, 60, 120), checkpointService.getCheckpointMinutes());
    }

    @Test
    void checkpointsCanBeDisabled() {
        checkpointService.setCheckpointsEnabled(false);
        try {
            assertFalse(checkpointService.isCheckpointsEnabled());
            assertTrue(checkpointService.findNewlyCrossedCheckpoint(19 * 60, 21 * 60).isEmpty(),
                    "a disabled checkpoint preference must never report a crossing");
        } finally {
            checkpointService.setCheckpointsEnabled(true);
        }
    }

    @Test
    void checkpointMinutesCanBeChangedDeduplicatedAndSorted() {
        checkpointService.setCheckpointMinutes(List.of(90, 30, 30, 10));
        try {
            assertEquals(List.of(10, 30, 90), checkpointService.getCheckpointMinutes());
        } finally {
            checkpointService.setCheckpointMinutes(List.of(20, 60, 120));
        }
    }

    @Test
    void findNewlyCrossedCheckpointReportsTheLowestThresholdJustCrossed() {
        checkpointService.setCheckpointsEnabled(true);
        checkpointService.setCheckpointMinutes(List.of(20, 60, 120));

        Optional<Integer> crossedAtTwenty = checkpointService.findNewlyCrossedCheckpoint(19 * 60, 20 * 60);
        assertTrue(crossedAtTwenty.isPresent());
        assertEquals(20, crossedAtTwenty.get());

        assertTrue(checkpointService.findNewlyCrossedCheckpoint(10 * 60, 15 * 60).isEmpty(),
                "no checkpoint threshold was reached in this step");
        assertTrue(checkpointService.findNewlyCrossedCheckpoint(20 * 60, 25 * 60).isEmpty(),
                "the 20-minute checkpoint was already crossed before this step");
    }

    @Test
    void findNewlyCrossedCheckpointCanSkipMultipleThresholdsInOneStep() {
        checkpointService.setCheckpointsEnabled(true);
        checkpointService.setCheckpointMinutes(List.of(20, 60, 120));

        // A long uninterrupted tick (e.g. resuming after being closed) can jump past more than one
        // threshold at once; only the lowest newly-crossed one is reported per call.
        Optional<Integer> crossed = checkpointService.findNewlyCrossedCheckpoint(10 * 60, 200 * 60);
        assertTrue(crossed.isPresent());
        assertEquals(20, crossed.get());
    }
}

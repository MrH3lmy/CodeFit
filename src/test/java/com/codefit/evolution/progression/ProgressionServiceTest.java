package com.codefit.evolution.progression;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.mastery.MasteryEstimateService;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProgressionServiceTest {
    private final ProgressionService service = new ProgressionService();

    @BeforeEach
    void resetOpeningBalance() throws Exception {
        try (var c = DatabaseConfig.getConnection(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE user_progress SET xp=0,level=1,streak_days=0 WHERE id=1");
            s.executeUpdate("UPDATE evolution_reward_preferences SET enabled=1 WHERE id=1");
        }
    }

    @Test
    void optOutPreferenceIsPersistedWithoutChangingOpeningProgress() {
        service.setRewardsEnabled(false);
        var disabled = service.snapshot();
        assertFalse(disabled.rewardsEnabled());
        assertEquals(0, disabled.xp());

        service.setRewardsEnabled(true);
        assertTrue(service.snapshot().rewardsEnabled());
        assertEquals(0, service.snapshot().xp());
    }

    @Test
    void xpBalanceDoesNotAlterMasteryEstimateAndUnlocksAreDeterministic() throws Exception {
        var skill = JavaDebuggingSkillSeed.graph().definitions().getFirst().ref();
        var before =
                new MasteryEstimateService()
                        .estimate(
                                JavaDebuggingSkillSeed.GRAPH,
                                skill,
                                null,
                                Instant.parse("2026-06-12T12:00:00Z"));
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            s.executeUpdate("UPDATE user_progress SET xp=105,level=2 WHERE id=1");
        }
        var after =
                new MasteryEstimateService()
                        .estimate(
                                JavaDebuggingSkillSeed.GRAPH,
                                skill,
                                null,
                                Instant.parse("2026-06-12T12:00:00Z"));
        assertEquals(before.estimate(), after.estimate());

        var earned =
                new ProgressionService.Entitlement(
                        1,
                        "independent:skill:v1",
                        ProgressionService.RewardType.INDEPENDENT_MILESTONE,
                        "skill",
                        1,
                        25,
                        "AWARDED",
                        "estimate:1",
                        "first independent",
                        true,
                        "1",
                        "now");
        var optedOut =
                new ProgressionService.Entitlement(
                        2,
                        "retention:other:v1",
                        ProgressionService.RewardType.RETENTION_MILESTONE,
                        "other",
                        1,
                        0,
                        "OPTED_OUT",
                        "estimate:2",
                        "opted out",
                        false,
                        "1",
                        "later");
        var effort =
                new ProgressionService.Entitlement(
                        3,
                        "effort:quest:v1",
                        ProgressionService.RewardType.EFFORT,
                        "quest",
                        1,
                        10,
                        "AWARDED",
                        "submission:3",
                        "effort",
                        true,
                        "1",
                        "later");
        assertEquals(
                Set.of("INDEPENDENT_MILESTONE:skill"),
                ProgressionPolicy.unlockedMilestones(List.of(earned, optedOut, effort)));
        assertEquals(1, ProgressionPolicy.levelForXp(99));
        assertEquals(2, ProgressionPolicy.levelForXp(100));
        assertEquals(
                "C-Rank Backend Builder", ProgressionPolicy.engagementRank(30, false));
        assertEquals(
                "S-Rank Backend Architect", ProgressionPolicy.engagementRank(30, true));
    }

    @Test
    void rewardHistoryIsBoundedAndFullyAvailableThroughDeterministicPages() throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                """
                                INSERT INTO evolution_reward_entitlements
                                  (entitlement_key,reward_type,subject_id,definition_version,
                                   policy_version,xp_awarded,status,source_ref,reason)
                                VALUES(?,'EFFORT',?,1,'1',10,'AWARDED',?,'fixture')
                                """)) {
            for (int i = 0; i < 140; i++) {
                s.setString(1, "history:" + i);
                s.setString(2, "quest-" + i);
                s.setString(
                        3,
                        i == 139
                                ? "estimate:fixture:evidence:"
                                        + "0000000000000000000000000000000000000000000000000000000000000000"
                                : "submission:" + i);
                s.addBatch();
            }
            s.executeBatch();
        }

        assertEquals(25, service.snapshot().history().size());
        assertEquals(
                64,
                service.historyPage(null, 64).entries().size());
        assertThrows(IllegalArgumentException.class, () -> service.historyPage(null, 65));
        assertFalse(service.historyPage(null, 64).entries().get(0).proofAvailable());

        var all = new java.util.ArrayList<ProgressionService.Entitlement>();
        Long cursor = null;
        do {
            var page = service.historyPage(cursor, 37);
            all.addAll(page.entries());
            cursor = page.nextBeforeSequence();
        } while (cursor != null);
        assertEquals(140, all.size());
        assertTrue(
                all.stream()
                        .map(ProgressionService.Entitlement::sequence)
                        .reduce((first, second) -> {
                            assertTrue(first > second);
                            return second;
                        })
                        .isPresent());
    }
}

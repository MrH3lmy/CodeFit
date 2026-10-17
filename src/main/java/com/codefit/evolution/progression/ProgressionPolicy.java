package com.codefit.evolution.progression;

import java.util.*;

/** Frozen V1 engagement reward amounts and deterministic level thresholds. */
public final class ProgressionPolicy {
    public static final String VERSION = "1";
    public static final int XP_PER_LEVEL = 100;
    public static final int CONSISTENT_STREAK_DAYS = 7;
    public static final int EXPERIENCED_REVIEW_COUNT = 50;
    public static final int EFFORT_XP = 10;
    public static final int INDEPENDENT_MILESTONE_XP = 25;
    public static final int RETENTION_MILESTONE_XP = 25;

    private ProgressionPolicy() {}

    public static int levelForXp(int xp) {
        if (xp < 0) throw new IllegalArgumentException("XP cannot be negative");
        return (xp / XP_PER_LEVEL) + 1;
    }

    public static String engagementRank(int level, boolean consistent) {
        if (level < 1) throw new IllegalArgumentException("Level must be positive");
        if (level >= 30 && consistent) return "S-Rank Backend Architect";
        if (level >= 24 && consistent) return "A-Rank Backend Lead";
        if (level >= 18 && consistent) return "B-Rank Backend Specialist";
        if (level >= 10) return "C-Rank Backend Builder";
        if (level >= 5) return "D-Rank Backend Apprentice";
        return "E-Rank Backend Starter";
    }

    public static Set<String> unlockedMilestones(
            Collection<ProgressionService.Entitlement> entitlements) {
        Set<String> unlocked = new TreeSet<>();
        for (var entitlement : entitlements)
            if ("AWARDED".equals(entitlement.status())
                    && entitlement.type() != ProgressionService.RewardType.EFFORT)
                unlocked.add(entitlement.type().name() + ":" + entitlement.subject());
        return Collections.unmodifiableSet(unlocked);
    }
}

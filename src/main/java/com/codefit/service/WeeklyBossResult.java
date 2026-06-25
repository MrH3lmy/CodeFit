package com.codefit.service;

import java.util.List;

public record WeeklyBossResult(
        boolean hasSignal,
        int reviewedCards,
        double scorePercent,
        List<String> weakAreas,
        String recommendedFocus
) {
    public static WeeklyBossResult empty() {
        return new WeeklyBossResult(false, 0, 0.0, List.of(), "Complete a weekly boss battle to unlock a training focus.");
    }
}

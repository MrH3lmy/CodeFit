package com.codefit.service;

/**
 * The fixed order of the guided daily routine (#111): review due/relearning cards, optionally
 * capture a reflection, optionally take the weekly transfer assessment when due, then a completion
 * summary. {@link GuidedTrainingService#resolveCurrentStage} decides which of these to show next
 * from whichever stages are already marked done, so re-entering the routine after leaving mid-way
 * resumes rather than restarting.
 */
public enum GuidedStage {
    REVIEW,
    REFLECTION,
    WEEKLY_ASSESSMENT,
    COMPLETE
}

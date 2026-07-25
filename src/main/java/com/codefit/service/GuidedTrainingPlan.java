package com.codefit.service;

import java.util.List;

/**
 * What today's guided routine (#111) looks like before the learner starts: the adaptive review
 * queue already respecting time budget/new-card limit/focus module/due-card priority (built by
 * {@link ReviewService#getAdaptiveSessionCards}, not recomputed here), whether there's genuinely
 * review work to do, whether there's realistically time left for the optional stages, how many
 * cards are flagged for a rewrite, and whether this week's transfer assessment is due.
 *
 * @param sessionMinutes             the (clamped) session length this plan was built for
 * @param reviewPlan                 the adaptive due/relearning/new-card queue for stage 1+2
 * @param hasReviewWork              false when there is nothing due or new to review right now
 * @param hasTimeForOptionalStages   false when the review queue already consumes essentially the
 *                                   whole session budget, so reflection/assessment are flagged as
 *                                   "skip if short on time" rather than assumed to fit
 * @param cardsNeedingRewrite        leech cards surfaced for a rewrite (#103); already included in
 *                                   {@code reviewPlan} under the "Needs rewrite" composition bucket,
 *                                   this is just the count for the completion/landing summary
 * @param weeklyAssessmentDue        true only when a week has passed since the last run and there
 *                                   are assessment items to serve
 * @param weeklyAssessmentItems      the items to offer; empty unless {@code weeklyAssessmentDue}
 */
public record GuidedTrainingPlan(int sessionMinutes, ReviewService.AdaptiveSessionPlan reviewPlan,
                                 boolean hasReviewWork, boolean hasTimeForOptionalStages,
                                 int cardsNeedingRewrite, boolean weeklyAssessmentDue,
                                 List<WeeklyAssessmentSelectionService.SelectedAssessment> weeklyAssessmentItems) {
}

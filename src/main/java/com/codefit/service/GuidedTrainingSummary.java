package com.codefit.service;

/**
 * The guided routine's completion summary (#111): what was retained, what's still missed, what was
 * recovered after an initial miss, and what new knowledge the reflection step captured, plus this
 * week's transfer assessment result if it was taken today. Built once, from today's persisted
 * review/reflection/assessment records, so it reflects every review recorded that day regardless of
 * which optional stages the learner completed or skipped.
 */
public record GuidedTrainingSummary(int cardsReviewed, int retainedCount, int missedCount, int recoveredCount,
                                    int newlyCapturedCount, boolean weeklyAssessmentTaken,
                                    int weeklyAssessmentCorrect, int weeklyAssessmentTotal) {
}

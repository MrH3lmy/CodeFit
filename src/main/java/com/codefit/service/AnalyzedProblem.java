package com.codefit.service;

import com.codefit.model.ProblemState;
import com.codefit.model.SolvedWith;
import com.codefit.model.SubmissionResult;

import java.util.List;

/**
 * One unique problem found anywhere in an analyzed workbook (#160), keyed by {@code (platform,
 * externalCode)} — the same problem appearing in two roadmap sheets (e.g. Stage A and Stage C2)
 * collapses to exactly one {@code AnalyzedProblem} plus multiple {@link AnalyzedRoadmapMembership}
 * entries, entirely in memory, with no database lookup involved.
 *
 * <p>Catalog fields ({@code title}/{@code url}/{@code topic}/{@code qualityRating}/
 * {@code learningResources}) reflect the last workbook row seen for this key, matching
 * {@link ProblemService#upsertProblem}'s existing "the newest row's values win" behavior — including
 * a later {@code Topics} sheet row overriding {@code topic} again.
 *
 * <p>The remaining fields are the workbook's per-problem aggregate performance data (#159): at most
 * one imported progress state, one {@code ProblemAttempt} snapshot, and one set of reflection fields,
 * each taken from the <em>first</em> row (in workbook processing order) that supplies a value for it —
 * replicating {@link ProblemProgressService#applyImportedState}/{@code #applyImportedReflection}'s
 * existing "first row to arrive at an unset field wins, later rows are silently skipped" contract,
 * but computed once here during analysis instead of via a live database check per row.
 */
public record AnalyzedProblem(
        String key,
        String platform,
        String externalCode,
        String title,
        String url,
        String topic,
        Integer qualityRating,
        List<String> learningResources,
        ProblemState importedState,
        SubmissionResult submissionResult,
        Integer submitCount,
        Integer readingSeconds,
        Integer thinkingSeconds,
        Integer codingSeconds,
        Integer debuggingSeconds,
        String attemptNotes,
        Integer perceivedDifficulty,
        SolvedWith solvedWith,
        String actualTopic,
        String approachNotes) {
}

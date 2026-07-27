package com.codefit.service;

import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.SolvedWith;
import com.codefit.repository.ProblemProgressRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * Guarantees each problem has exactly one {@link ProblemProgress} record: {@link #getOrCreate(long)}
 * creates the {@code NOT_STARTED} row the first time a problem is touched, and every later call
 * updates that same row rather than inserting another one (#142).
 *
 * <p>Two update methods deliberately cover disjoint fields (#146):
 * <ul>
 *   <li>{@link #updateProgress} only ever changes workflow state ({@code state}/{@code completedAt}) —
 *       this is what {@code ProblemSolvingWorkspaceService#finish} calls, so finishing a session can
 *       never accidentally blank out a reflection the learner already recorded.</li>
 *   <li>{@link #updateReflection} only ever changes the post-solve reflection fields, and never
 *       touches {@code state}/{@code completedAt} — reflection is optional and editable at any time,
 *       independent of where the problem currently sits in the workflow.</li>
 * </ul>
 *
 * <p>{@link #applyImportedState} is the workbook importer's (#143) only way to touch progress: it
 * only ever fills in a state from a still-{@code NOT_STARTED} record, so a re-import (or a workbook
 * with a blank/stale status column) can never downgrade or blank out progress the learner has
 * already recorded locally.
 */
public class ProblemProgressService {

    private final ProblemProgressRepository progressRepository;

    public ProblemProgressService() {
        this(new ProblemProgressRepository());
    }

    public ProblemProgressService(ProblemProgressRepository progressRepository) {
        this.progressRepository = progressRepository;
    }

    public ProblemProgress getOrCreate(long problemId) {
        return progressRepository.findByProblemId(problemId)
                .orElseGet(() -> progressRepository.save(ProblemProgress.notStarted(problemId)));
    }

    public ProblemProgress getOrCreate(Connection connection, long problemId) throws SQLException {
        return progressRepository.findByProblemId(connection, problemId)
                .orElseGet(() -> {
                    try {
                        return progressRepository.save(connection, ProblemProgress.notStarted(problemId));
                    } catch (SQLException exception) {
                        throw new IllegalStateException("Unable to create problem progress", exception);
                    }
                });
    }

    /** Updates only the workflow state; never touches any reflection field. */
    public ProblemProgress updateProgress(long problemId, ProblemState state, LocalDateTime completedAt) {
        ProblemProgress progress = getOrCreate(problemId);
        progress.setState(state);
        progress.setCompletedAt(completedAt);
        progressRepository.update(progress);
        return progressRepository.findByProblemId(problemId).orElseThrow();
    }

    /**
     * Updates the post-solve reflection fields; never touches {@code state}/{@code completedAt}.
     * Reflection is optional and can be recorded or edited at any time, any number of times.
     */
    public ProblemProgress updateReflection(long problemId, ProblemReflection reflection) {
        ProblemProgress progress = getOrCreate(problemId);
        progress.setPerceivedDifficultyRating(reflection.perceivedDifficultyRating());
        progress.setSolvedWith(reflection.solvedWith());
        progress.setFinalCategory(reflection.finalCategory());
        progress.setApproachNotes(reflection.approachNotes());
        progress.setMistakeNotes(reflection.mistakeNotes());
        progress.setImportantObservation(reflection.importantObservation());
        progress.setTimeComplexity(reflection.timeComplexity());
        progress.setSpaceComplexity(reflection.spaceComplexity());
        progress.setLessonLearned(reflection.lessonLearned());
        progress.setActualTopic(reflection.actualTopic());
        progress.setEditorialUnderstood(reflection.editorialUnderstood());
        progress.setOtherSolutionsReviewed(reflection.otherSolutionsReviewed());
        progress.setSimplerImplementationConsidered(reflection.simplerImplementationConsidered());
        progress.setBetterComplexityConsidered(reflection.betterComplexityConsidered());
        progressRepository.update(progress);
        return progressRepository.findByProblemId(problemId).orElseThrow();
    }

    /**
     * Applies a state read from an imported workbook row, but only if the problem's existing
     * progress is still {@code NOT_STARTED} — i.e. the learner hasn't recorded anything locally yet.
     * If the learner has already moved the problem forward (or recorded any progress at all), the
     * imported value is silently ignored rather than overwriting it, satisfying "never overwrite a
     * newer/blank-or-older imported value" without needing a modification timestamp from the
     * workbook, which the source spreadsheet doesn't reliably provide.
     *
     * @return {@code true} if the imported state was applied, {@code false} if existing progress was left untouched
     */
    public boolean applyImportedState(Connection connection, long problemId, ProblemState importedState) throws SQLException {
        if (importedState == null || importedState == ProblemState.NOT_STARTED) {
            return false;
        }
        ProblemProgress progress = getOrCreate(connection, problemId);
        if (progress.getState() != ProblemState.NOT_STARTED) {
            return false;
        }
        progress.setState(importedState);
        progressRepository.update(connection, progress);
        return true;
    }

    /**
     * Fills in reflection fields read from an imported workbook row (#159), one field at a time and
     * only where that specific field is still unset — a learner's own already-recorded difficulty
     * rating, assistance level, actual topic, or approach notes is never overwritten by a (re-)import,
     * the same non-destructive contract {@link #applyImportedState} already gives the workflow state.
     *
     * @return {@code true} if at least one field was filled in
     */
    public boolean applyImportedReflection(Connection connection, long problemId, Integer perceivedDifficultyRating,
                                           SolvedWith solvedWith, String actualTopic, String approachNotes) throws SQLException {
        ProblemProgress progress = getOrCreate(connection, problemId);
        boolean changed = false;
        if (progress.getPerceivedDifficultyRating() == null && perceivedDifficultyRating != null) {
            progress.setPerceivedDifficultyRating(perceivedDifficultyRating);
            changed = true;
        }
        if (progress.getSolvedWith() == null && solvedWith != null) {
            progress.setSolvedWith(solvedWith);
            changed = true;
        }
        if ((progress.getActualTopic() == null || progress.getActualTopic().isBlank()) && actualTopic != null) {
            progress.setActualTopic(actualTopic);
            changed = true;
        }
        if ((progress.getApproachNotes() == null || progress.getApproachNotes().isBlank()) && approachNotes != null) {
            progress.setApproachNotes(approachNotes);
            changed = true;
        }
        if (changed) {
            progressRepository.update(connection, progress);
        }
        return changed;
    }
}

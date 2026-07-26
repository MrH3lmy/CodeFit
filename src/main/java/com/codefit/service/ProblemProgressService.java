package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.FinalCategory;
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
 * <p>{@link #applyImportedState} is the workbook importer's (#143) only way to touch progress: it
 * only ever fills in a state from a still-{@code NOT_STARTED} record, so a re-import (or a workbook
 * with a blank/stale status column) can never downgrade or blank out progress the learner has
 * already recorded locally — see the "never overwrite a newer record" working rule for this epic.
 * The general {@link #updateProgress} overwrite-in-place method is for interactive editing (#146),
 * where the learner is deliberately setting their own progress.
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

    public ProblemProgress updateProgress(long problemId, ProblemState state, DifficultyLevel perceivedDifficulty,
                                          SolvedWith solvedWith, FinalCategory finalCategory, String approachNotes,
                                          String mistakeNotes, LocalDateTime completedAt) {
        ProblemProgress progress = getOrCreate(problemId);
        progress.setState(state);
        progress.setPerceivedDifficulty(perceivedDifficulty);
        progress.setSolvedWith(solvedWith);
        progress.setFinalCategory(finalCategory);
        progress.setApproachNotes(approachNotes);
        progress.setMistakeNotes(mistakeNotes);
        progress.setCompletedAt(completedAt);
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
}

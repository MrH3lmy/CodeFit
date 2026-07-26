package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.FinalCategory;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.SolvedWith;
import com.codefit.repository.ProblemProgressRepository;

import java.time.LocalDateTime;

/**
 * Guarantees each problem has exactly one {@link ProblemProgress} record: {@link #getOrCreate(long)}
 * creates the {@code NOT_STARTED} row the first time a problem is touched, and every later call
 * updates that same row rather than inserting another one (#142). Import-time "never overwrite a
 * newer local record with a blank or older imported value" guarding is deliberately left to the
 * workbook importer (#143), which is the only caller that needs to compare against external data.
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
}

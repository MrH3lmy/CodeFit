package com.codefit.service;

import com.codefit.model.ProblemAttempt;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ProblemAttemptRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Records {@link ProblemAttempt}s, computing each new attempt's 1-based {@code attemptNumber} from
 * how many attempts the problem already has so a problem's attempt history is always a gapless,
 * strictly increasing sequence (#142).
 */
public class ProblemAttemptService {

    private final ProblemAttemptRepository attemptRepository;

    public ProblemAttemptService() {
        this(new ProblemAttemptRepository());
    }

    public ProblemAttemptService(ProblemAttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    public List<ProblemAttempt> getAttempts(long problemId) {
        return attemptRepository.findByProblemId(problemId);
    }

    public ProblemAttempt recordAttempt(long problemId, SubmissionResult submissionResult, Integer readingTimeSeconds,
                                        Integer thinkingTimeSeconds, Integer codingTimeSeconds,
                                        Integer debuggingTimeSeconds, String notes) {
        if (submissionResult == null) {
            throw new IllegalArgumentException("A submission result is required to record an attempt.");
        }
        int nextAttemptNumber = attemptRepository.countByProblemId(problemId) + 1;
        ProblemAttempt attempt = new ProblemAttempt(0, problemId, nextAttemptNumber, submissionResult,
                readingTimeSeconds, thinkingTimeSeconds, codingTimeSeconds, debuggingTimeSeconds,
                LocalDateTime.now(), notes);
        return attemptRepository.save(attempt);
    }
}

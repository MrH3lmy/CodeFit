package com.codefit.service;

import com.codefit.model.ProblemAttempt;
import com.codefit.model.SessionFinishOutcome;
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

    /**
     * Whether the problem's very first submission was itself a success (#146). {@code ACX} counts:
     * it means the learner already knew the solution going in, which is still "got it right the
     * first time" for accuracy purposes, just not from a fresh timed attempt. Returns {@code false}
     * for a problem with no attempts yet, rather than throwing, so it can be safely used while
     * computing accuracy across a whole roadmap.
     */
    public boolean isFirstSubmissionAccurate(long problemId) {
        return isFirstSubmissionAccurate(getAttempts(problemId));
    }

    static boolean isFirstSubmissionAccurate(List<ProblemAttempt> attempts) {
        return attempts.stream()
                .filter(attempt -> attempt.attemptNumber() == 1)
                .findFirst()
                .map(attempt -> attempt.submissionResult() == SubmissionResult.AC || attempt.submissionResult() == SubmissionResult.ACX)
                .orElse(false);
    }

    public ProblemAttempt recordAttempt(long problemId, SubmissionResult submissionResult, Integer readingTimeSeconds,
                                        Integer thinkingTimeSeconds, Integer codingTimeSeconds,
                                        Integer debuggingTimeSeconds, String notes) {
        return recordAttempt(problemId, submissionResult, readingTimeSeconds, thinkingTimeSeconds, codingTimeSeconds,
                debuggingTimeSeconds, notes, null);
    }

    /** Same as the five-argument overload, but tags the attempt with the workspace finish reason that produced it (#145). */
    public ProblemAttempt recordAttempt(long problemId, SubmissionResult submissionResult, Integer readingTimeSeconds,
                                        Integer thinkingTimeSeconds, Integer codingTimeSeconds,
                                        Integer debuggingTimeSeconds, String notes, SessionFinishOutcome sessionOutcome) {
        if (submissionResult == null) {
            throw new IllegalArgumentException("A submission result is required to record an attempt.");
        }
        int nextAttemptNumber = attemptRepository.countByProblemId(problemId) + 1;
        ProblemAttempt attempt = new ProblemAttempt(0, problemId, nextAttemptNumber, submissionResult,
                readingTimeSeconds, thinkingTimeSeconds, codingTimeSeconds, debuggingTimeSeconds,
                LocalDateTime.now(), notes, sessionOutcome);
        return attemptRepository.save(attempt);
    }
}

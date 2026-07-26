package com.codefit.service;

import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.SolvingPhase;
import com.codefit.repository.ProblemSolvingSessionRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Manages each problem's single persistent, resumable {@link ProblemSolvingSession} (#142). The
 * full workspace UI and phase-transition workflow belongs to #145; this service only guarantees the
 * "at most one session per problem" invariant and basic timer bookkeeping so that later work has a
 * stable foundation to build on.
 */
public class ProblemSolvingSessionService {

    private final ProblemSolvingSessionRepository sessionRepository;

    public ProblemSolvingSessionService() {
        this(new ProblemSolvingSessionRepository());
    }

    public ProblemSolvingSessionService(ProblemSolvingSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public Optional<ProblemSolvingSession> findSession(long problemId) {
        return sessionRepository.findByProblemId(problemId);
    }

    /** Returns the problem's existing session, or starts a fresh one at the READING phase. */
    public ProblemSolvingSession startOrResume(long problemId) {
        return sessionRepository.findByProblemId(problemId)
                .orElseGet(() -> sessionRepository.save(ProblemSolvingSession.start(problemId)));
    }

    public ProblemSolvingSession recordElapsedTime(long problemId, SolvingPhase phase, int seconds) {
        ProblemSolvingSession session = startOrResume(problemId);
        session.addElapsedSeconds(phase, seconds);
        session.setPhase(phase);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.update(session);
        return sessionRepository.findByProblemId(problemId).orElseThrow();
    }

    public ProblemSolvingSession updateNotes(long problemId, String notes) {
        ProblemSolvingSession session = startOrResume(problemId);
        session.setNotes(notes);
        sessionRepository.update(session);
        return sessionRepository.findByProblemId(problemId).orElseThrow();
    }

    /** Marks the session inactive without deleting it, so its accumulated timers survive until reset. */
    public void endSession(long problemId) {
        sessionRepository.findByProblemId(problemId).ifPresent(session -> {
            session.setActive(false);
            sessionRepository.update(session);
        });
    }

    /** Clears all in-progress timer state for a problem, e.g. once a submission has been finalized. */
    public void reset(long problemId) {
        sessionRepository.deleteByProblemId(problemId);
    }
}

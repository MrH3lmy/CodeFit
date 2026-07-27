package com.codefit.service;

import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.SolvingPhase;
import com.codefit.repository.ProblemSolvingSessionRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Manages each problem's single persistent, resumable {@link ProblemSolvingSession} (#142/#145).
 * {@link #pause}/{@link #resume} are pure state flips the UI timer reads before deciding whether to
 * keep ticking; the timer itself (see {@code ProblemSolvingWorkspaceController}) is expected to add
 * elapsed seconds in small (roughly one-second) increments via {@link #recordElapsedTime} rather than
 * computing a large delta from timestamps, so at most a few seconds of timing data can ever be lost
 * to an unclean shutdown, and a paused session never silently accumulates time while closed.
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

    /** Returns the problem's existing session (resumed, i.e. un-paused), or starts a fresh one at the READING phase. */
    public ProblemSolvingSession startOrResume(long problemId) {
        Optional<ProblemSolvingSession> existing = sessionRepository.findByProblemId(problemId);
        if (existing.isPresent()) {
            return resume(problemId);
        }
        return sessionRepository.save(ProblemSolvingSession.start(problemId));
    }

    /** Stops the timer without losing any accumulated time; {@link #recordElapsedTime} is a no-op while paused. */
    public ProblemSolvingSession pause(long problemId) {
        ProblemSolvingSession session = startOrResumeWithoutForcingResume(problemId);
        session.setPaused(true);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.update(session);
        return sessionRepository.findByProblemId(problemId).orElseThrow();
    }

    /** Resumes a paused (or previously ended/abandoned) session, or is a no-op if already running. */
    public ProblemSolvingSession resume(long problemId) {
        ProblemSolvingSession session = sessionRepository.findByProblemId(problemId)
                .orElseGet(() -> sessionRepository.save(ProblemSolvingSession.start(problemId)));
        session.setPaused(false);
        session.setActive(true);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.update(session);
        return sessionRepository.findByProblemId(problemId).orElseThrow();
    }

    /**
     * Adds elapsed seconds to {@code phase}'s counter and switches the session's current phase to
     * it, regardless of {@code seconds} (so switching phase with 0 elapsed seconds still moves the
     * "current phase" pointer). Does nothing if the session is currently paused, since paused time
     * must never count toward any phase's duration.
     */
    public ProblemSolvingSession recordElapsedTime(long problemId, SolvingPhase phase, int seconds) {
        ProblemSolvingSession session = startOrResumeWithoutForcingResume(problemId);
        if (session.isPaused()) {
            return session;
        }
        session.addElapsedSeconds(phase, seconds);
        session.setPhase(phase);
        session.setLastActiveAt(LocalDateTime.now());
        sessionRepository.update(session);
        return sessionRepository.findByProblemId(problemId).orElseThrow();
    }

    private ProblemSolvingSession startOrResumeWithoutForcingResume(long problemId) {
        return sessionRepository.findByProblemId(problemId)
                .orElseGet(() -> sessionRepository.save(ProblemSolvingSession.start(problemId)));
    }

    public ProblemSolvingSession updateNotes(long problemId, String notes) {
        ProblemSolvingSession session = startOrResumeWithoutForcingResume(problemId);
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

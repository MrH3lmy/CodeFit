package com.codefit.model;

import java.time.LocalDateTime;

/**
 * The persistent, resumable in-progress solving session for a {@link Problem}: which
 * {@link SolvingPhase} the learner is currently in and how much time has accumulated in each phase
 * so far. Deliberately separate from {@link ProblemAttempt}: a session holds live, mutable,
 * not-yet-submitted state (it can be paused and resumed across app restarts), while an attempt is the
 * immutable record created once a submission is finalized. A problem has at most one session row
 * (unique on {@code problem_id}, mirroring {@link ProblemProgress}); starting a new attempt does not
 * by itself end the session, since a learner may keep working the same problem across several
 * submissions before moving on (the full submit-and-reset workflow lands in #145/#146).
 */
public class ProblemSolvingSession {
    private long id;
    private long problemId;
    private SolvingPhase phase;
    private int readingSecondsElapsed;
    private int thinkingSecondsElapsed;
    private int codingSecondsElapsed;
    private int debuggingSecondsElapsed;
    private String notes;
    private boolean active;
    private LocalDateTime startedAt;
    private LocalDateTime lastActiveAt;

    public ProblemSolvingSession(long id, long problemId, SolvingPhase phase, int readingSecondsElapsed,
                                 int thinkingSecondsElapsed, int codingSecondsElapsed, int debuggingSecondsElapsed,
                                 String notes, boolean active, LocalDateTime startedAt, LocalDateTime lastActiveAt) {
        this.id = id;
        this.problemId = problemId;
        this.phase = phase == null ? SolvingPhase.READING : phase;
        this.readingSecondsElapsed = readingSecondsElapsed;
        this.thinkingSecondsElapsed = thinkingSecondsElapsed;
        this.codingSecondsElapsed = codingSecondsElapsed;
        this.debuggingSecondsElapsed = debuggingSecondsElapsed;
        this.notes = notes;
        this.active = active;
        this.startedAt = startedAt;
        this.lastActiveAt = lastActiveAt;
    }

    /** A freshly started session for a problem with no prior in-progress state. */
    public static ProblemSolvingSession start(long problemId) {
        LocalDateTime now = LocalDateTime.now();
        return new ProblemSolvingSession(0, problemId, SolvingPhase.READING, 0, 0, 0, 0, null, true, now, now);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProblemId() { return problemId; }
    public SolvingPhase getPhase() { return phase; }
    public void setPhase(SolvingPhase phase) { this.phase = phase == null ? SolvingPhase.READING : phase; }
    public int getReadingSecondsElapsed() { return readingSecondsElapsed; }
    public int getThinkingSecondsElapsed() { return thinkingSecondsElapsed; }
    public int getCodingSecondsElapsed() { return codingSecondsElapsed; }
    public int getDebuggingSecondsElapsed() { return debuggingSecondsElapsed; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(LocalDateTime lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    /** Adds elapsed seconds to whichever phase's counter this session is currently timing. */
    public void addElapsedSeconds(SolvingPhase phase, int seconds) {
        if (seconds <= 0 || phase == null) {
            return;
        }
        switch (phase) {
            case READING -> readingSecondsElapsed += seconds;
            case THINKING -> thinkingSecondsElapsed += seconds;
            case CODING -> codingSecondsElapsed += seconds;
            case DEBUGGING -> debuggingSecondsElapsed += seconds;
        }
    }
}

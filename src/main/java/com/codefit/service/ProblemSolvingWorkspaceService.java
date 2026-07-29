package com.codefit.service;

import com.codefit.model.HintLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SolvedWith;
import com.codefit.model.SolvingPhase;
import com.codefit.model.SubmissionResult;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.RoadmapEntryRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Coordinates the structured solving workspace (#145): the problem/roadmap/progress context to
 * display, session lifecycle (start/pause/resume/reset/tick), and finishing a session into a
 * {@link ProblemAttempt}. Every timer/session mutation delegates to
 * {@link ProblemSolvingSessionService} (#142); this class adds no persistence of its own beyond
 * orchestrating those calls together with {@link ProblemAttemptService} and
 * {@link ProblemProgressService}.
 */
public class ProblemSolvingWorkspaceService {

    private final ProblemRepository problemRepository;
    private final RoadmapEntryRepository roadmapEntryRepository;
    private final ProblemProgressService progressService;
    private final ProblemAttemptService attemptService;
    private final ProblemSolvingSessionService sessionService;

    public ProblemSolvingWorkspaceService() {
        this(new ProblemRepository(), new RoadmapEntryRepository(), new ProblemProgressService(),
                new ProblemAttemptService(), new ProblemSolvingSessionService());
    }

    public ProblemSolvingWorkspaceService(ProblemRepository problemRepository, RoadmapEntryRepository roadmapEntryRepository,
                                          ProblemProgressService progressService, ProblemAttemptService attemptService,
                                          ProblemSolvingSessionService sessionService) {
        this.problemRepository = problemRepository;
        this.roadmapEntryRepository = roadmapEntryRepository;
        this.progressService = progressService;
        this.attemptService = attemptService;
        this.sessionService = sessionService;
    }

    /** Everything the workspace screen needs to render, without starting a session as a side effect of merely opening it. */
    public WorkspaceView loadWorkspace(long problemId) {
        Problem problem = problemRepository.findById(problemId)
                .orElseThrow(() -> new IllegalArgumentException("No problem with id " + problemId + " exists."));
        RoadmapEntry primaryEntry = roadmapEntryRepository.findByProblemId(problemId).stream().findFirst().orElse(null);
        ProblemProgress progress = progressService.getOrCreate(problemId);
        Optional<ProblemSolvingSession> session = sessionService.findSession(problemId);
        return new WorkspaceView(problem, primaryEntry, progress, session);
    }

    public ProblemSolvingSession start(long problemId) {
        return sessionService.startOrResume(problemId);
    }

    public ProblemSolvingSession pause(long problemId) {
        return sessionService.pause(problemId);
    }

    public ProblemSolvingSession resume(long problemId) {
        return sessionService.resume(problemId);
    }

    public void reset(long problemId) {
        sessionService.reset(problemId);
    }

    /** Passthrough to {@link ProblemProgressService#updateReflection} so the workspace UI only ever talks to this one service. */
    public ProblemProgress updateReflection(long problemId, ProblemReflection reflection) {
        return progressService.updateReflection(problemId, reflection);
    }

    /** Called by the UI timer roughly once a second; a no-op if the session is paused. */
    public ProblemSolvingSession tick(long problemId, SolvingPhase phase, int seconds) {
        return sessionService.recordElapsedTime(problemId, phase, seconds);
    }

    /** Switches the current phase without adding elapsed time (e.g. correcting an accidental switch). */
    public ProblemSolvingSession switchPhase(long problemId, SolvingPhase phase) {
        return sessionService.recordElapsedTime(problemId, phase, 0);
    }

    /**
     * Finishes the session for one of the four supported outcomes.
     *
     * <ul>
     *   <li>{@code ACCEPTED} always records an {@link SubmissionResult#AC} attempt and marks the
     *       problem {@link ProblemState#SOLVED}.</li>
     *   <li>{@code SUBMITTED} records an attempt with the given verdict (defaulting to {@code AC} if
     *       none was given), and marks the problem {@code SOLVED} only if that verdict is itself a
     *       success ({@code AC}/{@code ACX}); otherwise the problem stays {@code IN_PROGRESS}.</li>
     *   <li>{@code COULD_NOT_SOLVE} records an attempt with the given verdict (defaulting to
     *       {@code WA}) and marks the problem {@link ProblemState#NEEDS_REVISIT}.</li>
     *   <li>{@code ABANDONED} creates no attempt at all — no genuine attempt occurred — and simply
     *       ends the session (keeping its accumulated time, in case this was a mistake and the
     *       learner resumes later) without touching progress.</li>
     * </ul>
     *
     * <p>For successful outcomes, the highest hint level opened in the just-finished session is also
     * converted into the persisted {@link SolvedWith} assistance value before the session is reset.
     * This lets the revisit queue schedule hint/editorial-dependent solves without changing their
     * {@code SOLVED} workflow state.
     *
     * <p>Every non-abandoned outcome resets the session afterward, so a future re-attempt at this
     * problem starts its own phase timers from zero rather than continuing to accumulate into an
     * already-finalized attempt's numbers.
     *
     * @return the recorded attempt, or empty for {@code ABANDONED}
     */
    public Optional<ProblemAttempt> finish(long problemId, SessionFinishOutcome outcome, SubmissionResult submissionResult, String notes) {
        if (outcome == null) {
            throw new IllegalArgumentException("A finish outcome is required.");
        }
        if (outcome == SessionFinishOutcome.ABANDONED) {
            sessionService.endSession(problemId);
            return Optional.empty();
        }

        ProblemSolvingSession session = sessionService.startOrResume(problemId);
        SubmissionResult resolvedResult = resolveSubmissionResult(outcome, submissionResult);

        ProblemAttempt recorded = attemptService.recordAttempt(problemId, resolvedResult,
                session.getReadingSecondsElapsed(), session.getThinkingSecondsElapsed(),
                session.getCodingSecondsElapsed(), session.getDebuggingSecondsElapsed(), notes, outcome);

        applyProgressForOutcome(problemId, outcome, resolvedResult);
        applyAssistanceForSuccessfulCompletion(problemId, outcome, resolvedResult, session.getHighestHintLevelOpened());
        sessionService.reset(problemId);
        return Optional.of(recorded);
    }

    private SubmissionResult resolveSubmissionResult(SessionFinishOutcome outcome, SubmissionResult submissionResult) {
        return switch (outcome) {
            case ACCEPTED -> SubmissionResult.AC;
            case SUBMITTED -> submissionResult != null ? submissionResult : SubmissionResult.AC;
            case COULD_NOT_SOLVE -> submissionResult != null ? submissionResult : SubmissionResult.WA;
            case ABANDONED -> throw new IllegalStateException("Abandoned sessions never resolve a submission result.");
        };
    }

    private void applyProgressForOutcome(long problemId, SessionFinishOutcome outcome, SubmissionResult resolvedResult) {
        ProblemState newState = switch (outcome) {
            case ACCEPTED -> ProblemState.SOLVED;
            case SUBMITTED -> isSuccessful(resolvedResult) ? ProblemState.SOLVED : ProblemState.IN_PROGRESS;
            case COULD_NOT_SOLVE -> ProblemState.NEEDS_REVISIT;
            case ABANDONED -> throw new IllegalStateException("Abandoned sessions never update progress.");
        };
        ProblemProgress existing = progressService.getOrCreate(problemId);
        progressService.updateProgress(problemId, newState,
                newState == ProblemState.SOLVED ? LocalDateTime.now() : existing.getCompletedAt());
    }

    private void applyAssistanceForSuccessfulCompletion(long problemId, SessionFinishOutcome outcome,
                                                        SubmissionResult resolvedResult, HintLevel highestHintLevelOpened) {
        boolean completedSuccessfully = outcome == SessionFinishOutcome.ACCEPTED
                || (outcome == SessionFinishOutcome.SUBMITTED && isSuccessful(resolvedResult));
        if (!completedSuccessfully) {
            return;
        }

        SolvedWith inferredAssistance = highestHintLevelOpened == null
                ? SolvedWith.SELF
                : highestHintLevelOpened == HintLevel.EXPLANATION ? SolvedWith.EDITORIAL : SolvedWith.HINT;
        ProblemProgress progress = progressService.getOrCreate(problemId);
        progressService.updateReflection(problemId, new ProblemReflection(
                progress.getPerceivedDifficultyRating(), inferredAssistance, progress.getFinalCategory(),
                progress.getApproachNotes(), progress.getMistakeNotes(), progress.getImportantObservation(),
                progress.getTimeComplexity(), progress.getSpaceComplexity(), progress.getLessonLearned(),
                progress.getActualTopic(), progress.isEditorialUnderstood(), progress.isOtherSolutionsReviewed(),
                progress.isSimplerImplementationConsidered(), progress.isBetterComplexityConsidered()));
    }

    private boolean isSuccessful(SubmissionResult result) {
        return result == SubmissionResult.AC || result == SubmissionResult.ACX;
    }

    /**
     * Marks a problem previously solved elsewhere (e.g. the learner already knows the solution from
     * before using CodeFit) without requiring a new timed workspace session (#146): records an
     * {@code ACX} attempt using whatever phase times the session happens to have (zero if none were
     * ever run) and moves progress to {@code SOLVED} with {@code solvedWith = PREVIOUSLY_SOLVED}.
     */
    public ProblemAttempt markPreviouslySolved(long problemId, String notes) {
        ProblemAttempt attempt = finish(problemId, SessionFinishOutcome.SUBMITTED, SubmissionResult.ACX, notes)
                .orElseThrow(() -> new IllegalStateException("Marking previously solved must always record an attempt."));
        ProblemProgress progress = progressService.getOrCreate(problemId);
        ProblemReflection reflection = new ProblemReflection(progress.getPerceivedDifficultyRating(), SolvedWith.PREVIOUSLY_SOLVED,
                progress.getFinalCategory(), progress.getApproachNotes(), progress.getMistakeNotes(),
                progress.getImportantObservation(), progress.getTimeComplexity(), progress.getSpaceComplexity(),
                progress.getLessonLearned(), progress.getActualTopic(), progress.isEditorialUnderstood(),
                progress.isOtherSolutionsReviewed(), progress.isSimplerImplementationConsidered(),
                progress.isBetterComplexityConsidered());
        progressService.updateReflection(problemId, reflection);
        return attempt;
    }

    public record WorkspaceView(Problem problem, RoadmapEntry roadmapEntry, ProblemProgress progress,
                                Optional<ProblemSolvingSession> session) {
    }
}

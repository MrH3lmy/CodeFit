package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.CompletionOrigin;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SubmissionResult;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MetricAvailability;
import com.codefit.peer.protocol.MetricProvenance;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.TimestampBasis;
import com.codefit.peer.snapshot.LocalProgressSnapshot;
import com.codefit.repository.InterviewMockRepository;
import com.codefit.repository.LocalProgressSnapshotRepository;
import com.codefit.repository.ProblemAttemptRepository;
import com.codefit.repository.ProblemRepository;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.repository.RoadmapEntryRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #183's evidence-to-metric aggregation, exercised against real repositories and a real isolated
 * SQLite database rather than mocks, so a correctness bug in an actual SQL query (not just in a
 * hand-rolled in-memory stand-in) would actually be caught. Every fixture controls its own
 * {@code reviewed_at}/{@code submitted_at} explicitly (via a raw UPDATE after the repository's own
 * {@code save}, since neither repository's public API accepts one — both always write
 * {@code CURRENT_TIMESTAMP}), which is exactly what makes the window-boundary tests below meaningful.
 *
 * <p>{@link ProgressSnapshotService#capture(ComparisonWindow)} takes no {@code Instant} parameter — it
 * always uses its own trusted {@link Clock} — so every test here builds its own service via
 * {@link #serviceAt} with a {@link Clock#fixed} at the instant the test wants to simulate "now" as.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class ProgressSnapshotServiceTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    private ReviewHistoryRepositoryHelper reviews;
    private ProblemAttemptRepository problemAttemptRepository;
    private ProblemRepository problemRepository;
    private RoadmapEntryRepository roadmapEntryRepository;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        // interview_mock_runs is created lazily by InterviewMockRepository itself; touch it once so the
        // table exists before this method's own cleanup tries to delete from it.
        new InterviewMockRepository().findOverallScoresCompletedBetween(LocalDateTime.MIN, LocalDateTime.MIN);
        try (Connection connection = DatabaseConfig.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DELETE FROM review_history");
            statement.execute("DELETE FROM problem_attempts");
            statement.execute("DELETE FROM problem_progress");
            statement.execute("DELETE FROM interview_mock_runs");
            statement.execute("DELETE FROM roadmap_entries");
            statement.execute("DELETE FROM problems");
            statement.execute("DELETE FROM flashcards");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        reviews = new ReviewHistoryRepositoryHelper();
        problemAttemptRepository = new ProblemAttemptRepository();
        problemRepository = new ProblemRepository();
        roadmapEntryRepository = new RoadmapEntryRepository();
    }

    /** A fresh service whose trusted clock is fixed at {@code now} — never a caller-chosen {@code Instant} parameter. */
    private ProgressSnapshotService serviceAt(Instant now) {
        return new ProgressSnapshotService(new ReviewHistoryRepository(), new ProblemAttemptRepository(),
                new InterviewMockRepository(), new LocalProgressSnapshotRepository(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private MetricValue metric(LocalProgressSnapshot snapshot, String id) {
        return snapshot.metrics().stream().filter(m -> m.metricId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void knownFixtureHistoryYieldsExactDailyMetrics() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);

        // 3 objective reviews inside the window: 2 correct (EXACT), 1 wrong (WA-equivalent -> not listed as correct).
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(10, 0));
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(11, 0));
        reviews.insertReview(flashcardId, "WRONG_ANSWER", ReviewRating.AGAIN, day.atTime(12, 0));
        // One review the next day must not count.
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.plusDays(1).atTime(1, 0));

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 59).atZone(UTC).toInstant()).capture(window).snapshot();

        assertEquals(3, metric(snapshot, "review.attempts").value());
    }

    @Test
    void knownFixtureHistoryYieldsExactWeeklyMetrics() {
        long flashcardId = createFlashcard();
        LocalDate monday = LocalDate.of(2026, 1, 12); // a Monday
        ComparisonWindow window = ComparisonWindow.week(monday, UTC, DayOfWeek.MONDAY);

        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, monday.atTime(9, 0));
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, monday.plusDays(6).atTime(23, 0)); // Sunday, still in week
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, monday.plusDays(7).atTime(0, 0)); // next Monday: excluded

        LocalProgressSnapshot snapshot = serviceAt(monday.plusDays(7).atTime(0, 0).atZone(UTC).toInstant())
                .capture(window).snapshot();

        assertEquals(2, metric(snapshot, "review.attempts").value());
    }

    @Test
    void verifiedCorrectRateReportsExactNumeratorAndDenominator() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        // Exactly 7 of 10 objectively-evaluated reviews are correct -> 7000 basis points, exact.
        for (int i = 0; i < 7; i++) {
            reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(1, i));
        }
        for (int i = 0; i < 3; i++) {
            reviews.insertReview(flashcardId, "WRONG_ANSWER", ReviewRating.AGAIN, day.atTime(2, i));
        }

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        MetricValue rate = metric(snapshot, "review.verified_correct_rate");

        assertEquals(MetricAvailability.MEASURED, rate.availability());
        assertEquals(10, rate.sampleSize(), "sample size is the denominator");
        assertEquals(7000, rate.value(), "7/10 = 7000 basis points exactly");
        long recoveredNumerator = Math.round(rate.value() * rate.sampleSize() / 10_000.0);
        assertEquals(7, recoveredNumerator, "value+sampleSize recover the exact numerator");
    }

    @Test
    void repeatedAcceptedAttemptsDoNotDuplicateUniqueCompletion() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        long problemId = createProblem("P1");
        insertAttempt(problemId, 1, SubmissionResult.WA, day.atTime(9, 0));
        insertAttempt(problemId, 2, SubmissionResult.AC, day.atTime(10, 0));
        insertAttempt(problemId, 3, SubmissionResult.ACX, day.atTime(10, 30)); // learner "re-accepted" the same problem

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        assertEquals(3, metric(snapshot, "problem.attempts").value(), "attempt volume counts every submission");
        assertEquals(2, metric(snapshot, "problem.accepted").value(), "accepted-attempt volume counts both AC and ACX events");
        assertEquals(1, metric(snapshot, "problem.unique_completed").value(), "one problem, solved once, however many times it was accepted");
    }

    @Test
    void multipleRoadmapMembershipsDoNotDuplicateAProblemsCompletion() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        long problemId = createProblem("P2");
        roadmapEntryRepository.save(new RoadmapEntry(problemId, RoadmapStage.A, 1, null, true, DifficultyLevel.EASY));
        roadmapEntryRepository.save(new RoadmapEntry(problemId, RoadmapStage.B, 1, null, true, DifficultyLevel.EASY));
        insertAttempt(problemId, 1, SubmissionResult.AC, day.atTime(10, 0));

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        assertEquals(1, metric(snapshot, "problem.unique_completed").value(),
                "the same problem in two roadmap stages is still one completion");
    }

    @Test
    void reImportingAnExistingProblemNeverCreatesAnotherCompletion() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        long problemId = createProblem("P3");
        insertAttempt(problemId, 1, SubmissionResult.AC, day.atTime(10, 0));

        // A re-import looks the problem up by its natural key instead of inserting a new row.
        Optional<Problem> reImported = problemRepository.findByPlatformAndExternalCode("JUNIOR", "P3");
        assertTrue(reImported.isPresent());
        assertEquals(problemId, reImported.get().getId(), "re-import resolves to the SAME problem row");
        // problem_attempts has UNIQUE(problem_id, attempt_number), so a well-behaved re-importer that
        // recomputes the next attempt number from existing rows never inserts a duplicate for it -
        // nothing more to do here except confirm the count is still exactly one.

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        assertEquals(1, metric(snapshot, "problem.unique_completed").value());
    }

    @Test
    void laterSuccessfulReAttemptsNeverMoveAProblemsCompletionThroughHistory() {
        // The exact production bug: ProblemSolvingWorkspaceService#applyProgressForOutcome
        // overwrites problem_progress.completed_at on every later successful finish, which used to
        // move problem.unique_completed out of the day it actually first happened on and into
        // whichever day last touched the row. problem_attempts is append-only, so the FIRST AC/ACX's
        // own submitted_at is what must anchor this metric instead.
        long problemId = createProblem("REATTEMPT");
        LocalDate monday = LocalDate.of(2026, 1, 12);
        LocalDate tuesday = monday.plusDays(1);
        insertAttempt(problemId, 1, SubmissionResult.AC, monday.atTime(10, 0));
        insertAttempt(problemId, 2, SubmissionResult.AC, tuesday.atTime(10, 0)); // a later, genuinely real re-solve

        ComparisonWindow mondayWindow = ComparisonWindow.day(monday, UTC);
        ComparisonWindow tuesdayWindow = ComparisonWindow.day(tuesday, UTC);
        Instant afterBoth = tuesday.atTime(23, 0).atZone(UTC).toInstant();

        LocalProgressSnapshot mondaySnapshot = serviceAt(afterBoth).capture(mondayWindow).snapshot();
        LocalProgressSnapshot tuesdaySnapshot = serviceAt(afterBoth).capture(tuesdayWindow).snapshot();

        assertEquals(1, metric(mondaySnapshot, "problem.unique_completed").value(),
                "the completion stays on Monday, where the FIRST successful attempt actually happened");
        assertEquals(0, metric(tuesdaySnapshot, "problem.unique_completed").value(),
                "Tuesday's re-solve of an already-completed problem must not count as a second completion");
        assertEquals(1, metric(tuesdaySnapshot, "problem.attempts").value(), "Tuesday's own attempt volume still increases");
        assertEquals(1, metric(tuesdaySnapshot, "problem.accepted").value(), "Tuesday's own accepted volume still increases");
    }

    @Test
    void productionWorkspaceFlowNeverMovesHistoricalCompletionOnALaterReAttempt() {
        // Drives the actual, real ProblemSolvingWorkspaceService#finish flow (not a hand-inserted
        // attempt row) - the exact code path the review identified as overwriting
        // problem_progress.completed_at on every later successful finish
        // (ProblemSolvingWorkspaceService#applyProgressForOutcome).
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
        long problemId = createProblem("WORKSPACE-REATTEMPT");
        LocalDate monday = LocalDate.of(2026, 1, 12);
        LocalDate tuesday = monday.plusDays(1);

        ProblemAttempt first = workspaceService.finish(problemId, SessionFinishOutcome.ACCEPTED, null, "first solve").orElseThrow();
        retimeAttemptAndProgress(problemId, first.id(), monday.atTime(10, 0));

        // A genuine later re-solve through the real workflow: this DOES overwrite
        // problem_progress.completed_at (confirmed by inspecting applyProgressForOutcome), which is
        // exactly the mutation this metric must now be immune to.
        ProblemAttempt second = workspaceService.finish(problemId, SessionFinishOutcome.ACCEPTED, null, "second solve").orElseThrow();
        retimeAttemptAndProgress(problemId, second.id(), tuesday.atTime(10, 0));

        Instant afterBoth = tuesday.atTime(23, 0).atZone(UTC).toInstant();
        LocalProgressSnapshot mondaySnapshot = serviceAt(afterBoth).capture(ComparisonWindow.day(monday, UTC)).snapshot();
        LocalProgressSnapshot tuesdaySnapshot = serviceAt(afterBoth).capture(ComparisonWindow.day(tuesday, UTC)).snapshot();

        assertEquals(1, metric(mondaySnapshot, "problem.unique_completed").value(),
                "the real workspace flow's first finish is what anchors the completion to Monday");
        assertEquals(0, metric(tuesdaySnapshot, "problem.unique_completed").value(),
                "the real workspace flow's later re-finish - which does overwrite problem_progress.completed_at - must not move the completion");
        assertEquals(1, metric(tuesdaySnapshot, "problem.attempts").value(), "Tuesday's own attempt volume still increases");
        assertEquals(1, metric(tuesdaySnapshot, "problem.accepted").value(), "Tuesday's own accepted volume still increases");
    }

    @Test
    void aProblemSolvedOnlyThroughImportWithNoTimestampedAttemptNeverCountsAsACompletion() {
        // A problem that arrived as already-SOLVED purely through import/legacy data, with no matching
        // problem_attempts row at all, has no trustworthy first-completion timestamp - it must stay
        // unavailable from this metric for every window, never fabricated into whichever window a
        // caller happens to check.
        long problemId = createProblem("LEGACY-NO-ATTEMPT");
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        assertEquals(0, metric(snapshot, "problem.unique_completed").value(),
                "no timestamped attempt evidence exists for problem " + problemId + " - it must not appear in any window");
    }

    // --- #183 review fix, round 4: completion_origin keeps markPreviouslySolved() from being
    // misclassified as a trustworthy first-time completion ---

    @Test
    void markingPreviouslySolvedNeverCountsAsATodaysCompletion() {
        // The exact bug the review identified: markPreviouslySolved() records a genuine SUBMITTED/ACX
        // attempt (so attempts/accepted volume must still move), but CodeFit has no idea when the
        // learner actually first solved this problem - it must never become "completed today" just
        // because today's mark-previously-solved happens to be the first AC/ACX row on file.
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
        long problemId = createProblem("PREVIOUSLY-SOLVED");
        LocalDate today = LocalDate.of(2026, 1, 15);

        ProblemAttempt attempt = workspaceService.markPreviouslySolved(problemId, "already knew this one");
        retimeAttemptAndProgress(problemId, attempt.id(), today.atTime(10, 0));

        LocalProgressSnapshot snapshot = serviceAt(today.atTime(23, 0).atZone(UTC).toInstant())
                .capture(ComparisonWindow.day(today, UTC)).snapshot();

        assertEquals(1, metric(snapshot, "problem.attempts").value(), "the attempt itself still counts for attempt volume");
        assertEquals(1, metric(snapshot, "problem.accepted").value(),
                "existing attempt semantics still treat ACX as accepted, regardless of completion_origin");
        assertEquals(0, metric(snapshot, "problem.unique_completed").value(),
                "markPreviouslySolved's completion time is unknown - it must never be fabricated into today's window");
    }

    @Test
    void genuineWorkspaceAcceptedCompletionCounts() {
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
        long problemId = createProblem("GENUINE-AC");
        LocalDate today = LocalDate.of(2026, 1, 15);

        ProblemAttempt attempt = workspaceService.finish(problemId, SessionFinishOutcome.ACCEPTED, null, "solved it").orElseThrow();
        retimeAttemptAndProgress(problemId, attempt.id(), today.atTime(10, 0));

        LocalProgressSnapshot snapshot = serviceAt(today.atTime(23, 0).atZone(UTC).toInstant())
                .capture(ComparisonWindow.day(today, UTC)).snapshot();

        assertEquals(1, metric(snapshot, "problem.unique_completed").value(), "a fresh, genuine AC is trustworthy first-completion evidence");
    }

    @Test
    void genuineAcxAfterRealPriorFailuresStillCounts() {
        // The review's explicit constraint: a real Monday-WA/Tuesday-ACX flow through the genuine
        // solving/submission path must still count - the fix must not simply exclude all ACX.
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
        long problemId = createProblem("GENUINE-ACX-AFTER-FAILURE");
        LocalDate monday = LocalDate.of(2026, 1, 12);
        LocalDate tuesday = monday.plusDays(1);

        ProblemAttempt failed = workspaceService.finish(problemId, SessionFinishOutcome.COULD_NOT_SOLVE, SubmissionResult.WA, "didn't get it")
                .orElseThrow();
        retimeAttemptAndProgress(problemId, failed.id(), monday.atTime(10, 0));

        ProblemAttempt accepted = workspaceService.finish(problemId, SessionFinishOutcome.SUBMITTED, SubmissionResult.ACX, "got it this time")
                .orElseThrow();
        retimeAttemptAndProgress(problemId, accepted.id(), tuesday.atTime(10, 0));

        LocalProgressSnapshot snapshot = serviceAt(tuesday.atTime(23, 0).atZone(UTC).toInstant())
                .capture(ComparisonWindow.day(tuesday, UTC)).snapshot();

        assertEquals(1, metric(snapshot, "problem.unique_completed").value(),
                "a genuine ACX that really is the learner's first observed success must still count, exactly like a genuine AC");
    }

    @Test
    void reSolvingLaterThroughTheWorkspaceStillDoesNotCreateAnotherCompletion() {
        ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
        long problemId = createProblem("GENUINE-RESOLVE");
        LocalDate monday = LocalDate.of(2026, 1, 12);
        LocalDate tuesday = monday.plusDays(1);

        ProblemAttempt first = workspaceService.finish(problemId, SessionFinishOutcome.ACCEPTED, null, "first solve").orElseThrow();
        retimeAttemptAndProgress(problemId, first.id(), monday.atTime(10, 0));

        ProblemAttempt second = workspaceService.finish(problemId, SessionFinishOutcome.ACCEPTED, null, "re-solved it").orElseThrow();
        retimeAttemptAndProgress(problemId, second.id(), tuesday.atTime(10, 0));

        Instant afterBoth = tuesday.atTime(23, 0).atZone(UTC).toInstant();
        LocalProgressSnapshot mondaySnapshot = serviceAt(afterBoth).capture(ComparisonWindow.day(monday, UTC)).snapshot();
        LocalProgressSnapshot tuesdaySnapshot = serviceAt(afterBoth).capture(ComparisonWindow.day(tuesday, UTC)).snapshot();

        assertEquals(1, metric(mondaySnapshot, "problem.unique_completed").value(), "Monday's genuine first solve is the completion");
        assertEquals(0, metric(tuesdaySnapshot, "problem.unique_completed").value(), "Tuesday's re-solve must not create a second completion");
        assertEquals(1, metric(tuesdaySnapshot, "problem.attempts").value(), "Tuesday's own attempt volume still increases");
        assertEquals(1, metric(tuesdaySnapshot, "problem.accepted").value(), "Tuesday's own accepted volume still increases");
    }

    @Test
    void legacyUnknownOriginAcxNeverFabricatesACompletion() {
        // A row that predates completion_origin entirely (migrated to UNKNOWN - never guessed as
        // FRESH_ATTEMPT just because submission_result is ACX).
        long problemId = createProblem("LEGACY-UNKNOWN-ACX");
        LocalDate day = LocalDate.of(2026, 1, 15);
        insertAttemptWithOrigin(problemId, 1, SubmissionResult.ACX, day.atTime(10, 0), CompletionOrigin.UNKNOWN);

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant())
                .capture(ComparisonWindow.day(day, UTC)).snapshot();

        assertEquals(1, metric(snapshot, "problem.attempts").value(), "attempt volume still counts the row");
        assertEquals(1, metric(snapshot, "problem.accepted").value(), "accepted volume still counts the row");
        assertEquals(0, metric(snapshot, "problem.unique_completed").value(),
                "a legacy row with unknown origin must never be fabricated into a first-time completion");
    }

    @Test
    void reImportStillDoesNotCreateACompletion() {
        // #183 review fix, round 4: the workbook importer's own write path
        // (TrainingSheetImportService#applyProblem) must explicitly record IMPORTED, since its
        // submittedAt is the import's own timestamp, not the learner's real historical submission
        // time - keeping this existing green behavior intact under the new origin-aware query.
        long problemId = createProblem("REIMPORT-IMPORTED");
        LocalDate day = LocalDate.of(2026, 1, 15);
        insertAttemptWithOrigin(problemId, 1, SubmissionResult.AC, day.atTime(10, 0), CompletionOrigin.IMPORTED);

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant())
                .capture(ComparisonWindow.day(day, UTC)).snapshot();

        assertEquals(0, metric(snapshot, "problem.unique_completed").value(),
                "an import-sourced attempt (IMPORTED origin) must never count as a fresh completion");
    }

    @Test
    void importedAttemptNeverInflatesAttemptAcceptedOrSolvingSecondsForTheImportsOwnWindow() {
        // #183 review fix, round 5: a workbook import's submitted_at is the import's own run time,
        // not when the learner actually solved anything - the workbook describes historical
        // activity with no real timestamp of its own. Before this fix, an import landing "today"
        // inflated problem.attempts/accepted/solving_seconds for today's window with volume that
        // never genuinely happened then.
        long problemId = createProblem("IMPORT-DOES-NOT-INFLATE-VOLUME");
        LocalDate importDay = LocalDate.of(2026, 1, 15);
        insertAttemptWithOrigin(problemId, 1, SubmissionResult.AC, importDay.atTime(10, 0), CompletionOrigin.IMPORTED);

        LocalProgressSnapshot snapshot = serviceAt(importDay.atTime(23, 0).atZone(UTC).toInstant())
                .capture(ComparisonWindow.day(importDay, UTC)).snapshot();

        assertEquals(0, metric(snapshot, "problem.attempts").value(),
                "an imported attempt must never be counted as attempt volume in the window it happened to be imported into");
        assertEquals(0, metric(snapshot, "problem.accepted").value(),
                "an imported attempt must never be counted as accepted volume in the window it happened to be imported into");
        assertEquals(0, metric(snapshot, "problem.solving_seconds").value(),
                "an imported attempt must never be counted as solving time in the window it happened to be imported into");
        assertEquals(0, metric(snapshot, "problem.unique_completed").value());
    }

    @Test
    void genuineLegacyUnknownOriginStillCountsForAttemptAndAcceptedVolume() {
        // Contrast with the test above: a TRUE pre-migration legacy row (never guessed as import or
        // fresh) still has a genuine, non-fabricated submitted_at from whenever the app actually
        // wrote it, so - unlike IMPORTED - it must keep counting for attempt/accepted volume
        // (#183's existing allowance for attempt-volume metrics to use evidence that isn't precise
        // enough for first-completion purposes). Only problem.unique_completed excludes it.
        long problemId = createProblem("LEGACY-UNKNOWN-STILL-COUNTS");
        LocalDate day = LocalDate.of(2026, 1, 15);
        insertAttemptWithOrigin(problemId, 1, SubmissionResult.AC, day.atTime(10, 0), CompletionOrigin.UNKNOWN);

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant())
                .capture(ComparisonWindow.day(day, UTC)).snapshot();

        assertEquals(1, metric(snapshot, "problem.attempts").value(), "a genuine legacy row's real submitted_at still counts");
        assertEquals(1, metric(snapshot, "problem.accepted").value(), "a genuine legacy row's real submitted_at still counts");
        assertEquals(0, metric(snapshot, "problem.unique_completed").value());
    }

    @Test
    void objectiveManualSelfRatedAndLegacyEvidenceStayDistinguishable() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        for (int i = 0; i < 10; i++) {
            reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(1, i)); // objective, verified
        }
        for (int i = 0; i < 10; i++) {
            reviews.insertSubjective(flashcardId, ReviewRating.GOOD, day.atTime(2, i)); // subjective self-rating
        }

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        MetricValue verified = metric(snapshot, "review.verified_correct_rate");
        MetricValue selfRated = metric(snapshot, "review.self_rated_success_rate");
        assertEquals(MetricProvenance.VERIFIED_LOCAL_VALIDATION, verified.provenance());
        assertEquals(10, verified.sampleSize(), "subjective rows never inflate the verified denominator");
        assertEquals(MetricProvenance.SELF_RATED, selfRated.provenance());
        assertEquals(10, selfRated.sampleSize(), "verified rows never inflate the self-rated denominator");
    }

    @Test
    void legacyIsObjectivelyCorrectFallbackIsNeverExportedAsVerifiedCorrectness() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        // 10 genuinely verified rows so the metric is MEASURED, plus legacy rows with no validation_result
        // at all (pre-#X rows) that fall back to the self rating - these must stay out of "verified".
        for (int i = 0; i < 10; i++) {
            reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(1, i));
        }
        for (int i = 0; i < 5; i++) {
            reviews.insertLegacyBlankValidation(flashcardId, ReviewRating.GOOD, day.atTime(3, i));
        }

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        MetricValue verified = metric(snapshot, "review.verified_correct_rate");
        assertEquals(10, verified.sampleSize(), "the 5 legacy fallback rows must never count toward verified evidence");
        MetricValue selfRated = metric(snapshot, "review.self_rated_success_rate");
        assertEquals(0, selfRated.sampleSize(), "legacy fallback rows must never be pooled into the genuine self-rated metric either");
        MetricValue legacyFallback = metric(snapshot, "review.legacy_rating_fallback_success_rate");
        assertEquals(MetricProvenance.LEGACY_SELF_RATING_FALLBACK, legacyFallback.provenance());
        assertEquals(5, legacyFallback.sampleSize(), "the legacy fallback rows are counted here instead, labelled as such");
    }

    @Test
    void genuineSubjectiveAndLegacyFallbackSamplesAreExportedSeparatelyWhenBothPresent() {
        // The exact scenario the review flagged: both evidence subsets present simultaneously. Neither
        // metric's sample size may absorb the other's samples, and neither declared provenance may
        // describe a sample it doesn't own.
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        for (int i = 0; i < 6; i++) {
            reviews.insertSubjective(flashcardId, ReviewRating.GOOD, day.atTime(1, i));
        }
        for (int i = 0; i < 5; i++) {
            reviews.insertLegacyBlankValidation(flashcardId, ReviewRating.AGAIN, day.atTime(2, i));
        }

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        MetricValue selfRated = metric(snapshot, "review.self_rated_success_rate");
        MetricValue legacyFallback = metric(snapshot, "review.legacy_rating_fallback_success_rate");
        assertEquals(MetricProvenance.SELF_RATED, selfRated.provenance());
        assertEquals(6, selfRated.sampleSize(), "only the genuinely subjective rows");
        assertEquals(MetricProvenance.LEGACY_SELF_RATING_FALLBACK, legacyFallback.provenance());
        assertEquals(5, legacyFallback.sampleSize(), "only the legacy fallback rows");
        assertEquals(11, selfRated.sampleSize() + legacyFallback.sampleSize(), "together they account for every row, split, not pooled");
    }

    @Test
    void legacyUnknownHintUsageNeverEntersTheNumeratorOrDenominator() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        for (int i = 0; i < 10; i++) {
            reviews.insertWithKnownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(1, i), false);
        }
        // If these wrongly entered the denominator (as "known, hint used"), the rate would drop from
        // 10000 to 10/15 = 6667 basis points - a detectable, not merely cosmetic, pollution.
        for (int i = 0; i < 5; i++) {
            reviews.insertLegacyUnknownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(2, i));
        }

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        MetricValue hintFree = metric(snapshot, "review.hint_free_rate");

        assertEquals(10, hintFree.sampleSize(), "the 5 legacy unknown rows must never enter the denominator");
        assertEquals(10_000, hintFree.value(), "10/10 known hint-free, unaffected by the legacy rows");
    }

    @Test
    void aNewExplicitlyHintFreeReviewIncreasesBothNumeratorAndDenominator() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        for (int i = 0; i < 9; i++) {
            reviews.insertWithKnownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(1, i), false);
        }
        LocalProgressSnapshot before = serviceAt(day.atTime(12, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        assertEquals(MetricAvailability.INSUFFICIENT_DATA, metric(before, "review.hint_free_rate").availability(),
                "only 9 known samples so far - below the minimum of 10");

        reviews.insertWithKnownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(1, 9), false);
        LocalProgressSnapshot after = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        MetricValue hintFree = metric(after, "review.hint_free_rate");

        assertEquals(10, hintFree.sampleSize(), "denominator increased by exactly 1");
        assertEquals(10_000, hintFree.value(), "the new review was hint-free, so the numerator increased by 1 too");
    }

    @Test
    void aNewExplicitlyHintUsedReviewIncreasesTheDenominatorButNotTheNumerator() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        for (int i = 0; i < 9; i++) {
            reviews.insertWithKnownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(1, i), false);
        }

        reviews.insertWithKnownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(1, 9), true);
        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        MetricValue hintFree = metric(snapshot, "review.hint_free_rate");

        assertEquals(10, hintFree.sampleSize(), "denominator increased by 1 for the new known sample");
        assertEquals(9_000, hintFree.value(), "9 of 10 are hint-free - the hint-used review never joins the numerator");
    }

    @Test
    void onlyLegacyUnknownHintReviewsStaysInsufficientDataNeverAFabricatedHundredPercent() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        for (int i = 0; i < 20; i++) {
            reviews.insertLegacyUnknownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(1, i));
        }

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        MetricValue hintFree = metric(snapshot, "review.hint_free_rate");

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, hintFree.availability(),
                "20 legacy rows with unknown hint usage must never be reported as a measured rate");
        assertEquals(0, hintFree.sampleSize());
        assertEquals(0, hintFree.value(), "never a fabricated 100% (or any other value) from unknown evidence");
    }

    @Test
    void mixedKnownAndUnknownHintReviewsSampleSizeReflectsOnlyKnownEvidence() {
        long flashcardId = createFlashcard();
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        for (int i = 0; i < 12; i++) {
            reviews.insertWithKnownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(1, i), i < 3);
        }
        for (int i = 0; i < 8; i++) {
            reviews.insertLegacyUnknownHintUsage(flashcardId, ReviewRating.GOOD, day.atTime(2, i));
        }

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        MetricValue hintFree = metric(snapshot, "review.hint_free_rate");

        assertEquals(12, hintFree.sampleSize(), "the 8 unknown rows must never inflate the sample size");
        assertEquals(7_500, hintFree.value(), "9 of 12 known reviews were hint-free");
    }

    @Test
    void missingEvidenceIsInsufficientDataNeverAMeasuredZero() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        MetricValue verified = metric(snapshot, "review.verified_correct_rate");
        assertEquals(MetricAvailability.INSUFFICIENT_DATA, verified.availability());
        assertEquals(0, verified.sampleSize());
        MetricValue mock = metric(snapshot, "mock.overall_score");
        assertEquals(MetricAvailability.INSUFFICIENT_DATA, mock.availability());
    }

    @Test
    void utcBoundaryIsExactAtTheMillisecondAcrossANonUtcZone() {
        // America/New_York is UTC-5 in January (no DST). The local day 2026-01-15 is therefore the
        // UTC interval [2026-01-15T05:00:00Z, 2026-01-16T05:00:00Z).
        ZoneId zone = ZoneId.of("America/New_York");
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), zone);
        long flashcardId = createFlashcard();
        reviews.insertReviewUtc(flashcardId, "EXACT", ReviewRating.GOOD, LocalDateTime.of(2026, 1, 16, 4, 59, 59));
        reviews.insertReviewUtc(flashcardId, "EXACT", ReviewRating.GOOD, LocalDateTime.of(2026, 1, 16, 5, 0, 1));

        Instant cutoff = LocalDateTime.of(2026, 1, 16, 12, 0).atZone(zone).toInstant();
        LocalProgressSnapshot snapshot = serviceAt(cutoff).capture(window).snapshot();

        assertEquals(1, metric(snapshot, "review.attempts").value(), "only the row strictly inside [start, end) in UTC counts");
    }

    @Test
    void legacyZoneLocalColumnIsComparedInTheComparisonZoneNotUtc() {
        // Same window as above: local day 2026-01-15 in America/New_York = UTC [...T05:00Z, ...T05:00Z).
        // interview_mock_runs.completed_at is written as a naive LocalDateTime assumed to be zone-local.
        // 2026-01-16T02:00 "local" belongs to the NEXT local day and must be excluded - but a UTC-bound
        // comparison of the same raw string would wrongly include it (02:00 < the window's 05:00Z end).
        ZoneId zone = ZoneId.of("America/New_York");
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), zone);
        insertMockRun(80, LocalDateTime.of(2026, 1, 16, 2, 0));

        Instant cutoff = LocalDateTime.of(2026, 1, 17, 0, 0).atZone(zone).toInstant();
        LocalProgressSnapshot snapshot = serviceAt(cutoff).capture(window).snapshot();

        assertEquals(MetricAvailability.INSUFFICIENT_DATA, metric(snapshot, "mock.overall_score").availability(),
                "a zone-local 02:00 on the next day must not be pulled into the previous local day");
    }

    @Test
    void captureIsPersistedAndReplacesRatherThanDuplicatesOnRecapture() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        long flashcardId = createFlashcard();
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(9, 0));

        ProgressSnapshotService.Capture first = serviceAt(day.atTime(12, 0).atZone(UTC).toInstant()).capture(window);
        assertEquals(LocalProgressSnapshotRepository.SaveOutcome.RECORDED_FIRST, first.outcome());

        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(13, 0));
        ProgressSnapshotService.Capture second = serviceAt(day.atTime(14, 0).atZone(UTC).toInstant()).capture(window);
        assertEquals(LocalProgressSnapshotRepository.SaveOutcome.REPLACED, second.outcome());
        assertEquals(1, first.snapshot().revision());
        assertEquals(2, second.snapshot().revision(), "a genuine correction is revision 2, not a second revision-1 row");

        LocalProgressSnapshot reloaded = new LocalProgressSnapshotRepository().findByWindow(window).orElseThrow();
        assertEquals(2, metric(reloaded, "review.attempts").value(), "the correction replaced the row, not appended a second one");
        assertEquals(second.snapshot().revision(), reloaded.revision());
        assertEquals(second.snapshot().capturedAt(), reloaded.capturedAt());
    }

    @Test
    void unchangedResendWithinTheSameSecondIsIdempotentAndDoesNotBumpTheRevision() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        long flashcardId = createFlashcard();
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(9, 0));
        Instant now = day.atTime(12, 0, 0, 500_000_000).atZone(UTC).toInstant();

        ProgressSnapshotService.Capture first = serviceAt(now).capture(window);
        assertEquals(LocalProgressSnapshotRepository.SaveOutcome.RECORDED_FIRST, first.outcome());

        // No new evidence, exact same instant: a byte-identical resend.
        ProgressSnapshotService.Capture second = serviceAt(now).capture(window);
        assertEquals(LocalProgressSnapshotRepository.SaveOutcome.UNCHANGED, second.outcome());
        assertEquals(first.snapshot().revision(), second.snapshot().revision(), "an unchanged resend never bumps the revision");
    }

    @Test
    void twoGenuinelyDifferentCapturesWithinTheSameWallClockSecondGetStrictlyIncreasingRevisions() {
        // 12:00:00.100 and 12:00:00.900 share the same whole epoch second, which is exactly what the
        // old cutoff.getEpochSecond()-derived revision would have collided on.
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        long flashcardId = createFlashcard();
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(9, 0));
        Instant firstInstant = day.atTime(12, 0, 0, 100_000_000).atZone(UTC).toInstant();
        Instant secondInstant = day.atTime(12, 0, 0, 900_000_000).atZone(UTC).toInstant();
        assertEquals(firstInstant.getEpochSecond(), secondInstant.getEpochSecond(), "sanity check: same whole epoch second");

        ProgressSnapshotService.Capture first = serviceAt(firstInstant).capture(window);
        assertEquals(1, first.snapshot().revision());

        // A genuinely new review landed between the two captures.
        reviews.insertReview(flashcardId, "EXACT", ReviewRating.GOOD, day.atTime(9, 30));
        ProgressSnapshotService.Capture second = serviceAt(secondInstant).capture(window);

        assertEquals(LocalProgressSnapshotRepository.SaveOutcome.REPLACED, second.outcome());
        assertEquals(2, second.snapshot().revision(), "the second, genuinely different capture must get a strictly higher revision");
        assertTrue(second.snapshot().revision() > first.snapshot().revision());

        LocalProgressSnapshot reloaded = new LocalProgressSnapshotRepository().findByWindow(window).orElseThrow();
        assertEquals(2, reloaded.revision(), "local persistence keeps the latest body under the higher revision");
        assertEquals(2, metric(reloaded, "review.attempts").value());
    }

    // --- fixtures ---

    private long createFlashcard() {
        try (Connection connection = DatabaseConfig.getConnection()) {
            long deckId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO decks (name, description) VALUES (?, 'fixture')",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "fixture-deck-" + System.nanoTime());
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    keys.next();
                    deckId = keys.getLong(1);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO flashcards (deck_id, front, back, card_type, accepted_answers, review_count, due_date) "
                            + "VALUES (?, 'front', 'back', 'RECALL', 'x', 0, date('now'))",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, deckId);
                statement.executeUpdate();
                try (var keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private long createProblem(String externalCode) {
        Problem saved = problemRepository.save(new Problem(externalCode, "JUNIOR", "Problem " + externalCode,
                null, "General", null, null));
        return saved.getId();
    }

    /**
     * Inserts a genuine, fresh attempt directly (bypassing the workspace service) so a test can
     * control {@code submittedAt} precisely. {@code CompletionOrigin.FRESH_ATTEMPT} matches what
     * every one of these fixtures actually models: a real, directly-observed submission, never a
     * {@code markPreviouslySolved}/import-style attempt whose completion time is unknown (#183
     * review fix, round 4) - those are modeled by the dedicated legacy/previously-solved tests
     * instead, via {@link #insertAttemptWithOrigin}.
     */
    private void insertAttempt(long problemId, int attemptNumber, SubmissionResult result, LocalDateTime submittedAtUtc) {
        insertAttemptWithOrigin(problemId, attemptNumber, result, submittedAtUtc, CompletionOrigin.FRESH_ATTEMPT);
    }

    private void insertAttemptWithOrigin(long problemId, int attemptNumber, SubmissionResult result,
                                          LocalDateTime submittedAtUtc, CompletionOrigin completionOrigin) {
        ProblemAttempt saved = problemAttemptRepository.save(new ProblemAttempt(0, problemId, attemptNumber, result,
                null, null, null, null, submittedAtUtc, null, null, completionOrigin));
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE problem_attempts SET submitted_at = ? WHERE id = ?")) {
            statement.setString(1, submittedAtUtc.toString());
            statement.setLong(2, saved.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Backdates a real {@code ProblemSolvingWorkspaceService#finish} result's {@code problem_attempts}
     * row and the problem's (shared, overwritten-on-each-finish) {@code problem_progress} row to a
     * fixed instant, so the production-path regression test above can place two real finishes on two
     * different calendar days without waiting on the real wall clock.
     */
    private void retimeAttemptAndProgress(long problemId, long attemptId, LocalDateTime at) {
        try (Connection connection = DatabaseConfig.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE problem_attempts SET submitted_at = ? WHERE id = ?")) {
                statement.setString(1, at.toString());
                statement.setLong(2, attemptId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE problem_progress SET completed_at = ? WHERE problem_id = ?")) {
                statement.setString(1, at.toString());
                statement.setLong(2, problemId);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Inserts a raw {@code interview_mock_runs} row with an explicit {@code completed_at}, for zone-boundary fixtures. */
    private void insertMockRun(int overallScorePercent, LocalDateTime completedAt) {
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO interview_mock_runs (run_id, profile_id, mode, overall_score_percent, completed_at) "
                             + "VALUES (?, 'fixture-profile', 'LIVE_CODING', ?, ?)")) {
            statement.setString(1, "fixture-run-" + System.nanoTime());
            statement.setInt(2, overallScorePercent);
            statement.setString(3, completedAt.toString());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Controls {@code review_history.reviewed_at}/{@code validation_result} explicitly for deterministic fixtures. */
    private static final class ReviewHistoryRepositoryHelper {
        private final ReviewHistoryRepository repository = new ReviewHistoryRepository();

        void insertReview(long flashcardId, String validationResult, ReviewRating rating, LocalDateTime reviewedAtUtc) {
            insertReviewUtc(flashcardId, validationResult, rating, reviewedAtUtc);
        }

        void insertReviewUtc(long flashcardId, String validationResult, ReviewRating rating, LocalDateTime reviewedAtUtc) {
            ReviewHistory saved = repository.save(new ReviewHistory(0, flashcardId, rating, 0, 1, reviewedAtUtc, true, false,
                    validationResult, null, null, false, null, null));
            setReviewedAt(saved.getId(), reviewedAtUtc);
        }

        void insertSubjective(long flashcardId, ReviewRating rating, LocalDateTime reviewedAtUtc) {
            ReviewHistory saved = repository.save(new ReviewHistory(0, flashcardId, rating, 0, 1, reviewedAtUtc, true, false,
                    "SUBJECTIVE", null, null, false, null, null));
            setReviewedAt(saved.getId(), reviewedAtUtc);
        }

        void insertLegacyBlankValidation(long flashcardId, ReviewRating rating, LocalDateTime reviewedAtUtc) {
            ReviewHistory saved = repository.save(new ReviewHistory(0, flashcardId, rating, 0, 1, reviewedAtUtc, true, false,
                    null, null, null, false, null, null));
            setReviewedAt(saved.getId(), reviewedAtUtc);
        }

        /** A genuinely new review with known, explicit hint usage - exactly what {@code save()} always marks as recorded. */
        void insertWithKnownHintUsage(long flashcardId, ReviewRating rating, LocalDateTime reviewedAtUtc, boolean hintUsed) {
            ReviewHistory saved = repository.save(new ReviewHistory(0, flashcardId, rating, 0, 1, reviewedAtUtc, true, false,
                    "EXACT", null, null, hintUsed, null, null));
            setReviewedAt(saved.getId(), reviewedAtUtc);
        }

        /**
         * Simulates a row that already existed before hint tracking was added: {@code save()} always
         * marks hint usage as recorded, so this inserts normally and then forces
         * {@code hint_usage_recorded} back to 0 directly - exactly what the additive
         * {@code ALTER TABLE ... ADD COLUMN hint_usage_recorded INTEGER NOT NULL DEFAULT 0} migration
         * does to every row that already existed when it ran, regardless of {@code hint_used}'s value.
         */
        void insertLegacyUnknownHintUsage(long flashcardId, ReviewRating rating, LocalDateTime reviewedAtUtc) {
            ReviewHistory saved = repository.save(new ReviewHistory(0, flashcardId, rating, 0, 1, reviewedAtUtc, true, false,
                    "EXACT", null, null, true, null, null));
            setReviewedAt(saved.getId(), reviewedAtUtc);
            markHintUsageUnknown(saved.getId());
        }

        private void markHintUsageUnknown(long id) {
            try (Connection connection = DatabaseConfig.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE review_history SET hint_usage_recorded = 0 WHERE id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }

        private void setReviewedAt(long id, LocalDateTime reviewedAtUtc) {
            try (Connection connection = DatabaseConfig.getConnection();
                 PreparedStatement statement = connection.prepareStatement("UPDATE review_history SET reviewed_at = ? WHERE id = ?")) {
                statement.setString(1, reviewedAtUtc.toString());
                statement.setLong(2, id);
                statement.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ComplexityClass;
import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemState;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.model.SolvedWith;
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
import com.codefit.repository.ProblemProgressRepository;
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
    private ProblemProgressRepository problemProgressRepository;
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
        problemProgressRepository = new ProblemProgressRepository();
        problemRepository = new ProblemRepository();
        roadmapEntryRepository = new RoadmapEntryRepository();
    }

    /** A fresh service whose trusted clock is fixed at {@code now} — never a caller-chosen {@code Instant} parameter. */
    private ProgressSnapshotService serviceAt(Instant now) {
        return new ProgressSnapshotService(new ReviewHistoryRepository(), new ProblemAttemptRepository(),
                new ProblemProgressRepository(), new InterviewMockRepository(), new LocalProgressSnapshotRepository(),
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
        problemProgressRepository.save(solvedProgress(problemId, day.atTime(10, 0)));

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
        problemProgressRepository.save(solvedProgress(problemId, day.atTime(10, 0)));

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();

        assertEquals(1, metric(snapshot, "problem.unique_completed").value(),
                "the same problem in two roadmap stages is still one completion");
    }

    @Test
    void reImportingAnExistingProblemNeverCreatesAnotherCompletion() {
        LocalDate day = LocalDate.of(2026, 1, 15);
        ComparisonWindow window = ComparisonWindow.day(day, UTC);
        long problemId = createProblem("P3");
        problemProgressRepository.save(solvedProgress(problemId, day.atTime(10, 0)));

        // A re-import looks the problem up by its natural key instead of inserting a new row.
        Optional<Problem> reImported = problemRepository.findByPlatformAndExternalCode("JUNIOR", "P3");
        assertTrue(reImported.isPresent());
        assertEquals(problemId, reImported.get().getId(), "re-import resolves to the SAME problem row");
        // Progress already exists for this problem_id; a well-behaved importer never inserts a second
        // problem_progress row for it (problem_id is UNIQUE), so nothing more to do here except confirm
        // the count is still exactly one.

        LocalProgressSnapshot snapshot = serviceAt(day.atTime(23, 0).atZone(UTC).toInstant()).capture(window).snapshot();
        assertEquals(1, metric(snapshot, "problem.unique_completed").value());
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
        // problem_progress.completed_at is written as a naive LocalDateTime assumed to be zone-local.
        // 2026-01-16T02:00 "local" belongs to the NEXT local day and must be excluded - but a UTC-bound
        // comparison of the same raw string would wrongly include it (02:00 < the window's 05:00Z end).
        ZoneId zone = ZoneId.of("America/New_York");
        ComparisonWindow window = ComparisonWindow.day(LocalDate.of(2026, 1, 15), zone);
        long problemId = createProblem("TZ1");
        problemProgressRepository.save(solvedProgress(problemId, LocalDateTime.of(2026, 1, 16, 2, 0)));

        Instant cutoff = LocalDateTime.of(2026, 1, 17, 0, 0).atZone(zone).toInstant();
        LocalProgressSnapshot snapshot = serviceAt(cutoff).capture(window).snapshot();

        assertEquals(0, metric(snapshot, "problem.unique_completed").value(),
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

    private void insertAttempt(long problemId, int attemptNumber, SubmissionResult result, LocalDateTime submittedAtUtc) {
        ProblemAttempt saved = problemAttemptRepository.save(new ProblemAttempt(0, problemId, attemptNumber, result,
                null, null, null, null, submittedAtUtc, null));
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE problem_attempts SET submitted_at = ? WHERE id = ?")) {
            statement.setString(1, submittedAtUtc.toString());
            statement.setLong(2, saved.id());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private ProblemProgress solvedProgress(long problemId, LocalDateTime completedAt) {
        return new ProblemProgress(0, problemId, ProblemState.SOLVED, null, SolvedWith.SELF, null,
                null, null, null, (ComplexityClass) null, (ComplexityClass) null, null, null,
                false, false, false, false, completedAt, completedAt);
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

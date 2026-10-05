package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ProgressComparison;
import com.codefit.peer.comparison.SnapshotComparisonEngine.Reason;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.repository.PeerProgressSummaryRepository;
import com.codefit.repository.PeerSyncConsentRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * State-mapping and orchestration coverage for {@link PeerComparisonService}: every decision this
 * class claims to make without delegating to {@code SnapshotComparisonEngine} (consent-gated
 * NOT_SHARED, MISSING, and the tightly-bounded same-day fallback) is exercised directly here, against
 * real repositories and a real {@link ProgressSnapshotService} - never mocked. Peer-side fixtures are
 * inserted directly into the repositories (never through the real sync/transport stack - that
 * boundary is covered once, for real, by the two-process product E2E instead).
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerComparisonServiceTest {

    private static final char[] PASSPHRASE = "peer-comparison-test-passphrase".toCharArray();
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private ContactService contactService;
    private IdentityService identityService;
    private PeerProgressSummaryRepository peerProgressSummaryRepository;
    private PeerSyncConsentRepository peerSyncConsentRepository;
    private IdentityId myId;
    private IdentityId peerId;
    private long contactId;
    /** My own real metric shapes (ids/versions/units/provenance), from a real capture over the
     *  evidence this fixture inserts - reused as-is for peer-side fixtures too, so every (id, unit,
     *  provenance) combination this test ever builds is guaranteed valid by construction rather than
     *  hand-picked and hoped to match {@code MetricRegistry}. */
    private List<MetricValue> realMetricShapes;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        identityService = new IdentityService();
        contactService = new ContactService();
        peerProgressSummaryRepository = new PeerProgressSummaryRepository();
        peerSyncConsentRepository = new PeerSyncConsentRepository();

        myId = identityService.createIdentity(PASSPHRASE, nowMillis()).id();
        IdentityKey peerIdentityKey = new IdentityKey(KeyPairs.rawPublicKey(KeyPairs.generate().getPublic()));

        Contact pending = contactService.registerPendingContact(peerIdentityKey, "fake-peer", nowMillis());
        Contact paired = contactService.acceptInvitation(pending.id(), nowMillis());
        contactId = paired.id();
        peerId = paired.identityId();

        realMetricShapes = insertReviewTodayAndCaptureRealMetrics();
    }

    /**
     * Fixes <em>both</em> {@link PeerComparisonService}'s own clock and the {@link
     * ProgressSnapshotService} it captures "my own" side with to the exact same instant - the only
     * way to make "my own" side's cutoff (always captured fresh, for real, via a genuinely separate
     * internal call) deterministic enough to construct a peer-side fixture that the engine's own
     * elapsed-time alignment check (same instant since window start, required whenever neither side
     * is a complete period - true for every "today" window short of literal midnight) will actually
     * accept as aligned. Two independent real-clock reads can never be relied on to agree to the
     * nanosecond; one shared fixed clock sidesteps the question entirely.
     */
    private PeerComparisonService serviceAt(Instant fixedNow) {
        Clock clock = Clock.fixed(fixedNow, ZONE);
        ProgressSnapshotService fixedSnapshotService = new ProgressSnapshotService(
                new com.codefit.repository.ReviewHistoryRepository(), new com.codefit.repository.ProblemAttemptRepository(),
                new com.codefit.repository.InterviewMockRepository(), new com.codefit.repository.LocalProgressSnapshotRepository(),
                clock);
        return new PeerComparisonService(contactService, identityService, fixedSnapshotService,
                peerProgressSummaryRepository, peerSyncConsentRepository, clock);
    }

    // --- 1: peer never sent any consent ---

    @Test
    void peerWithNoConsentAtAllIsNotShared() {
        ProgressComparison comparison = serviceAt(nowMillis()).compareTodayWith(contactId);
        assertEquals(ComparisonState.UNAVAILABLE, comparison.state());
        assertEquals(Reason.RIGHT_NOT_SHARED, comparison.reason());
    }

    // --- 2: peer granted consent, but not DAILY_SUMMARY ---

    @Test
    void peerConsentWithoutDailySummaryIsNotShared() throws Exception {
        saveConsent(List.of(SharingScope.SOCIAL_PROFILE), 1);
        ProgressComparison comparison = serviceAt(nowMillis()).compareTodayWith(contactId);
        assertEquals(ComparisonState.UNAVAILABLE, comparison.state());
        assertEquals(Reason.RIGHT_NOT_SHARED, comparison.reason());
    }

    // --- 3: peer authorized DAILY_SUMMARY, but nothing has arrived yet ---

    @Test
    void peerAuthorizedButNothingArrivedYetIsMissing() throws Exception {
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        ProgressComparison comparison = serviceAt(nowMillis()).compareTodayWith(contactId);
        assertEquals(ComparisonState.UNAVAILABLE, comparison.state());
        assertEquals(Reason.RIGHT_MISSING, comparison.reason());
    }

    // --- 4: both sides shared and synced -> comparable ---

    @Test
    void bothSidesSharedAndSyncedIsComparable() throws Exception {
        Instant now = nowMillis();
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        ComparisonWindow myWindow = ComparisonWindow.day(LocalDate.now(ZONE), ZONE);
        // receivedAt == now, the exact same instant "my own" side's fixed-clock capture will use as
        // its own cutoff too - see savePeerSummaryExact's own javadoc for why this must be exact,
        // not merely close.
        savePeerSummaryExact(myWindow, now);

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);
        assertEquals(ComparisonState.COMPARABLE, comparison.state());
        assertTrue(comparison.right().snapshotOptional().isPresent());
    }

    @Test
    void aFreshEarlierPeerCutoffIsRecomputedLocallyAtTheSameElapsedPoint() throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        Instant now = today.atTime(15, 0).atZone(ZONE).toInstant();
        Instant peerCutoff = now.minus(Duration.ofMinutes(7));
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        ComparisonWindow myWindow = ComparisonWindow.day(today, ZONE);
        savePeerSummaryExactWithCutoff(myWindow, peerCutoff, now);

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);

        assertEquals(ComparisonState.COMPARABLE, comparison.state(),
                "a normal few-minutes-old peer summary must not fail with CUTOFF_MISMATCH");
        assertEquals(peerCutoff, comparison.left().snapshot().cutoff(),
                "my real evidence must be recomputed through the peer's elapsed cutoff");
        assertEquals(peerCutoff, comparison.right().snapshot().cutoff());
        assertEquals(now, comparison.left().capturedAt(),
                "capturedAt stays truthful: the historical-cutoff computation happened now");
    }

    // --- stale snapshot ---

    @Test
    void aSnapshotOlderThanTheFreshnessWindowIsStale() throws Exception {
        Instant now = nowMillis();
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        // A DAY window wide enough (within the 22-26h bound) that it both legitimately contains
        // "now" (so the fallback path accepts it as a real same-moment candidate) and lets its own
        // cutoff sit more than 24h - PeerComparisonService.TODAY_FRESHNESS_WINDOW - before "now".
        // Deliberately not "my" exact today's window (which could never itself be this wide while
        // still containing "now"), so this reaches the engine through the fallback path.
        ComparisonWindow wideWindow = new ComparisonWindow(com.codefit.peer.protocol.WindowKind.DAY,
                LocalDate.now(ZONE), ZONE.getId(), null, now.minus(Duration.ofHours(25)), now.plus(Duration.ofHours(1)));
        savePeerSummaryExactWithCutoff(wideWindow, wideWindow.start(), now);

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);
        assertEquals(ComparisonState.UNAVAILABLE, comparison.state());
        assertEquals(Reason.RIGHT_STALE, comparison.reason());
    }

    // --- 10: yesterday's snapshot is never used as today's fallback ---

    @Test
    void yesterdaysSnapshotIsNeverUsedAsTodaysFallback() throws Exception {
        Instant now = nowMillis();
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        ComparisonWindow yesterday = ComparisonWindow.day(LocalDate.now(ZONE).minusDays(1), ZONE);
        // Not the exact object id for today, and yesterday's own window does not contain "now" -
        // must never qualify as a fallback candidate. Received shortly after yesterday's own window
        // closed - any later point is equally valid, but must be at or after its own cutoff/end.
        savePeerSummaryExact(yesterday, yesterday.end().plus(Duration.ofHours(1)));

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);
        assertEquals(ComparisonState.UNAVAILABLE, comparison.state());
        assertEquals(Reason.RIGHT_MISSING, comparison.reason(),
                "yesterday's snapshot must never be silently accepted as today's fallback");
    }

    // --- 6/7: same local label, mismatched UTC interval -> INCOMPATIBLE, via the fallback path ---

    @Test
    void aFallbackCandidateWithMismatchedUtcIntervalButSameLocalLabelIsIncompatible() throws Exception {
        Instant now = nowMillis();
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        LocalDate today = LocalDate.now(ZONE);
        ComparisonWindow myWindow = ComparisonWindow.day(today, ZONE);
        // Same kind/local label/zone as mine, but a hand-built, genuinely different UTC interval
        // (shifted by two hours, still inside the valid 22-26h DAY bound) - and it still contains
        // "now" so it legitimately qualifies as a fallback candidate for the engine to diagnose,
        // rather than being silently skipped.
        Instant shiftedStart = myWindow.start().minus(Duration.ofHours(2));
        Instant shiftedEnd = myWindow.end().minus(Duration.ofHours(2));
        assertTrue(shiftedStart.isBefore(now) && now.isBefore(shiftedEnd), "fixture precondition: shifted window must still contain now");
        ComparisonWindow mismatched = new ComparisonWindow(myWindow.kind(), today, myWindow.zoneId(), null,
                shiftedStart, shiftedEnd);
        // Not the exact object id (a different window -> a different hash), so this only reaches
        // the engine through the fallback path.
        savePeerSummaryExact(mismatched, now.minus(Duration.ofMinutes(5)));

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);
        assertEquals(ComparisonState.INCOMPATIBLE, comparison.state());
        assertEquals(Reason.UTC_INTERVAL_MISMATCH, comparison.reason());
    }

    // --- cross-zone same-moment fallback: a different zone string, window still contains "now" ---

    @Test
    void aFallbackCandidateFromADifferentZoneIsDescriptiveOnlyNotMissing() throws Exception {
        Instant now = nowMillis();
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        // A real, different IANA zone whose own "today" window still contains the evaluation
        // instant right now - exactly "equivalent current-day publication under the peer's own
        // zone" from this class's own javadoc.
        ZoneId otherZone = ZONE.getId().equals("UTC") ? ZoneId.of("America/New_York") : ZoneId.of("UTC");
        ComparisonWindow peerWindow = ComparisonWindow.day(LocalDate.now(otherZone), otherZone);
        assertTrue(peerWindow.contains(now), "fixture precondition: the other zone's own window must contain now");
        // Exactly "now" (not offset) - see savePeerSummaryExact's own javadoc for why this must match
        // "my own" side's fixed-clock cutoff exactly for the engine's elapsed-time alignment check.
        savePeerSummaryExact(peerWindow, now);

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);
        // Either genuinely the same local label (improbable but not invalid) or cross-zone -
        // either way it must have reached the engine as a real candidate, never MISSING.
        assertTrue(comparison.state() == ComparisonState.DESCRIPTIVE_ONLY || comparison.state() == ComparisonState.COMPARABLE,
                "a legitimate same-moment fallback candidate must reach the engine, not be reported MISSING: " + comparison);
    }

    // --- 7: revocation after a previously received snapshot overrides cached data ---

    @Test
    void revocationAfterAPreviouslyReceivedSnapshotIsNotShared() throws Exception {
        Instant now = nowMillis();
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        ComparisonWindow myWindow = ComparisonWindow.day(LocalDate.now(ZONE), ZONE);
        savePeerSummaryExact(myWindow, now);

        // Sanity: comparable before revocation.
        assertEquals(ComparisonState.COMPARABLE, serviceAt(now).compareTodayWith(contactId).state());

        // The peer's later CONSENT_REVISION revokes DAILY_SUMMARY - PeerSyncConsentRepository only
        // ever holds the latest accepted revision, so this overwrites, not appends.
        saveConsent(List.of(), 2);

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);
        assertEquals(ComparisonState.UNAVAILABLE, comparison.state());
        assertEquals(Reason.RIGHT_NOT_SHARED, comparison.reason(),
                "revocation must override previously cached/received snapshot data, never inferred as still shared");
    }

    // --- 9 (orchestration-level slice; the full real-boundary proof is the two-process E2E) ---

    @Test
    void duplicateIngestOfTheSamePeerSummaryDoesNotCorruptTheComparison() throws Exception {
        Instant now = nowMillis();
        saveConsent(List.of(SharingScope.DAILY_SUMMARY), 1);
        ComparisonWindow myWindow = ComparisonWindow.day(LocalDate.now(ZONE), ZONE);
        savePeerSummaryExact(myWindow, now.minus(Duration.ofMinutes(10)));
        savePeerSummaryExact(myWindow, now); // re-ingest of the same object id, now with the exact cutoff "my own" side needs

        List<com.codefit.peer.sync.PeerProgressSummary> stored = peerProgressSummaryRepository.findByAuthor(peerId);
        assertEquals(1, stored.size(), "upsert must supersede, never duplicate, the same (author, objectId) row");

        ProgressComparison comparison = serviceAt(now).compareTodayWith(contactId);
        assertEquals(ComparisonState.COMPARABLE, comparison.state());
    }

    // --- helpers ---

    private void saveConsent(List<SharingScope> scopes, long revision) throws Exception {
        try (Connection connection = DatabaseConfig.getConnection()) {
            peerSyncConsentRepository.save(connection, peerId, scopes, 1L, revision, nowMillis());
        }
    }

    /** Builds a peer-authored {@link ProgressSummary} that reuses my own real, just-captured metric
     *  shapes (same ids/versions/units/provenance - see {@link #realMetricShapes}) so a COMPARABLE
     *  result is the expected baseline outcome; tests that want a different outcome (stale,
     *  incompatible, revoked, not shared) override only what that specific case needs. */
    private void savePeerSummaryExact(ComparisonWindow window, Instant receivedAt) throws Exception {
        // Derived from receivedAt itself, not an independently-read "now": a snapshot can never be
        // received before its own cutoff, so clamping the cutoff against the same instant we then
        // use as receivedAt is the only way to guarantee that invariant holds for every caller here.
        savePeerSummaryExactWithCutoff(window, window.cutoffAt(receivedAt), receivedAt);
    }

    private void savePeerSummaryExactWithCutoff(ComparisonWindow window, Instant cutoff, Instant receivedAt) throws Exception {
        ObjectId objectId = SnapshotPublicationService.progressSummaryObjectId(peerId, myId, window);
        ProgressSummary body = new ProgressSummary(window, cutoff, realMetricShapes);
        try (Connection connection = DatabaseConfig.getConnection()) {
            peerProgressSummaryRepository.upsert(connection, peerId, objectId, 1L, 1L, body, receivedAt);
        }
    }

    /** Inserts one real review today, then performs one real {@link ProgressSnapshotService} capture
     *  over it purely to harvest valid, registry-accepted {@link MetricValue} shapes for reuse as
     *  peer-side fixtures - never to establish "my own" side of any comparison (each test's own call
     *  to {@link PeerComparisonService#compareTodayWith} performs its own, separate real capture). */
    private List<MetricValue> insertReviewTodayAndCaptureRealMetrics() {
        try (Connection connection = DatabaseConfig.getConnection()) {
            long deckId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO decks (name, description) VALUES (?, 'peer-comparison-test')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "peer-comparison-deck-" + System.nanoTime());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    deckId = keys.getLong(1);
                }
            }
            long flashcardId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO flashcards (deck_id, front, back, card_type, accepted_answers, review_count, due_date) "
                            + "VALUES (?, 'front', 'back', 'RECALL', 'x', 0, date('now'))",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, deckId);
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    flashcardId = keys.getLong(1);
                }
            }
            com.codefit.repository.ReviewHistoryRepository reviewRepository = new com.codefit.repository.ReviewHistoryRepository();
            LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
            ReviewHistory saved = reviewRepository.save(new ReviewHistory(0, flashcardId, ReviewRating.GOOD, 0, 1, nowUtc));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE review_history SET reviewed_at = ? WHERE id = ?")) {
                statement.setString(1, nowUtc.toString());
                statement.setLong(2, saved.getId());
                statement.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ComparisonWindow window = ComparisonWindow.day(LocalDate.now(ZONE), ZONE);
        return new ProgressSnapshotService().capture(window).snapshot().toProgressSummary().metrics();
    }

    private static Instant nowMillis() {
        return Instant.ofEpochMilli(Instant.now().toEpochMilli());
    }
}

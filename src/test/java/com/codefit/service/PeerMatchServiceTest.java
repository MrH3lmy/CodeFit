package com.codefit.service;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.peer.comparison.SnapshotComparisonEngine.ComparisonState;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.match.MatchRole;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.Audience;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.ConsentRevision;
import com.codefit.peer.protocol.Envelope;
import com.codefit.peer.protocol.EnvelopeCodec;
import com.codefit.peer.protocol.EnvelopeHeader;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.MatchInvitation;
import com.codefit.peer.protocol.MatchResponse;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.protocol.Tombstone;
import com.codefit.peer.protocol.TombstoneReason;
import com.codefit.peer.sync.SyncOutcome;
import com.codefit.repository.MatchRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lifecycle, idempotency, and progress-orchestration coverage for {@link PeerMatchService}. "My own"
 * side of every action is exercised through the real service; the peer's own wire messages are
 * hand-built and fed through the real, unmodified {@link PeerSyncIngestService} (the same pattern
 * {@code PeerSyncIngestServiceTest}/{@code PeerComparisonServiceTest} already use) - never a direct
 * repository poke standing in for the wire path. The one thing deliberately NOT exercised here is a
 * real two-process authenticated connection; that boundary is covered once, for real, by the
 * two-process product E2E instead.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerMatchServiceTest {

    private static final char[] PASSPHRASE = "peer-match-test-passphrase".toCharArray();
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private IdentityService identityService;
    private ContactService contactService;
    private PeerMatchService matchService;
    private MatchRepository matchRepository;
    private PeerProgressSummaryRepository peerProgressSummaryRepository;
    private PeerSyncConsentRepository peerSyncConsentRepository;
    private PeerSyncIngestService ingestService;

    private IdentityId myId;
    private KeyPair peerKeyPair;
    private IdentityKey peerIdentityKey;
    private IdentityId peerId;
    private long contactId;
    private List<MetricValue> realMetricShapes;

    @BeforeEach
    void setUp() {
        PeerIdentityTestTables.resetAll();
        identityService = new IdentityService();
        contactService = new ContactService();
        matchService = new PeerMatchService();
        matchRepository = new MatchRepository();
        peerProgressSummaryRepository = new PeerProgressSummaryRepository();
        peerSyncConsentRepository = new PeerSyncConsentRepository();
        ingestService = new PeerSyncIngestService();

        myId = identityService.createIdentity(PASSPHRASE, nowMillis()).id();
        peerKeyPair = KeyPairs.generate();
        peerIdentityKey = new IdentityKey(KeyPairs.rawPublicKey(peerKeyPair.getPublic()));
        peerId = peerIdentityKey.id();

        Contact pending = contactService.registerPendingContact(peerIdentityKey, "fake-peer", nowMillis());
        Contact paired = contactService.acceptInvitation(pending.id(), nowMillis());
        contactId = paired.id();

        realMetricShapes = insertReviewTodayAndCaptureRealMetrics();
    }

    // --- 1: create invitation ---

    @Test
    void startMatchCreatesAPendingChallengerInvitation() {
        StudyMatch match = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());

        assertEquals(MatchRole.CHALLENGER, match.role());
        assertEquals(MatchStatus.PENDING, match.status());
        assertEquals(MatchDuration.FIFTEEN_MINUTES, match.duration());
        assertEquals(contactId, match.contactId());
        // Starting a match pre-grants MATCH_PARTICIPATION so no separate consent step is ever needed.
        assertTrue(contactService.permissionsFor(contactId).orElseThrow().scopes().contains(SharingScope.MATCH_PARTICIPATION));
    }

    // --- 2/3: receive invitation, duplicate invitation idempotency ---

    @Test
    void receivingAnInvitationCreatesAPendingOpponentRow() {
        ObjectId matchId = randomMatchId(1);
        SyncOutcome outcome = ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.THIRTY_MINUTES, 1, 1, 1, nowMillis()), nowMillis());

        assertEquals(SyncOutcome.ACCEPTED, outcome);
        StudyMatch received = matchRepository.find(matchId).orElseThrow();
        assertEquals(MatchRole.OPPONENT, received.role());
        assertEquals(MatchStatus.PENDING, received.status());
        assertEquals(MatchDuration.THIRTY_MINUTES, received.duration());
    }

    @Test
    void theExactSameInvitationResentIsAnIdempotentDuplicateNoOp() {
        ObjectId matchId = randomMatchId(2);
        SignedEnvelope envelope = invitationEnvelope(matchId, MatchDuration.FIFTEEN_MINUTES, 1, 1, 1, nowMillis());

        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerId, myId, envelope, nowMillis()));
        assertEquals(SyncOutcome.DUPLICATE, ingestService.ingest(peerId, myId, envelope, nowMillis()));
        assertEquals(1, matchRepository.findByContact(contactId).size(), "a resent invitation must never create a second local row");
    }

    // --- 4/10: accept, both sides converge on the identical startedAt/endsAt ---

    @Test
    void acceptingSetsTheAgreedActiveWindow() {
        ObjectId matchId = randomMatchId(3);
        ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.FIFTEEN_MINUTES, 1, 1, 1, nowMillis()), nowMillis());
        Instant acceptAt = nowMillis();

        StudyMatch accepted = matchService.accept(matchId, acceptAt);

        assertEquals(MatchStatus.ACTIVE, accepted.status());
        assertEquals(acceptAt, accepted.startedAt());
        assertEquals(acceptAt.plusSeconds(15 * 60), accepted.endsAt());
        assertTrue(contactService.permissionsFor(contactId).orElseThrow().scopes().contains(SharingScope.MATCH_PARTICIPATION));
    }

    @Test
    void receivingTheAcceptanceActivatesTheChallengersOwnRowWithTheIdenticalWindow() {
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.THIRTY_MINUTES, nowMillis());
        Instant startedAt = nowMillis().plusSeconds(5);

        SyncOutcome outcome = ingestService.ingest(peerId, myId,
                responseEnvelope(invitation.matchId(), true, startedAt, 1, 1, 1, startedAt), nowMillis());

        assertEquals(SyncOutcome.ACCEPTED, outcome);
        StudyMatch mine = matchRepository.find(invitation.matchId()).orElseThrow();
        assertEquals(MatchStatus.ACTIVE, mine.status());
        // The challenger adopts the opponent's own declared startedAt verbatim - never computing its own.
        assertEquals(startedAt, mine.startedAt());
        assertEquals(startedAt.plusSeconds(30 * 60), mine.endsAt());
    }

    // --- 5: duplicate acceptance ---

    @Test
    void respondingTwiceToTheSameInvitationThrows() {
        ObjectId matchId = randomMatchId(4);
        ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.FIFTEEN_MINUTES, 1, 1, 1, nowMillis()), nowMillis());
        matchService.accept(matchId, nowMillis());

        assertThrows(IllegalStateException.class, () -> matchService.accept(matchId, nowMillis()));
        assertThrows(IllegalStateException.class, () -> matchService.decline(matchId, nowMillis()));
    }

    @Test
    void theExactSameResponseEnvelopeResentIsAnIdempotentDuplicateNoOp() {
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());
        Instant startedAt = nowMillis();
        SignedEnvelope envelope = responseEnvelope(invitation.matchId(), true, startedAt, 1, 1, 1, startedAt);

        assertEquals(SyncOutcome.ACCEPTED, ingestService.ingest(peerId, myId, envelope, nowMillis()));
        assertEquals(SyncOutcome.DUPLICATE, ingestService.ingest(peerId, myId, envelope, nowMillis()));
        assertEquals(MatchStatus.ACTIVE, matchRepository.find(invitation.matchId()).orElseThrow().status());
    }

    // --- 6: decline ---

    @Test
    void decliningMarksTheMatchDeclined() {
        ObjectId matchId = randomMatchId(5);
        ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.FIFTEEN_MINUTES, 1, 1, 1, nowMillis()), nowMillis());

        StudyMatch declined = matchService.decline(matchId, nowMillis());

        assertEquals(MatchStatus.DECLINED, declined.status());
        assertNotNull(declined); // acceptedAt/startedAt/endsAt all remain null - enforced by StudyMatch's own constructor
    }

    @Test
    void receivingADeclineMarksTheChallengersOwnRowDeclined() {
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());

        SyncOutcome outcome = ingestService.ingest(peerId, myId,
                responseEnvelope(invitation.matchId(), false, null, 1, 1, 1, nowMillis()), nowMillis());

        assertEquals(SyncOutcome.ACCEPTED, outcome);
        assertEquals(MatchStatus.DECLINED, matchRepository.find(invitation.matchId()).orElseThrow().status());
    }

    // --- 7: cancellation ---

    @Test
    void cancellingWithdrawsAPendingInvitation() {
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());

        StudyMatch cancelled = matchService.cancel(invitation.matchId(), nowMillis());

        assertEquals(MatchStatus.CANCELLED, cancelled.status());
    }

    @Test
    void receivingTheCancellationMarksTheOpponentsOwnRowCancelled() {
        ObjectId matchId = randomMatchId(6);
        ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.FIFTEEN_MINUTES, 1, 1, 1, nowMillis()), nowMillis());

        SyncOutcome outcome = ingestService.ingest(peerId, myId, cancellationEnvelope(matchId, 1, 2, 2, nowMillis()), nowMillis());

        assertEquals(SyncOutcome.ACCEPTED, outcome);
        assertEquals(MatchStatus.CANCELLED, matchRepository.find(matchId).orElseThrow().status());
    }

    // --- 8: invalid transitions rejected ---

    @Test
    void cancellingANonPendingMatchThrows() {
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());
        matchService.cancel(invitation.matchId(), nowMillis());

        assertThrows(IllegalStateException.class, () -> matchService.cancel(invitation.matchId(), nowMillis()));
    }

    @Test
    void aLateCancellationNeverRegressesAnAlreadyActiveOpponentRow() {
        ObjectId matchId = randomMatchId(7);
        ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.FIFTEEN_MINUTES, 1, 1, 1, nowMillis()), nowMillis());
        matchService.accept(matchId, nowMillis());

        // A cancellation that crossed the acceptance in flight must never regress an already-ACTIVE match.
        SyncOutcome outcome = ingestService.ingest(peerId, myId, cancellationEnvelope(matchId, 1, 2, 2, nowMillis()), nowMillis());

        assertEquals(SyncOutcome.ACCEPTED, outcome, "the tombstone envelope itself is still validly accepted at the protocol level");
        assertEquals(MatchStatus.ACTIVE, matchRepository.find(matchId).orElseThrow().status(),
                "but it must never regress this device's own already-ACTIVE match back to CANCELLED");
    }

    @Test
    void aLateResponseNeverRegressesAnAlreadyCancelledChallengerRow() {
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());
        matchService.cancel(invitation.matchId(), nowMillis());

        SyncOutcome outcome = ingestService.ingest(peerId, myId,
                responseEnvelope(invitation.matchId(), true, nowMillis(), 1, 1, 1, nowMillis()), nowMillis());

        assertEquals(SyncOutcome.ACCEPTED, outcome);
        assertEquals(MatchStatus.CANCELLED, matchRepository.find(invitation.matchId()).orElseThrow().status(),
                "a response that arrived after this device's own cancellation must never resurrect the match as ACTIVE");
    }

    // --- 9: ACTIVE match survives reload/restart ---

    @Test
    void anActiveMatchSurvivesANewServiceInstance() {
        ObjectId matchId = randomMatchId(8);
        ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.FIFTEEN_MINUTES, 1, 1, 1, nowMillis()), nowMillis());
        matchService.accept(matchId, nowMillis());

        PeerMatchService restarted = new PeerMatchService();
        StudyMatch reloaded = restarted.find(matchId).orElseThrow();
        assertEquals(MatchStatus.ACTIVE, reloaded.status());
    }

    // --- 11/12/13/14: match progress - real local evidence, real peer evidence, same-cutoff alignment ---

    @Test
    void myRealStudyEvidenceContributesToMatchProgress() {
        ObjectId matchId = randomMatchId(11);
        ingestService.ingest(peerId, myId, invitationEnvelope(matchId, MatchDuration.SIXTY_MINUTES, 1, 1, 1, nowMillis()), nowMillis());
        Instant startedAt = nowMillis();
        matchService.accept(matchId, startedAt);

        PeerMatchService.MatchProgressView view = matchService.compareProgress(matchId, startedAt.plusSeconds(5));

        assertTrue(view.comparison().left().snapshotOptional().isPresent(), "my own side must reflect real local evidence");
        assertFalse(view.comparison().left().snapshotOptional().get().metrics().isEmpty());
    }

    @Test
    void peerRealSyncedEvidenceContributesAndBothSidesAreEvaluatedAgainstTheSameCutoff() throws Exception {
        // I am the challenger; the peer is the opponent who accepts, then syncs their own real progress to me.
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.SIXTY_MINUTES, nowMillis());
        Instant startedAt = nowMillis();
        ingestService.ingest(peerId, myId, responseEnvelope(invitation.matchId(), true, startedAt, 1, 1, 1, startedAt), nowMillis());
        StudyMatch active = matchRepository.find(invitation.matchId()).orElseThrow();
        assertEquals(MatchStatus.ACTIVE, active.status());

        // The peer grants me MATCH_PARTICIPATION (their own consent to me) and syncs their real progress,
        // captured at a DIFFERENT real cutoff than mine will be - deliberately not coordinated.
        grantMatchParticipationFromPeer();
        ComparisonWindow matchWindow = active.window();
        Instant peerCutoff = matchWindow.cutoffAt(startedAt.plusSeconds(37));
        ObjectId summaryObjectId = SnapshotPublicationService.progressSummaryObjectId(peerId, myId, matchWindow);
        ProgressSummary peerBody = new ProgressSummary(matchWindow, peerCutoff, realMetricShapes);
        EnvelopeHeader summaryHeader = new EnvelopeHeader(0, peerIdentityKey, summaryObjectId, 1, 1000, 1,
                peerCutoff, peerCutoff.plus(Duration.ofDays(1)), Audience.direct(List.of(myId)));
        assertEquals(SyncOutcome.ACCEPTED,
                ingestService.ingest(peerId, myId, EnvelopeCodec.sign(new Envelope(summaryHeader, peerBody), peerKeyPair.getPrivate()),
                        peerCutoff));

        // My own capture happens at yet another, later real moment - no coordination with the peer's own cutoff.
        PeerMatchService.MatchProgressView view = matchService.compareProgress(invitation.matchId(), startedAt.plusSeconds(90));

        assertEquals(ComparisonState.COMPARABLE, view.comparison().state(),
                "an active match's two independently-timed captures must be comparable - never CUTOFF_MISMATCH "
                        + "merely because they were captured at different real moments (this feature's own extension to the engine)");
        assertFalse(view.comparison().metrics().isEmpty());
        assertTrue(view.verdict().isPresent());
    }

    // --- 15: peer disconnect does not destroy/block the match ---

    @Test
    void comparingProgressNeverRequiresOrChecksAnyLiveConnectionAndTheMatchItselfIsUnaffectedByDisconnection() {
        // PeerMatchService/MatchRepository never touch NetworkingService/PeerConnection at all - a
        // disconnected peer simply means no new peer data arrives; the match and its last-synced
        // data remain exactly as they were, and comparing never throws for lack of a connection.
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());
        Instant startedAt = nowMillis();
        ingestService.ingest(peerId, myId, responseEnvelope(invitation.matchId(), true, startedAt, 1, 1, 1, startedAt), nowMillis());

        PeerMatchService.MatchProgressView view = matchService.compareProgress(invitation.matchId(), startedAt.plusSeconds(10));

        assertEquals(MatchStatus.ACTIVE, matchRepository.find(invitation.matchId()).orElseThrow().status(),
                "the match itself must not disappear or change merely because no peer data has arrived");
        assertNotNull(view.comparison());
    }

    // --- 16: reconnect/resend remains idempotent ---

    @Test
    void thePendingInvitationIsOfferedForResendUntilRespondedAndNeverDuplicatesLocalState() {
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, nowMillis());

        List<StudyMatch> firstPass = matchRepository.pendingOutboundFor(contactId);
        List<StudyMatch> secondPass = matchRepository.pendingOutboundFor(contactId);

        assertEquals(1, firstPass.size());
        assertEquals(1, secondPass.size());
        assertEquals(invitation.matchId(), firstPass.get(0).matchId());
        assertEquals(1, matchRepository.findByContact(contactId).size(), "repeated offering must never duplicate the local row");
    }

    // --- 17/18: completed match never regresses, and its final result survives a restart ---

    @Test
    void aCompletedMatchNeverRegressesToActiveOnALateMessage() {
        // I am the challenger; the opponent's own acceptance already activated my own row, already
        // elapsed past its own 15-minute duration.
        Instant startedAt = nowMillis().minus(Duration.ofMinutes(20));
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, startedAt);
        ingestService.ingest(peerId, myId, responseEnvelope(invitation.matchId(), true, startedAt, 1, 1, 1, startedAt), startedAt);

        StudyMatch completed = matchRepository.completeIfPastEndsAt(invitation.matchId(), nowMillis());
        assertEquals(MatchStatus.COMPLETED, completed.status());

        // A genuinely new, later-revision response arriving after completion must never regress it back to ACTIVE.
        SyncOutcome outcome = ingestService.ingest(peerId, myId,
                responseEnvelope(invitation.matchId(), true, startedAt, 1, 2, 2, nowMillis()), nowMillis());
        assertEquals(SyncOutcome.ACCEPTED, outcome, "the envelope itself is still validly accepted at the protocol level");
        assertEquals(MatchStatus.COMPLETED, matchRepository.find(invitation.matchId()).orElseThrow().status(),
                "but it must never regress this device's own already-COMPLETED match back to ACTIVE");
    }

    @Test
    void completedMatchFinalResultReadableAfterRestart() {
        Instant startedAt = nowMillis().minus(Duration.ofMinutes(20));
        StudyMatch invitation = matchService.startMatch(contactId, MatchDuration.FIFTEEN_MINUTES, startedAt);
        ingestService.ingest(peerId, myId, responseEnvelope(invitation.matchId(), true, startedAt, 1, 1, 1, startedAt), startedAt);
        matchRepository.completeIfPastEndsAt(invitation.matchId(), nowMillis());

        PeerMatchService restarted = new PeerMatchService();
        PeerMatchService.MatchProgressView view = restarted.compareProgress(invitation.matchId(), nowMillis());

        assertEquals(MatchStatus.COMPLETED, restarted.find(invitation.matchId()).orElseThrow().status());
        assertNotNull(view.comparison());
    }

    // --- helpers ---

    private void grantMatchParticipationFromPeer() throws Exception {
        try (Connection connection = DatabaseConfig.getConnection()) {
            peerSyncConsentRepository.save(connection, peerId, List.of(SharingScope.MATCH_PARTICIPATION), 1L, 1L, nowMillis());
        }
    }

    private Audience toMe() {
        return Audience.direct(List.of(myId));
    }

    private SignedEnvelope sign(Envelope envelope) {
        return EnvelopeCodec.sign(envelope, peerKeyPair.getPrivate());
    }

    private SignedEnvelope invitationEnvelope(ObjectId matchId, MatchDuration duration, long epoch, long sequence, long revision,
                                               Instant now) {
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, matchId, epoch, sequence, revision,
                now, now.plus(Duration.ofDays(1)), toMe());
        return sign(new Envelope(header, new MatchInvitation(duration)));
    }

    private SignedEnvelope responseEnvelope(ObjectId matchId, boolean accepted, Instant startedAt, long epoch, long sequence,
                                             long revision, Instant now) {
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, matchId, epoch, sequence, revision,
                now, now.plus(Duration.ofDays(1)), toMe());
        return sign(new Envelope(header, new MatchResponse(accepted, startedAt)));
    }

    private SignedEnvelope cancellationEnvelope(ObjectId matchId, long epoch, long sequence, long revision, Instant now) {
        EnvelopeHeader header = new EnvelopeHeader(0, peerIdentityKey, matchId, epoch, sequence, revision,
                now, now.plus(Duration.ofDays(1)), toMe());
        return sign(new Envelope(header, new Tombstone(com.codefit.peer.protocol.MessageType.MATCH_INVITATION,
                TombstoneReason.REVOKED, false)));
    }

    private static ObjectId randomMatchId(int seed) {
        byte[] bytes = new byte[ObjectId.LENGTH];
        bytes[0] = (byte) seed;
        bytes[ObjectId.LENGTH - 1] = (byte) (seed >>> 8);
        return new ObjectId(bytes);
    }

    private List<MetricValue> insertReviewTodayAndCaptureRealMetrics() {
        try (Connection connection = DatabaseConfig.getConnection()) {
            long deckId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO decks (name, description) VALUES (?, 'peer-match-test')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "peer-match-deck-" + System.nanoTime());
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

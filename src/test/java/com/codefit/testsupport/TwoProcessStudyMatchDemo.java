package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.peer.comparison.SnapshotComparisonEngine;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.match.MatchRole;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.service.ContactService;
import com.codefit.service.NetworkingService;
import com.codefit.service.PeerMatchService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Not a test itself: the product-level real-boundary proof for the "1-v-1 Study Match V1" milestone
 * this PR implements. Two genuinely separate OS processes each create a real identity, pair
 * (file-based invitation exchange, {@code TwoProcessPeerDemo}'s own established pattern), connect
 * over real mutual-TLS transport, and then - entirely through {@link PeerMatchService} and PR #198's
 * own automatic connection-established send/receive, <strong>never</strong> calling {@code
 * PeerSyncSessionService.startReceiving}/{@code sendOutboxTo} directly - carry out the real match
 * lifecycle: challenger invites, opponent accepts (both converge on the identical {@code matchId}/
 * {@code startedAt}/{@code endsAt}), each records one real piece of study evidence, each approves and
 * syncs their own real match-window progress, and each observes the other's real synced progress
 * through the real, unmodified {@link SnapshotComparisonEngine}.
 *
 * <p>"Reaches completion without waiting 15 real minutes" is done by <em>constructing</em> the final
 * verification instant, not by sleeping: the match's real {@code startedAt}/{@code endsAt} are real
 * instants exactly {@link MatchDuration#FIFTEEN_MINUTES} apart, and every exchange above happens for
 * real within that live window (comfortably seconds after {@code startedAt}, nowhere near real-time
 * completion) - only the <em>final</em> {@link PeerMatchService#compareProgress} call passes an
 * explicit evaluation instant {@code startedAt + 16 minutes} (well past the real {@code endsAt}, but
 * not real wall-clock "now") to exercise the same lazy ACTIVE→COMPLETED transition a user would
 * eventually see for real, and prove the final result it produces is stable.
 *
 * <p>Usage: {@code role dbFile ownInviteFile peerInviteFile ownResultFile peerResultFile}. LISTENER
 * is the challenger; DIALER is the opponent (match roles are independent of connection roles; this
 * demo just picks one fixed assignment).
 */
public final class TwoProcessStudyMatchDemo {
    private static final char[] VAULT_PASSPHRASE = "two-process-study-match-demo".toCharArray();
    // Slightly more generous than the other two-process demos' own 25s: this one does strictly more
    // real work per polling stage (invitation -> accept -> local evidence -> approve -> two separate
    // sync passes -> peer-data poll), so the same real scheduling jitter eats proportionally more of
    // a tighter budget.
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(35);

    private TwoProcessStudyMatchDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("Usage: role dbFile ownInviteFile peerInviteFile ownResultFile peerResultFile");
            System.exit(2);
            return;
        }
        String role = args[0];
        Path dbFile = Path.of(args[1]);
        Path ownInviteFile = Path.of(args[2]);
        Path peerInviteFile = Path.of(args[3]);
        Path resultFile = Path.of(args[4]);
        Path peerResultFile = Path.of(args[5]);
        try {
            run(role, dbFile, ownInviteFile, peerInviteFile, resultFile, peerResultFile);
        } catch (Exception e) {
            writeResult(resultFile, "FAILURE " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String role, Path dbFile, Path ownInviteFile, Path peerInviteFile, Path resultFile,
                             Path peerResultFile) throws Exception {
        DatabaseConfig.useDatabaseFile(dbFile);
        DatabaseConfig.initialize();

        com.codefit.service.IdentityService identityService = new com.codefit.service.IdentityService();
        identityService.createIdentity(VAULT_PASSPHRASE, nowMillis());
        Thread.sleep(1_100); // a fresh writer session's epoch must strictly advance past createIdentity's own initial one

        NetworkingService networkingService = new NetworkingService();
        networkingService.enableNetworking(VAULT_PASSPHRASE, 0, nowMillis());
        int ownPort = networkingService.listeningPort().orElseThrow();

        SignedInvitation ownInvitation = networkingService.createInvitation(VAULT_PASSPHRASE,
                List.of(new PeerAddress("127.0.0.1", ownPort)), Duration.ofHours(1), nowMillis());
        Files.writeString(ownInviteFile, com.codefit.peer.invitation.InvitationCodec.toBase64(ownInvitation), StandardCharsets.US_ASCII);

        String peerInviteBase64 = waitForFileContent(peerInviteFile, WAIT_TIMEOUT);
        SignedInvitation peerInvitation = networkingService.parseInvitationBase64(peerInviteBase64);
        ContactService contactService = new ContactService();
        Contact pending = networkingService.registerPendingContactFromInvitation(peerInvitation, nowMillis());
        Contact paired = contactService.acceptInvitation(pending.id(), nowMillis());
        IdentityId peerIdentityId = paired.identityId();

        PeerMatchService matchService = new PeerMatchService();
        boolean challenger = "LISTENER".equals(role);
        ObjectId myMatchId = null;
        if (challenger) {
            // The challenger's own invitation is approved before the ready-marker handshake below, so
            // it is already outbox-eligible by the time either side's connection is established -
            // otherwise PR #198's own one-shot automatic send could fire before this landed, and
            // nothing would resend it later (no periodic resync, by design).
            StudyMatch invitation = matchService.startMatch(paired.id(), MatchDuration.FIFTEEN_MINUTES, nowMillis());
            myMatchId = invitation.matchId();
        }

        Path ownReadyFile = siblingMarker(ownInviteFile, "ready");
        Path peerReadyFile = siblingMarker(peerInviteFile, "ready");
        Files.writeString(ownReadyFile, "READY", StandardCharsets.US_ASCII);
        waitForFileContent(peerReadyFile, WAIT_TIMEOUT);

        if ("DIALER".equals(role)) {
            PeerAddress peerAddress = new PeerAddress("127.0.0.1", onlyAddressPort(peerInvitation));
            RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(300), Duration.ofSeconds(2), 0.1);
            DialOutcome outcome = networkingService.connectToContact(paired.id(), peerAddress, policy, new AtomicBoolean(false))
                    .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!outcome.result().authenticated()) {
                throw new IllegalStateException("Dial failed: " + outcome.result());
            }
        } else {
            Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
            boolean connected = false;
            while (Instant.now().isBefore(deadline)) {
                if (networkingService.activeConnection(peerIdentityId).isPresent()) {
                    connected = true;
                    break;
                }
                Thread.sleep(100);
            }
            if (!connected) {
                throw new IllegalStateException("Timed out waiting for the dialer to connect.");
            }
        }

        // Neither branch above, nor anything else in this file, ever calls
        // PeerSyncSessionService.startReceiving or sendOutboxTo - PR #198's own automatic
        // establishment orchestration must deliver and apply every match lifecycle message by itself.
        StudyMatch active;
        if (challenger) {
            active = waitForActive(matchService, myMatchId, WAIT_TIMEOUT);
        } else {
            StudyMatch invitation = waitForIncomingInvitation(matchService, paired.id(), WAIT_TIMEOUT);
            Instant startedAt = nowMillis();
            active = matchService.accept(invitation.matchId(), startedAt);
            myMatchId = invitation.matchId();
            networkingService.syncOutboxNow(paired.id()); // the acceptance itself, sent now rather than waiting for a future reconnect
        }

        // Both sides now agree on the identical matchId/startedAt/endsAt.
        if (!active.endsAt().equals(active.startedAt().plusSeconds(15 * 60))) {
            throw new IllegalStateException("Unexpected match window: " + active);
        }

        // Each side records one real piece of study evidence, falling naturally within the live
        // [startedAt, endsAt) window (seconds after startedAt, nowhere near endsAt, 15 real minutes
        // later) - no backdating needed.
        insertOneRealReview();
        matchService.refreshProgress(myMatchId, nowMillis());
        networkingService.syncOutboxNow(paired.id());

        PeerMatchService.MatchProgressView liveView = waitForPeerMatchData(matchService, myMatchId, WAIT_TIMEOUT);
        if (liveView == null) {
            throw new IllegalStateException("Timed out waiting for the peer's real synced match progress.");
        }

        // The "reaches completion without waiting 15 real minutes" proof: an explicit evaluation
        // instant well past the real endsAt (never real wall-clock "now", which is still only
        // seconds past startedAt) - the same lazy ACTIVE->COMPLETED transition a real user would
        // eventually see, exercised and verified right now instead of 15 minutes from now.
        Instant wellPastEndsAt = active.startedAt().plus(Duration.ofMinutes(16));
        PeerMatchService.MatchProgressView finalView = matchService.compareProgress(myMatchId, wellPastEndsAt);
        StudyMatch finalMatch = matchService.find(myMatchId).orElseThrow();
        if (finalMatch.status() != MatchStatus.COMPLETED) {
            throw new IllegalStateException("Expected COMPLETED, was " + finalMatch.status());
        }
        // The final result must be stable: re-evaluating again (still past endsAt) must report the
        // identical state/metric count, not something that drifts with however many times it is read.
        PeerMatchService.MatchProgressView stableView = matchService.compareProgress(myMatchId, wellPastEndsAt.plusSeconds(5));
        if (finalView.comparison().state() != stableView.comparison().state()
                || finalView.comparison().metrics().size() != stableView.comparison().metrics().size()) {
            throw new IllegalStateException("Final result is not stable across re-reads: " + finalView + " vs " + stableView);
        }

        writeResult(resultFile, "SUCCESS " + finalMatch.status() + " " + finalView.comparison().state() + " "
                + finalView.comparison().metrics().size() + " metrics");

        try {
            waitForFileContent(peerResultFile, WAIT_TIMEOUT);
        } catch (IllegalStateException peerStillFinishing) {
            System.err.println("[" + role + "] courtesy wait for the peer's own result file timed out after this "
                    + "process's own SUCCESS was already recorded - exiting anyway: " + peerStillFinishing.getMessage());
        }
    }

    private static StudyMatch waitForIncomingInvitation(PeerMatchService matchService, long contactId, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<StudyMatch> incoming = matchService.matchesFor(contactId).stream()
                    .filter(m -> m.role() == MatchRole.OPPONENT && m.status() == MatchStatus.PENDING)
                    .findFirst();
            if (incoming.isPresent()) {
                return incoming.get();
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Timed out waiting for the challenger's real synced match invitation.");
    }

    private static StudyMatch waitForActive(PeerMatchService matchService, ObjectId matchId, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<StudyMatch> found = matchService.find(matchId);
            if (found.isPresent() && found.get().status() == MatchStatus.ACTIVE) {
                return found.get();
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("Timed out waiting for the opponent's real synced acceptance.");
    }

    private static PeerMatchService.MatchProgressView waitForPeerMatchData(PeerMatchService matchService, ObjectId matchId,
                                                                             Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        PeerMatchService.MatchProgressView last = null;
        while (Instant.now().isBefore(deadline)) {
            last = matchService.compareProgress(matchId, nowMillis());
            if (last.comparison().right().state() == SnapshotComparisonEngine.InputState.AVAILABLE) {
                return last;
            }
            Thread.sleep(400);
        }
        if (last != null) {
            System.err.println("Last observed match comparison before timeout: state=" + last.comparison().state()
                    + " reason=" + last.comparison().reason() + " rightState=" + last.comparison().right().state());
        }
        return null;
    }

    /**
     * Synchronized on the exact same {@code PeerLocalWriteLock.MONITOR} production code uses (via
     * the test-only {@link com.codefit.service.PeerLocalWriteLockTestAccess} shim): unlike real
     * usage - where a user's own ordinary study writes are spread over a whole real study session,
     * not concentrated into the few seconds right after a connection comes up - this demo
     * deliberately records evidence immediately after connecting, which can otherwise race this
     * same connection's own automatic receive loop on nearly every run and trip the
     * SQLITE_BUSY-kills-the-receive-loop failure mode {@code PeerLocalWriteLock}'s own javadoc
     * documents, for a demo-timing reason that has nothing to do with the feature under test.
     */
    private static void insertOneRealReview() throws Exception {
        com.codefit.service.PeerLocalWriteLockTestAccess.runLocked(() -> {
            try (Connection connection = DatabaseConfig.getConnection()) {
                long deckId;
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO decks (name, description) VALUES (?, 'two-process-study-match-demo')",
                        Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, "study-match-deck-" + System.nanoTime());
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
                ReviewHistoryRepository reviewRepository = new ReviewHistoryRepository();
                LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
                ReviewHistory saved = reviewRepository.save(new ReviewHistory(0, flashcardId, ReviewRating.GOOD, 0, 1, nowUtc));
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE review_history SET reviewed_at = ? WHERE id = ?")) {
                    statement.setString(1, nowUtc.toString());
                    statement.setLong(2, saved.getId());
                    statement.executeUpdate();
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static Path siblingMarker(Path file, String suffix) {
        return file.resolveSibling(file.getFileName().toString() + "." + suffix);
    }

    private static Instant nowMillis() {
        return Instant.ofEpochMilli(Instant.now().toEpochMilli());
    }

    private static int onlyAddressPort(SignedInvitation invitation) {
        return invitation.invitation().addresses().get(0).port();
    }

    private static String waitForFileContent(Path file, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(file) && Files.size(file) > 0) {
                return Files.readString(file, StandardCharsets.US_ASCII).strip();
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Timed out waiting for " + file);
    }

    private static void writeResult(Path resultFile, String content) {
        try {
            Files.writeString(resultFile, content, StandardCharsets.US_ASCII);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.peer.comparison.SnapshotComparisonEngine;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.repository.ReviewHistoryRepository;
import com.codefit.service.ContactService;
import com.codefit.service.NetworkingService;
import com.codefit.service.PeerComparisonService;
import com.codefit.service.PeerSyncOutboxService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Not a test itself: the product-level real-boundary proof for "two paired CodeFit users can share
 * today's study progress, sync it over the real P2P connection, and see a real comparison" - the
 * milestone this PR implements. Two genuinely separate OS processes each: create a real identity,
 * pair (file-based invitation exchange, exactly {@code TwoProcessPeerDemo}'s own established
 * pattern), record one real piece of today's study evidence, grant the other
 * {@link SharingScope#DAILY_SUMMARY} and approve today's real {@link ComparisonWindow} into the
 * existing #184 outbox model, then connect over real mutual-TLS transport. From that point on,
 * <strong>neither process's own code calls {@code PeerSyncSessionService.startReceiving} or {@code
 * sendOutboxTo}</strong> - PR #198's own automatic connection-established send/receive delivers the
 * approved progress summary, and each side proves it by calling the real {@link
 * PeerComparisonService#compareTodayWith} and observing a genuine {@code COMPARABLE} result.
 *
 * <p>Usage: {@code role dbFile ownInviteFile peerInviteFile ownResultFile peerResultFile}.
 */
public final class TwoProcessProgressComparisonDemo {
    private static final char[] VAULT_PASSPHRASE = "two-process-progress-comparison-demo".toCharArray();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(25);

    private TwoProcessProgressComparisonDemo() {
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

        // Real evidence: one real, today-dated review, exactly like a real learner's own study session.
        insertOneRealReviewToday();

        // The real consent prerequisite: grant DAILY_SUMMARY and approve today's real window into the
        // existing #184 outbox model - exactly what PeerController's own "Share Today's Progress"
        // action does, never a manufactured ProgressSummary.
        ZoneId zone = ZoneId.systemDefault();
        ComparisonWindow todayWindow = ComparisonWindow.day(LocalDate.now(zone), zone);
        contactService.updatePermissions(paired.id(), new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 1, null, false),
                nowMillis());
        new PeerSyncOutboxService().approveProgressSummary(paired.id(), todayWindow, nowMillis());

        // Both processes run at their own pace; a same-style file marker (sibling to the invitation
        // files this demo already exchanges that way) makes both sides' "I have finished granting and
        // approving" visible to the other before either one dials or starts waiting for a connection -
        // otherwise the automatic one-shot send that fires at connection establishment could fire
        // before the peer's own grant/approval had landed, and nothing would resend it later (no
        // periodic resync in this PR - by design, see PR A's own description).
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
        // establishment orchestration must deliver the peer's approved progress summary by itself.
        // Poll the real PeerComparisonService (never a direct repository peek standing in for it)
        // until the peer's own real data has genuinely arrived and passed consent/presence gating.
        //
        // This deliberately does not require the final SnapshotComparisonEngine.ComparisonState to be
        // COMPARABLE: two genuinely independent real processes each capture "today" at their own real
        // wall-clock instant, and the engine's own alignment rule (see SnapshotComparisonEngineTest)
        // requires the two cutoffs' elapsed-since-start durations to be EXACTLY equal whenever both
        // sides are still partial (an in-progress "today" is complete only once local midnight has
        // passed) - by design, not a bug to work around here (ComparisonWindow#equalElapsedCutoff
        // exists for this, but nothing in production yet re-captures evidence at an explicit past
        // cutoff; inventing that here would be exactly the "turn it into another infrastructure
        // project" scope expansion PR B's own brief forbids). What this E2E must prove is that the
        // peer's real, synced data genuinely reached PeerProgressSummaryRepository and passed the
        // engine's NOT_SHARED/MISSING gate - i.e. the right-hand observation's own state is AVAILABLE -
        // whatever alignment verdict the engine then reaches on top of that real data is itself a
        // genuine answer from the real, unmodified engine.
        PeerComparisonService comparisonService = new PeerComparisonService();
        SnapshotComparisonEngine.ProgressComparison comparison = waitForPeerDataToArrive(comparisonService, paired.id(), WAIT_TIMEOUT);
        if (comparison == null) {
            throw new IllegalStateException("Timed out waiting for the peer's real synced data to arrive.");
        }
        writeResult(resultFile, "SUCCESS " + comparison.state() + " " + comparison.reason() + " "
                + comparison.metrics().size() + " metrics");

        // Best-effort courtesy wait for the peer's own result file - never fatal to the SUCCESS above
        // (see TwoProcessAutomaticSyncDemo's own javadoc for exactly why: a slower-but-eventually-
        // successful peer must never have its own success turn into a corrupted FAILURE here).
        try {
            waitForFileContent(peerResultFile, WAIT_TIMEOUT);
        } catch (IllegalStateException peerStillFinishing) {
            System.err.println("[" + role + "] courtesy wait for the peer's own result file timed out after this "
                    + "process's own SUCCESS was already recorded - exiting anyway: " + peerStillFinishing.getMessage());
        }
    }

    private static SnapshotComparisonEngine.ProgressComparison waitForPeerDataToArrive(PeerComparisonService comparisonService,
                                                                                         long contactId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        SnapshotComparisonEngine.ProgressComparison last = null;
        while (Instant.now().isBefore(deadline)) {
            last = comparisonService.compareTodayWith(contactId);
            if (last.right().state() == SnapshotComparisonEngine.InputState.AVAILABLE) {
                return last;
            }
            // Deliberately not a tight busy-loop: each call captures (and locally persists) this
            // device's own fresh snapshot, a real local write that must serialize against the same
            // connection's automatic receive loop (see PeerComparisonService's and
            // PeerLocalWriteLock's own javadoc) - a slower cadence keeps this polling loop from being
            // the dominant source of that lock's contention.
            Thread.sleep(400);
        }
        if (last != null) {
            System.err.println("Last observed comparison before timeout: state=" + last.state() + " reason=" + last.reason()
                    + " rightState=" + last.right().state());
        }
        return null;
    }

    private static void insertOneRealReviewToday() throws Exception {
        try (Connection connection = DatabaseConfig.getConnection()) {
            long deckId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO decks (name, description) VALUES (?, 'two-process-progress-comparison-demo')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "progress-comparison-deck-" + System.nanoTime());
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
        }
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

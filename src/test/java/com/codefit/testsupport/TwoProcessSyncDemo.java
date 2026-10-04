package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import com.codefit.model.ReviewHistory;
import com.codefit.model.ReviewRating;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.identity.TrustState;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.EnvelopeCodec;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.PeerConnection;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.service.ContactService;
import com.codefit.service.IdentityService;
import com.codefit.service.NetworkingService;
import com.codefit.service.PeerSyncIngestService;
import com.codefit.service.PeerSyncOutboxService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Not a test itself: a standalone entry point {@code TwoProcessSyncDemoTest} launches as a real,
 * separate OS process per step, following exactly the same "two independent processes, separate
 * SQLite files, separate identities, real sockets" pattern {@code TwoProcessPeerDemo} established for
 * #182 - extended here to drive #184's actual sync engine ({@code PeerSyncSessionService}) end to end.
 *
 * <p>The {@code SYNC} action no longer drives that engine directly: once connection establishment is
 * automatically wired to send-then-receive (the PR this class's own javadoc is being updated for), a
 * second, independent {@code PeerSyncSessionService} manually started on the very same connection would
 * race the automatic one to read it, nondeterministically splitting received frames between the two.
 * Instead, {@code SYNC} observes the automatic receive loop's own outcomes through an optional listener
 * {@code NetworkingService} exposes for exactly this - see its constructor and field javadoc.
 *
 * <p>Every invocation is deliberately short-lived: it opens its own database file, re-establishes
 * networking fresh (a brand-new writer-session epoch - proving restart survival structurally, not by
 * assertion), performs exactly one named {@code action}, writes a result line, and exits. The driving
 * JUnit test launches a short sequence of these processes - sometimes one at a time, sometimes a
 * LISTENER and a DIALER concurrently for one connection - and, between process launches, inspects each
 * side's SQLite file directly (via {@code DatabaseConfig.useDatabaseFile} in the test's own JVM, after
 * the owning process has fully exited) rather than needing any special introspection API.
 *
 * <p>Usage: {@code role dbFile action resultFile [action-specific args...]}, where {@code role} is
 * {@code A} or {@code B} (used only to select which side's port/invite files to read/write by
 * convention - see the test for the exact file names). Actions:
 * <ul>
 *   <li>{@code INIT ownInviteFile ownPortFile} - create the identity if it doesn't exist yet, enable
 *       networking, write this device's invitation and listening port.</li>
 *   <li>{@code PAIR peerInviteFile} - read the peer's invitation and pair with it (idempotent: already
 *       paired is a no-op).</li>
 *   <li>{@code GRANT peerPortFile windowDate} - grant {@code DAILY_SUMMARY} to the (sole) paired
 *       contact with a generous historical window, and approve that window's progress summary for
 *       ongoing sharing. {@code peerPortFile} is unused here but kept for argument-position symmetry.</li>
 *   <li>{@code SYNC LISTENER|DIALER ownPortFile peerPortFile captureFile} - publish this launch's own
 *       (freshly re-bound) port to {@code ownPortFile}, then connect (dial the port published in
 *       {@code peerPortFile}, or wait to be dialed). Connection establishment alone already triggers an
 *       automatic outbox send and starts an automatic receive loop (this PR's own subject); this action
 *       just gives that loop a bounded window to run, appending every accepted {@code PROGRESS_SUMMARY}
 *       frame it reports to {@code captureFile} as {@code revision:hexFrame}, before disconnecting.</li>
 *   <li>{@code REVOKE} - revoke every scope from the (sole) paired contact.</li>
 *   <li>{@code REPLAY captureFile revision} - decode the frame captured for {@code revision} and feed
 *       it directly to this device's own {@code PeerSyncIngestService} (no network involved) -
 *       simulates a peer that was offline during a revocation later replaying a stale copy.</li>
 * </ul>
 */
public final class TwoProcessSyncDemo {
    private static final char[] VAULT_PASSPHRASE = "two-process-sync-demo-passphrase".toCharArray();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration SYNC_WINDOW = Duration.ofSeconds(4);
    private static final HexFormat HEX = HexFormat.of();

    private TwoProcessSyncDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.err.println("Usage: role dbFile action resultFile [action-specific args...]");
            System.exit(2);
        }
        String role = args[0];
        Path dbFile = Path.of(args[1]);
        String action = args[2];
        Path resultFile = Path.of(args[3]);
        try {
            run(role, dbFile, action, resultFile, args);
            writeResult(resultFile, "SUCCESS");
        } catch (Exception e) {
            writeResult(resultFile, "FAILURE " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String role, Path dbFile, String action, Path resultFile, String[] args) throws Exception {
        DatabaseConfig.useDatabaseFile(dbFile);
        DatabaseConfig.initialize();
        // Every launch is a fresh JVM/process; crossing a whole wall-clock second guarantees the new
        // writer session's clock-derived epoch (WriterEpoch, protocol §10.1) is strictly newer than
        // whatever this identity's previous launch (if any) already used, however close together the
        // two process launches happen to run.
        Thread.sleep(1_100);

        IdentityService identityService = new IdentityService();
        Instant now = nowMillis();
        if (identityService.currentIdentity().isEmpty()) {
            identityService.createIdentity(VAULT_PASSPHRASE, now);
            Thread.sleep(1_100);
        }

        // SYNC is the only action that needs to observe received frames; this reference is set just
        // before doSync runs so the listener below (installed once, up front, since PR A's own
        // automatic receive loop - not this class - now owns every established connection) knows where
        // to append them. See this class's own javadoc for why a second, independent
        // PeerSyncSessionService can no longer be created here to drive sync manually.
        AtomicReference<Path> activeCaptureFile = new AtomicReference<>();
        NetworkingService networkingService = new NetworkingService(event -> { }, (envelope, outcome) -> {
            Path captureFile = activeCaptureFile.get();
            if (captureFile != null && envelope != null && outcome.accepted() && envelope.body() instanceof ProgressSummary) {
                appendCapture(captureFile, envelope);
            }
        });
        networkingService.enableNetworking(VAULT_PASSPHRASE, 0, nowMillis());
        int ownPort = networkingService.listeningPort().orElseThrow();

        switch (action) {
            case "INIT" -> doInit(networkingService, Path.of(args[4]), Path.of(args[5]), ownPort);
            case "PAIR" -> doPair(networkingService, Path.of(args[4]));
            case "GRANT" -> doGrant(LocalDate.parse(args[4]), now);
            case "ADD_EVIDENCE" -> doAddEvidence(LocalDate.parse(args[4]));
            case "SYNC" -> doSync(networkingService, args[4], ownPort, Path.of(args[5]), Path.of(args[6]), Path.of(args[7]), activeCaptureFile);
            case "REVOKE" -> doRevoke();
            case "REPLAY" -> doReplay(identityService, Path.of(args[4]), Long.parseLong(args[5]));
            default -> throw new IllegalArgumentException("Unknown action " + action);
        }
        networkingService.disableNetworking();
    }

    private static void doInit(NetworkingService networkingService, Path ownInviteFile, Path ownPortFile, int ownPort)
            throws Exception {
        SignedInvitation ownInvitation = networkingService.createInvitation(VAULT_PASSPHRASE,
                List.of(new PeerAddress("127.0.0.1", ownPort)), Duration.ofHours(1), nowMillis());
        Files.writeString(ownInviteFile, InvitationCodec.toBase64(ownInvitation), StandardCharsets.US_ASCII);
        Files.writeString(ownPortFile, String.valueOf(ownPort), StandardCharsets.US_ASCII);
    }

    private static void doPair(NetworkingService networkingService, Path peerInviteFile) throws Exception {
        ContactService contactService = new ContactService();
        if (!contactService.listContacts().isEmpty()) {
            return; // already paired from an earlier launch
        }
        String peerInviteBase64 = waitForFileContent(peerInviteFile, WAIT_TIMEOUT);
        SignedInvitation peerInvitation = networkingService.parseInvitationBase64(peerInviteBase64);
        Contact pending = networkingService.registerPendingContactFromInvitation(peerInvitation, nowMillis());
        contactService.acceptInvitation(pending.id(), nowMillis());
    }

    private static void doGrant(LocalDate windowDate, Instant now) {
        ContactService contactService = new ContactService();
        Contact contact = contactService.listContacts().stream().filter(c -> c.trustState() == TrustState.PAIRED)
                .findFirst().orElseThrow(() -> new IllegalStateException("No paired contact to grant to."));
        contactService.updatePermissions(contact.id(), new PermissionGrant(List.of(SharingScope.DAILY_SUMMARY), 365, null, false), now);
        PeerSyncOutboxService outboxService = new PeerSyncOutboxService();
        outboxService.approveProgressSummary(contact.id(), ComparisonWindow.day(windowDate, ZoneId.of("UTC")), now);
    }

    /** Adds one genuine piece of study evidence (a reviewed flashcard) dated within {@code windowDate}, so a later re-capture of that same day's progress summary genuinely differs and bumps revision. */
    private static void doAddEvidence(LocalDate windowDate) throws Exception {
        long flashcardId;
        try (Connection connection = DatabaseConfig.getConnection()) {
            long deckId;
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO decks (name, description) VALUES (?, 'two-process-sync-demo')",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "sync-demo-deck-" + System.nanoTime());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    deckId = keys.getLong(1);
                }
            }
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
        }
        com.codefit.repository.ReviewHistoryRepository reviewRepository = new com.codefit.repository.ReviewHistoryRepository();
        ReviewHistory saved = reviewRepository.save(new ReviewHistory(0, flashcardId, ReviewRating.GOOD, 0, 1,
                windowDate.atTime(10, 0)));
        try (Connection connection = DatabaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE review_history SET reviewed_at = ? WHERE id = ?")) {
            statement.setString(1, windowDate.atTime(10, 0).toString());
            statement.setLong(2, saved.getId());
            statement.executeUpdate();
        }
    }

    private static void doSync(NetworkingService networkingService, String role, int ownPort, Path ownPortFile,
                                Path peerPortFile, Path captureFile, AtomicReference<Path> activeCaptureFile) throws Exception {
        // Every SYNC launch is a fresh process that re-enabled networking on a new ephemeral port
        // (port 0), so the port recorded by an earlier INIT (or an earlier SYNC) is already stale -
        // publish this launch's own current port before the peer (running concurrently) might try to
        // dial it.
        Files.writeString(ownPortFile, String.valueOf(ownPort), StandardCharsets.US_ASCII);
        activeCaptureFile.set(captureFile);

        ContactService contactService = new ContactService();
        Contact contact = contactService.listContacts().stream().filter(c -> c.trustState() == TrustState.PAIRED)
                .findFirst().orElseThrow(() -> new IllegalStateException("No paired contact to sync with."));

        PeerConnection connection;
        if ("DIALER".equals(role)) {
            int peerPort = Integer.parseInt(waitForFileContent(peerPortFile, WAIT_TIMEOUT));
            RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(300), Duration.ofSeconds(2), 0.1);
            DialOutcome outcome = networkingService.connectToContact(contact.id(), new PeerAddress("127.0.0.1", peerPort),
                    policy, new AtomicBoolean(false)).get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!outcome.result().authenticated()) {
                throw new IllegalStateException("Dial failed: " + outcome.result());
            }
            connection = outcome.connection();
        } else {
            Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
            Optional<PeerConnection> found = Optional.empty();
            while (Instant.now().isBefore(deadline) && found.isEmpty()) {
                found = networkingService.activeConnection(contact.identityId());
                if (found.isEmpty()) {
                    Thread.sleep(100);
                }
            }
            connection = found.orElseThrow(() -> new IllegalStateException("Timed out waiting for the dialer to connect."));
        }

        // Nothing here drives sync directly any more: by the time either branch above hands back
        // `connection`, PR A's own automatic establishment hook (NetworkingService.onConnectionEstablished)
        // has already synchronously sent this device's outbox and started the automatic receive loop on
        // this exact connection - whose outcomes feed the capture-file listener wired in at construction
        // (see activeCaptureFile above). This just gives that loop a bounded window to finish, then disconnects.
        Thread.sleep(SYNC_WINDOW.toMillis());
        connection.close();
    }

    private static void doRevoke() {
        ContactService contactService = new ContactService();
        Contact contact = contactService.listContacts().stream().filter(c -> c.trustState() == TrustState.PAIRED)
                .findFirst().orElseThrow();
        contactService.updatePermissions(contact.id(), PermissionGrant.revokeAll(), nowMillis());
    }

    private static void doReplay(IdentityService identityService, Path captureFile, long revision) throws Exception {
        String targetPrefix = revision + ":";
        String hexFrame = Files.readAllLines(captureFile, StandardCharsets.US_ASCII).stream()
                .filter(line -> line.startsWith(targetPrefix))
                .findFirst().map(line -> line.substring(targetPrefix.length()))
                .orElseThrow(() -> new IllegalStateException("No captured frame for revision " + revision));
        SignedEnvelope envelope = EnvelopeCodec.decodeFrame(HEX.parseHex(hexFrame));
        PeerSyncIngestService ingestService = new PeerSyncIngestService();
        var myId = identityService.currentIdentity().orElseThrow().id();
        var outcome = ingestService.ingest(envelope.header().author().id(), myId, envelope, nowMillis());
        Files.writeString(captureFile.resolveSibling(captureFile.getFileName() + ".replay-outcome"), outcome.name(),
                StandardCharsets.US_ASCII);
    }

    private static void appendCapture(Path captureFile, SignedEnvelope envelope) {
        try {
            String line = envelope.header().revision() + ":" + HEX.formatHex(EnvelopeCodec.encodeFrame(envelope)) + System.lineSeparator();
            Files.write(captureFile, line.getBytes(StandardCharsets.US_ASCII),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Instant nowMillis() {
        return Instant.ofEpochMilli(Instant.now().toEpochMilli());
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

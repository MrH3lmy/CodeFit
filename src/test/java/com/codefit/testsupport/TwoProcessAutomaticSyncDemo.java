package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.repository.PeerSyncConsentRepository;
import com.codefit.service.ContactService;
import com.codefit.service.NetworkingService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Not a test itself: a standalone entry point {@code TwoProcessAutomaticSyncDemoTest} launches as a
 * real, separate OS process to prove the one thing PR #197 deliberately left unwired and this PR
 * exists to wire up - that a real authenticated {@code NetworkingService} connection automatically
 * starts receiving and automatically sends its outbox, with <strong>no call anywhere in this file</strong>
 * to {@code PeerSyncSessionService.startReceiving} or {@code sendOutboxTo}. Every other #182/#184
 * two-process demo in this codebase ({@code TwoProcessPeerDemo}, {@code TwoProcessSyncDemo}) either
 * doesn't exercise sync at all or calls those two methods directly as the very thing it's testing -
 * this one deliberately never does, to prove the orchestration itself, not the primitives underneath
 * it (both already proven, respectively, by #182's demo and by {@code
 * PeerSyncSessionServiceReconnectRaceTest}/{@code TwoProcessSyncDemoTest}).
 *
 * <p>Usage: {@code role dbFile ownInviteFile peerInviteFile ownResultFile peerResultFile}. Both roles create an identity,
 * enable networking, exchange invitations through plain files (the out-of-band channel a real user
 * would use), pair, and - critically - each grants the OTHER side a real sharing scope via the
 * existing {@code ContactService.updatePermissions} consent model, exactly the prerequisite a real
 * paired user would already have satisfied. Role {@code DIALER} then actively connects via {@code
 * NetworkingService.connectToContact}; role {@code LISTENER} only enables networking and waits. After
 * that one call, BOTH roles simply poll their own already-open local database for the other side's
 * consent revision to show up - a real signal that can only appear if this device's own automatic
 * receive loop ingested a real signed envelope the other device's automatic outbox-send pass actually
 * sent, over the real authenticated transport, with neither side's own code in this file ever having
 * called either method directly.
 */
public final class TwoProcessAutomaticSyncDemo {
    private static final char[] VAULT_PASSPHRASE = "two-process-automatic-sync-demo".toCharArray();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(25);

    private TwoProcessAutomaticSyncDemo() {
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

        // A fresh writer session's epoch is derived from the wall clock at second granularity
        // (WriterEpoch, protocol §10.1) and must be strictly newer than createIdentity's own initial
        // epoch - the same real constraint TwoProcessPeerDemo's own comment documents.
        Thread.sleep(1_100);

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

        // The real, existing consent prerequisite: each side explicitly grants the other a sharing
        // scope, exactly like a real paired user would through a (future) consent control. historicalWindowDays
        // is irrelevant here (SOCIAL_PROFILE carries no window), but is set anyway for realism.
        contactService.updatePermissions(paired.id(), new PermissionGrant(List.of(SharingScope.SOCIAL_PROFILE), 1, null, false),
                nowMillis());

        // Both processes run their own independent sequence at their own pace; nothing above
        // guarantees the OTHER side has also finished pairing+granting by the time this side would
        // otherwise dial. The automatic outbox-send this PR wires up fires exactly once, right when
        // the connection is established - if the peer's own grant had not landed yet at that instant,
        // its one-shot send would simply have nothing to send and nothing retries it later (by design
        // - there is no periodic resync in this PR). A same-style file marker, sibling to the
        // invitation files this demo already exchanges that way, makes both sides' "I have finished
        // granting" visible to the other before either one dials or starts waiting for a connection.
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

        // Neither "DIALER" nor "LISTENER" branch above, nor anything else in this file, ever calls
        // PeerSyncSessionService.startReceiving or sendOutboxTo. From here on this process only reads
        // its own already-open database for a real signal that the OTHER side's automatic outbox send
        // was both sent and received by THIS side's automatic receive loop.
        boolean receivedPeerConsent = waitForConsentFrom(peerIdentityId, WAIT_TIMEOUT);
        if (!receivedPeerConsent) {
            throw new IllegalStateException("Timed out waiting to automatically receive the peer's consent revision.");
        }
        writeResult(resultFile, "SUCCESS " + peerIdentityId);

        // Do not let this process (and so its NetworkingService, and so its half of the real
        // authenticated connection) exit the instant ITS OWN receive succeeds - wait for the peer's own
        // result file first (the same real, file-based signal this whole demo already uses for the
        // invitation exchange), so a slower peer's own automatic send/receive still gets to finish
        // before either side's connection goes away.
        //
        // Deliberately best-effort and never fatal to the SUCCESS already written above: this is a
        // courtesy wait for the OTHER process, not a condition of THIS process's own correctness. A
        // review of an earlier version of this file correctly flagged the previous shape (this call
        // unguarded, so main()'s own outer catch would overwrite an already-true SUCCESS with FAILURE
        // on nothing more than the peer taking a little longer) as a circular/cascading shutdown
        // dependency - one slow-but-eventually-successful side corrupting the other's own already-
        // correct result. Catching and logging here, rather than letting it propagate, is the fix.
        try {
            waitForFileContent(peerResultFile, WAIT_TIMEOUT);
        } catch (IllegalStateException peerStillFinishing) {
            System.err.println("[" + role + "] courtesy wait for the peer's own result file timed out after this "
                    + "process's own SUCCESS was already recorded - exiting anyway: " + peerStillFinishing.getMessage());
        }
    }

    private static boolean waitForConsentFrom(IdentityId authorId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DatabaseConfig.getConnection()) {
                if (new PeerSyncConsentRepository().scopesFor(connection, authorId).isPresent()) {
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
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

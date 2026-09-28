package com.codefit.testsupport;

import com.codefit.config.DatabaseConfig;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.transport.DialOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.service.ContactService;
import com.codefit.service.NetworkingService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Not a test itself: a standalone entry point {@code TwoProcessPeerDemoTest} launches as a real,
 * separate OS process (its own JVM, its own isolated SQLite database, its own identity and transport
 * keys) to demonstrate #182's acceptance criterion literally — "two independent local processes with
 * separate databases and keys exchanging protocol messages over real sockets" — rather than only two
 * threads inside one process.
 *
 * <p>Usage: {@code role dbFile ownInviteFile peerInviteFileOrNONE resultFile}. Both roles create an
 * identity, enable networking, write their own invitation to {@code ownInviteFile}, and (if given a
 * peer invitation file) wait for it, pair with it, and grant it every scope so the pairing is
 * unambiguous. Role {@code DIALER} then actively connects; role {@code LISTENER} waits for the inbound
 * connection to appear. Both write {@code SUCCESS <fingerprint>} or {@code FAILURE <reason>} to
 * {@code resultFile} and exit 0/1 accordingly.
 */
public final class TwoProcessPeerDemo {
    private static final char[] VAULT_PASSPHRASE = "two-process-demo-passphrase".toCharArray();
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(25);

    private TwoProcessPeerDemo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("Usage: role dbFile ownInviteFile peerInviteFileOrNONE resultFile");
            System.exit(2);
        }
        String role = args[0];
        Path dbFile = Path.of(args[1]);
        Path ownInviteFile = Path.of(args[2]);
        String peerInviteArg = args[3];
        Path resultFile = Path.of(args[4]);

        try {
            run(role, dbFile, ownInviteFile, "NONE".equals(peerInviteArg) ? null : Path.of(peerInviteArg), resultFile);
        } catch (Exception e) {
            writeResult(resultFile, "FAILURE " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String role, Path dbFile, Path ownInviteFile, Path peerInviteFile, Path resultFile) throws Exception {
        DatabaseConfig.useDatabaseFile(dbFile);
        DatabaseConfig.initialize();

        com.codefit.service.IdentityService identityService = new com.codefit.service.IdentityService();
        identityService.createIdentity(VAULT_PASSPHRASE, nowMillis());

        // A fresh writer session's epoch is derived from the wall clock at second granularity
        // (WriterEpoch, protocol §10.1) and must be strictly newer than createIdentity's own initial
        // epoch; crossing a whole second guarantees enableNetworking's beginWriterSession succeeds.
        Thread.sleep(1_100);

        NetworkingService networkingService = new NetworkingService();
        networkingService.enableNetworking(VAULT_PASSPHRASE, 0, nowMillis());
        int ownPort = networkingService.listeningPort().orElseThrow();

        SignedInvitation ownInvitation = networkingService.createInvitation(VAULT_PASSPHRASE,
                List.of(new PeerAddress("127.0.0.1", ownPort)), Duration.ofHours(1), nowMillis());
        Files.writeString(ownInviteFile, com.codefit.peer.invitation.InvitationCodec.toBase64(ownInvitation), StandardCharsets.US_ASCII);

        if (peerInviteFile == null) {
            writeResult(resultFile, "SUCCESS no-peer-configured");
            return;
        }

        String peerInviteBase64 = waitForFileContent(peerInviteFile, WAIT_TIMEOUT);
        SignedInvitation peerInvitation = networkingService.parseInvitationBase64(peerInviteBase64);
        ContactService contactService = new ContactService();
        Contact pending = networkingService.registerPendingContactFromInvitation(peerInvitation, nowMillis());
        Contact paired = contactService.acceptInvitation(pending.id(), nowMillis());
        IdentityId peerIdentityId = paired.identityId();

        if ("DIALER".equals(role)) {
            PeerAddress peerAddress = new PeerAddress("127.0.0.1",
                    onlyAddressPort(peerInvitation));
            RetryPolicy policy = new RetryPolicy(5, Duration.ofMillis(300), Duration.ofSeconds(2), 0.1);
            DialOutcome outcome = networkingService.connectToContact(paired.id(), peerAddress, policy, new AtomicBoolean(false))
                    .get(WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!outcome.result().authenticated()) {
                throw new IllegalStateException("Dial failed: " + outcome.result());
            }
            writeResult(resultFile, "SUCCESS " + outcome.result().remoteIdentityId());
        } else {
            Instant deadline = Instant.now().plus(WAIT_TIMEOUT);
            while (Instant.now().isBefore(deadline)) {
                if (networkingService.activeConnection(peerIdentityId).isPresent()) {
                    writeResult(resultFile, "SUCCESS " + peerIdentityId);
                    return;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException("Timed out waiting for the dialer to connect.");
        }
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

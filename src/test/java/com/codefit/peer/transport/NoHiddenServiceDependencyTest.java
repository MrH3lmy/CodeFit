package com.codefit.peer.transport;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable evidence for #182's hard constraint (ADR-0001, runtime-dependency-inventory.md): no hosted
 * signaling, STUN/TURN, bootstrap servers, public IP-check services, gateways, cloud mailboxes, or
 * telemetry anywhere in the transport/invitation/discovery source. Complements
 * {@code com.codefit.peer.protocol.NoNetworkingContractTest}, which pins the #180/#181 boundary and the
 * still-unchanged dependency count; this test pins the new #182 packages specifically.
 */
class NoHiddenServiceDependencyTest {
    private static final List<Path> SCANNED_ROOTS = List.of(
            Path.of("src/main/java/com/codefit/peer/transport"),
            Path.of("src/main/java/com/codefit/peer/invitation"),
            Path.of("src/main/java/com/codefit/peer/discovery"));

    /** Any of these appearing in source is a hidden central-service dependency by definition. */
    private static final Pattern FORBIDDEN = Pattern.compile(String.join("|", List.of(
            "java\\.net\\.http", "HttpClient", "URLConnection", "URL\\(",
            "(?i)stun:", "(?i)turn:", "(?i)\\bstun\\.", "(?i)\\bturn\\.",
            "(?i)bootstrap.?node", "(?i)bootstrap.?peer", "(?i)bootstrap.?list",
            "(?i)signal(l)?ing.?server", "(?i)rendezvous",
            "ipfs", "kademlia", "dht\\.", "\\bDHT\\b",
            "checkip", "ifconfig\\.", "ipify", "myip\\.",
            "analytics", "telemetry", "crashlytics", "sentry\\.io",
            "InetAddress\\.getAllByName", "\\.resolve\\(\\)\\s*//\\s*dns")));

    /** A literal external hostname (a dotted domain with a common TLD) hardcoded into source. */
    private static final Pattern HARDCODED_EXTERNAL_HOST = Pattern.compile(
            "\"[a-zA-Z0-9.-]+\\.(com|net|org|io|dev|cloud)[\"/:]");

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    /** Strips {@code /** ... *&#47;}, {@code /* ... *&#47;}, and {@code // ...} comments before scanning code. */
    private static String stripComments(String source) {
        String withoutBlockComments = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return withoutBlockComments.replaceAll("//[^\\n]*", "");
    }

    @Test
    void transportInvitationAndDiscoverySourcesNameNoHostedService() throws IOException {
        int scanned = 0;
        for (Path root : SCANNED_ROOTS) {
            List<Path> files = javaFiles(root);
            scanned += files.size();
            for (Path file : files) {
                // Comments are stripped first: this project's own Javadoc explains, in prose, which
                // hosted services each class deliberately does NOT use (e.g. "no bootstrap list"), and
                // those explanatory mentions are not the forbidden usage this test guards against.
                String code = stripComments(Files.readString(file));
                var forbiddenMatch = FORBIDDEN.matcher(code);
                assertFalse(forbiddenMatch.find(), () -> file + " references a forbidden hosted-service pattern: " + forbiddenMatch.group());

                var hostMatch = HARDCODED_EXTERNAL_HOST.matcher(code);
                assertFalse(hostMatch.find(), () -> file + " hardcodes an external hostname: " + hostMatch.group());
            }
        }
        assertTrue(scanned > 10, "expected to scan #182's transport/invitation/discovery sources");
    }

    @Test
    void invitationCodeNeverPerformsDnsLookups() throws IOException {
        // PeerAddress itself is the one place InetAddress.getByName is allowed (and only for already
        // literal-validated input, per its own class doc). Invitation parsing must never call it directly.
        // LanDiscoveryService also calls it, but only ever on this project's own hardcoded multicast IP
        // literal (never a hostname or peer-supplied value) — see its class doc.
        for (Path file : javaFiles(Path.of("src/main/java/com/codefit/peer/invitation"))) {
            String source = Files.readString(file);
            assertFalse(source.contains("InetAddress.getByName") || source.contains("InetAddress.getAllByName"),
                    () -> file + " must not resolve any address; an invitation carries IP literals only");
        }
    }
}

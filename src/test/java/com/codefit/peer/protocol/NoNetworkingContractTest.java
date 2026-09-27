package com.codefit.peer.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable evidence for the #180/#181 boundary. #180's wire-format package
 * ({@code com.codefit.peer.protocol}) still opens no listener, performs no egress, and touches no
 * file, database, or key-generation API of its own — it is a pure codec. #181 adds local identity
 * generation, encrypted key storage, and encrypted backup files under the sibling
 * {@code com.codefit.peer.identity} package, exactly as ADR-0001 §1 says it would ("Local identity
 * creation and encrypted key storage belong to #181"); those APIs are therefore expected there and are
 * checked separately for the one thing that must not appear in either package: an actual networking
 * API. Both packages remain free of any networking capability until #182 adds a transport.
 */
class NoNetworkingContractTest {
    private static final Path PEER_PROTOCOL_SOURCES = Path.of("src/main/java/com/codefit/peer/protocol");
    private static final Path PEER_IDENTITY_SOURCES = Path.of("src/main/java/com/codefit/peer/identity");

    /** Networking APIs: forbidden in both {@code com.codefit.peer.protocol} and {@code com.codefit.peer.identity}. */
    private static final Pattern NETWORKING = Pattern.compile(String.join("|",
            "java\\.net\\.", "javax\\.net\\.", "java\\.nio\\.channels", "java\\.net\\.http",
            "\\bSocket\\b", "ServerSocket", "DatagramSocket", "MulticastSocket", "HttpClient", "URLConnection"));

    /**
     * Additionally forbidden inside {@code com.codefit.peer.protocol} specifically: it is a pure
     * value-type/codec package with no file, database, or key-generation capability of its own (#181
     * owns all of that, one package over).
     */
    private static final Pattern FORBIDDEN_IN_PROTOCOL_CODEC = Pattern.compile(NETWORKING.pattern() + "|" + String.join("|",
            "java\\.io\\.File\\b", "java\\.nio\\.file", "FileInputStream", "FileOutputStream",
            "java\\.sql", "DatabaseConfig", "KeyPairGenerator", "SecureRandom", "ProcessBuilder", "Runtime\\.getRuntime",
            "ObjectInputStream", "readObject", "Class\\.forName", "ScriptEngine"));

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void peerProtocolCodecSourcesUseNoNetworkingFileDatabaseOrKeyGenerationApis() throws IOException {
        List<Path> files = javaFiles(PEER_PROTOCOL_SOURCES);
        assertTrue(files.size() > 10, "expected the protocol codec sources to be scanned");
        for (Path file : files) {
            String source = Files.readString(file);
            var matcher = FORBIDDEN_IN_PROTOCOL_CODEC.matcher(source);
            assertFalse(matcher.find(), () -> file + " uses forbidden API: " + matcher.group());
        }
    }

    @Test
    void peerIdentitySourcesOpenNoNetworkingApis() throws IOException {
        List<Path> files = javaFiles(PEER_IDENTITY_SOURCES);
        assertTrue(files.size() > 5, "expected the identity sources to be scanned");
        for (Path file : files) {
            String source = Files.readString(file);
            var matcher = NETWORKING.matcher(source);
            assertFalse(matcher.find(), () -> file + " uses forbidden networking API: " + matcher.group());
        }
    }

    @Test
    void noRuntimeDependencyWasAddedForThisSlice() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        long dependencies = Pattern.compile("<dependency>").matcher(pom).results().count();
        assertEquals(5, dependencies, "javafx-controls, javafx-fxml, sqlite-jdbc, poi-ooxml, junit-jupiter only; "
                + "a transport/crypto dependency needs the inventory in docs/p2p/runtime-dependency-inventory.md updated first");
        for (String forbidden : List.of("libp2p", "netty", "bouncycastle", "tink", "ipfs", "webrtc", "ice4j", "jmdns")) {
            assertFalse(pom.toLowerCase().contains(forbidden), "unexpected dependency: " + forbidden);
        }
    }
}

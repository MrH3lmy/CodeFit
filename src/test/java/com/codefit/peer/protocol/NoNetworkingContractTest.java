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
 * Executable evidence for the #180 boundary: the protocol foundation opens no listener, performs no
 * egress, touches no files or database, generates no identity, adds no runtime dependency, and is not
 * wired into the running application.
 */
class NoNetworkingContractTest {
    private static final Path PEER_SOURCES = Path.of("src/main/java/com/codefit/peer");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    private static final Pattern FORBIDDEN = Pattern.compile(String.join("|",
            "java\\.net\\.", "javax\\.net\\.", "java\\.nio\\.channels", "java\\.net\\.http",
            "\\bSocket\\b", "ServerSocket", "DatagramSocket", "MulticastSocket", "HttpClient", "URLConnection",
            "java\\.io\\.File\\b", "java\\.nio\\.file", "FileInputStream", "FileOutputStream",
            "java\\.sql", "DatabaseConfig", "KeyPairGenerator", "SecureRandom", "ProcessBuilder", "Runtime\\.getRuntime",
            "ObjectInputStream", "readObject", "Class\\.forName", "ScriptEngine"));

    private static List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @Test
    void peerProtocolSourcesUseNoNetworkingFileDatabaseOrKeyGenerationApis() throws IOException {
        List<Path> files = javaFiles(PEER_SOURCES);
        assertTrue(files.size() > 10, "expected the protocol sources to be scanned");
        for (Path file : files) {
            String source = Files.readString(file);
            var matcher = FORBIDDEN.matcher(source);
            assertFalse(matcher.find(), () -> file + " uses forbidden API: " + matcher.group());
        }
    }

    @Test
    void peerProtocolIsNotWiredIntoTheRunningApplication() throws IOException {
        for (Path file : javaFiles(MAIN_SOURCES)) {
            if (file.startsWith(PEER_SOURCES)) {
                continue;
            }
            assertFalse(Files.readString(file).contains("com.codefit.peer"), file + " references the peer protocol");
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

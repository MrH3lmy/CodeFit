package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.CodeFitApplication;
import com.codefit.config.DatabaseConfig;
import com.codefit.config.ProfileLock;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@ExtendWith(IsolatedDatabaseExtension.class)
class DesktopApplicationIntegrationTest {
    @TempDir Path temporary;

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void projectionAndReceiptLookupAreByteForByteReadOnlyAndDoNotReconcileRewards()
            throws Exception {
        String previous = DatabaseConfig.currentDatabaseUrl();
        try (var profile = ExperimentalProfile.open(temporary.resolve("profile"))) {
            assertEquals(previous, DatabaseConfig.currentDatabaseUrl());
            var app = new DesktopApplicationService(profile);
            byte[] before = Files.readAllBytes(profile.database());
            var state = app.readState(0);
            assertEquals(8, state.decks().size());
            assertNull(state.nextAfterId());
            assertNull(app.status(profile.id(), id()).receipt());
            try (var c = profile.connection(true);
                    var s = c.createStatement()) {
                assertEquals(
                        8,
                        assertThrows(
                                        SQLException.class,
                                        () -> s.executeUpdate("UPDATE user_progress SET xp=xp+1"))
                                .getErrorCode());
                assertEquals(
                        8,
                        assertThrows(
                                        SQLException.class,
                                        () ->
                                                s.executeUpdate(
                                                        "INSERT INTO"
                                                            + " daily_quests(quest_date,objective_type,target_count)"
                                                            + " VALUES('2099-01-01','REVIEW',1)"))
                                .getErrorCode());
            }
            assertArrayEquals(before, Files.readAllBytes(profile.database()));
            String output =
                    new String(ApplicationProtocol.result(id(), state), StandardCharsets.UTF_8);
            assertTrue(output.contains("\"progression\":{\"status\":\"unsupported\"}"));
            assertFalse(output.contains("accepted_answers"));
            assertFalse(output.contains("\"xp\":"));
        }
    }

    @Test
    void creationAndTerminalRejectionReplayAtomicallyWithExactPayloadAndProfileBinding()
            throws Exception {
        Path path = temporary.resolve("profile");
        String command = id(), profileId;
        long deckId;
        try (var profile = ExperimentalProfile.open(path)) {
            profileId = profile.id();
            var app = new DesktopApplicationService(profile);
            var receipt = app.create(profileId, command, "  My deck  ", "");
            assertEquals("created", receipt.status());
            assertEquals("My deck", receipt.deck().getName());
            assertEquals("Custom training deck.", receipt.deck().getDescription());
            deckId = receipt.deck().getId();
            assertEquals(deckId, app.create(profileId, command, "  My deck  ", "").deck().getId());
            assertEquals(
                    "COMMAND_ID_CONFLICT",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> app.create(profileId, command, "My deck", ""))
                            .getMessage());
            assertEquals(
                    "PROFILE_MISMATCH",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> app.create(id(), id(), "Other", ""))
                            .getMessage());
            String duplicate = id();
            assertEquals("rejected", app.create(profileId, duplicate, "My deck", "").status());
            assertEquals("rejected", app.status(profileId, duplicate).receipt().status());
            assertEquals(
                    "COMMAND_ID_CONFLICT",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> app.create(profileId, duplicate, "Different", ""))
                            .getMessage());
            var firstPage = app.readState(0);
            assertEquals(8, firstPage.decks().size());
            assertNotNull(firstPage.nextAfterId());
            assertEquals(
                    List.of(deckId),
                    app.readState(firstPage.nextAfterId()).decks().stream()
                            .map(d -> d.getId())
                            .toList());
        }
        try (var profile = ExperimentalProfile.open(path)) {
            assertEquals(profileId, profile.id());
            var app = new DesktopApplicationService(profile);
            assertEquals(deckId, app.status(profileId, command).receipt().deck().getId());
            assertEquals(deckId, app.create(profileId, command, "  My deck  ", "").deck().getId());
            assertEquals(9, count(profile, "decks"));
            assertEquals(2, count(profile, "desktop_commands"));
        }
    }

    @Test
    void ledgerFailureRollsBackTheDeckAndSameCommandCanSafelyRetry() throws Exception {
        try (var profile = ExperimentalProfile.open(temporary.resolve("profile"))) {
            var app = new DesktopApplicationService(profile);
            try (var c = profile.connection(false);
                    var s = c.createStatement()) {
                s.execute(
                        "CREATE TRIGGER fail_receipt BEFORE INSERT ON desktop_commands BEGIN SELECT"
                            + " RAISE(ABORT,'test failure'); END");
            }
            String command = id();
            assertThrows(
                    SQLException.class, () -> app.create(profile.id(), command, "Rollback", ""));
            assertEquals(8, count(profile, "decks"));
            assertNull(app.status(profile.id(), command).receipt());
            try (var c = profile.connection(false);
                    var s = c.createStatement()) {
                s.execute("DROP TRIGGER fail_receipt");
            }
            assertEquals("created", app.create(profile.id(), command, "Rollback", "").status());
            assertEquals(9, count(profile, "decks"));
        }
    }

    @Test
    void javaFxAndHeadlessShareCanonicalOwnershipBeforeAnyInitializerRuns() throws Exception {
        String previous = DatabaseConfig.currentDatabaseUrl();
        try (var profile = ExperimentalProfile.open(temporary.resolve("profile"))) {
            byte[] before = Files.readAllBytes(profile.database());
            assertEquals(
                    "PROFILE_BUSY",
                    assertThrows(IOException.class, () -> ProfileLock.acquire(profile.database()))
                            .getMessage());
            DatabaseConfig.useDatabaseFile(profile.database());
            try {
                assertThrows(IOException.class, () -> new CodeFitApplication().start(null));
            } finally {
                DatabaseConfig.useDatabaseUrl(previous);
            }
            assertArrayEquals(before, Files.readAllBytes(profile.database()));
        }
    }

    @Test
    void unknownProfilesSchemaVersionsAndFileAliasesFailClosed() throws Exception {
        Path foreign = temporary.resolve("foreign");
        Files.createDirectory(foreign);
        Path learner = foreign.resolve("codefit.db");
        Files.writeString(learner, "learner sentinel");
        assertThrows(IOException.class, () -> ExperimentalProfile.open(foreign));
        assertEquals("learner sentinel", Files.readString(learner));
        Path path = temporary.resolve("profile");
        try (var profile = ExperimentalProfile.open(path);
                var c = profile.connection(false);
                var s = c.createStatement()) {
            s.execute("INSERT INTO schema_migrations(version,description) VALUES(26,'future')");
        }
        byte[] before = Files.readAllBytes(path.resolve("codefit.db"));
        assertThrows(IOException.class, () -> ExperimentalProfile.open(path));
        assertArrayEquals(before, Files.readAllBytes(path.resolve("codefit.db")));
        if (path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
            Path alias = temporary.resolve("alias");
            Files.createSymbolicLink(alias, path);
            assertThrows(IOException.class, () -> ExperimentalProfile.open(alias));
            Path hard = temporary.resolve("hard.db");
            Files.createLink(hard, path.resolve("codefit.db"));
            assertThrows(IOException.class, () -> ProfileLock.acquire(hard));
        }
    }

    @Test
    void interruptedUnpublishedBootstrapCanResumeWithoutReplacingPublishedData() throws Exception {
        Path path = temporary.resolve("profile");
        Files.createDirectory(path);
        Files.writeString(
                path.resolve("experimental-profile.txt"),
                "CodeFit experimental library / version 1\n");
        Files.writeString(path.resolve("bootstrap.db"), "interrupted unaccepted bootstrap");
        try (var profile = ExperimentalProfile.open(path)) {
            assertEquals(8, count(profile, "decks"));
            assertFalse(Files.exists(path.resolve("bootstrap.db")));
        }
    }

    @Test
    void oversizedExistingMetadataFailsBoundedlyWithoutTruncationClaims() throws Exception {
        try (var profile = ExperimentalProfile.open(temporary.resolve("profile"))) {
            try (var c = profile.connection(false);
                    var s = c.createStatement()) {
                s.execute("UPDATE decks SET description=hex(zeroblob(1000000)) WHERE id=1");
            }
            var state = new DesktopApplicationService(profile).readState(0);
            assertNull(state.decks().getFirst().getDescription());
            assertThrows(IOException.class, () -> ApplicationProtocol.result(id(), state));
        }
    }

    @Test
    void realProcessLostReplyRestartReplayAndSecondOwner() throws Exception {
        Path path = temporary.resolve("profile");
        Process first = launch(path);
        String profileId;
        String command = id();
        try {
            String ready = first.inputReader().readLine();
            profileId = ready.substring(ready.indexOf("profileId\":\"") + 12, ready.length() - 2);
            assertTrue(ApplicationProtocol.uuid(profileId), ready);
            Process second = launch(path);
            try {
                assertTrue(second.inputReader().readLine().contains("PROFILE_BUSY"));
                assertTrue(second.waitFor(10, TimeUnit.SECONDS));
            } finally {
                second.destroyForcibly();
            }
            String request = createRequest(profileId, command);
            first.getOutputStream().write((request + "\n").getBytes(StandardCharsets.UTF_8));
            first.getOutputStream().flush();
            // Deliberately discard the real committed response before the client can acknowledge
            // it.
            assertTrue(first.inputReader().readLine().contains("\"status\":\"created\""));
        } finally {
            first.destroyForcibly();
            assertTrue(first.waitFor(10, TimeUnit.SECONDS));
        }
        Process restarted = launch(path);
        try {
            assertTrue(restarted.inputReader().readLine().contains(profileId));
            restarted
                    .getOutputStream()
                    .write(
                            (createRequest(profileId, command) + "\n")
                                    .getBytes(StandardCharsets.UTF_8));
            restarted.getOutputStream().flush();
            assertTrue(restarted.inputReader().readLine().contains("\"status\":\"created\""));
            restarted.getOutputStream().close();
            assertTrue(restarted.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, restarted.exitValue());
        } finally {
            restarted.destroyForcibly();
        }
        try (var profile = ExperimentalProfile.open(path)) {
            assertEquals(9, count(profile, "decks"));
            assertEquals(1, count(profile, "desktop_commands"));
        }
    }

    @Test
    void killedTransactionRollsBackDeckAndReceiptDuringOwnedStartupRecovery() throws Exception {
        Path path = temporary.resolve("profile");
        try (var ignored = ExperimentalProfile.open(path)) {}
        String command = id();
        Process writer =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                UncommittedWriter.class.getName(),
                                path.toString(),
                                command)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            assertEquals("staged", writer.inputReader().readLine());
        } finally {
            writer.destroyForcibly();
            assertTrue(writer.waitFor(10, TimeUnit.SECONDS));
        }
        try (var profile = ExperimentalProfile.open(path)) {
            var app = new DesktopApplicationService(profile);
            assertEquals(8, count(profile, "decks"));
            assertNull(app.status(profile.id(), command).receipt());
            assertEquals(
                    "created", app.create(profile.id(), command, "Crash rollback", "").status());
            assertEquals(9, count(profile, "decks"));
        }
    }

    public static final class UncommittedWriter {
        public static void main(String[] args) throws Exception {
            try (var profile = ExperimentalProfile.open(Path.of(args[0]));
                    var c = profile.connection(false)) {
                c.setAutoCommit(false);
                var deck =
                        new com.codefit.service.DeckService().createDeck(c, "Crash rollback", "");
                try (var s =
                        c.prepareStatement(
                                "INSERT INTO desktop_commands VALUES(?, 'Crash rollback', '',"
                                    + " 'created', ?, 'Crash rollback', 'Custom training"
                                    + " deck.')")) {
                    s.setString(1, args[1]);
                    s.setLong(2, deck.getId());
                    s.executeUpdate();
                }
                System.out.println("staged");
                System.out.flush();
                System.in.read();
                c.rollback();
            }
        }
    }

    private static String createRequest(String profileId, String commandId) {
        return "{\"v\":2,\"id\":\""
                + id()
                + "\",\"method\":\"decks.create\",\"profileId\":\""
                + profileId
                + "\",\"commandId\":\""
                + commandId
                + "\",\"name\":\"Lost reply\",\"description\":\"\"}";
    }

    private int count(ExperimentalProfile profile, String table) throws SQLException {
        try (var c = profile.connection(true);
                var s = c.createStatement();
                var r = s.executeQuery("SELECT count(*) FROM " + table)) {
            r.next();
            return r.getInt(1);
        }
    }

    private Process launch(Path profile) throws IOException {
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty(
                                "surefire.test.class.path", System.getProperty("java.class.path")),
                        DesktopApplicationMain.class.getName(),
                        "--parent",
                        Long.toString(ProcessHandle.current().pid()),
                        "--experimental-profile",
                        profile.toString())
                .directory(temporary.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}

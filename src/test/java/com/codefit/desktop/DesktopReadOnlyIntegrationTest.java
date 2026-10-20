package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.service.DeckService;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@ExtendWith(IsolatedDatabaseExtension.class)
class DesktopReadOnlyIntegrationTest {
    @TempDir Path temporary;

    @Test
    void existingServiceReadsActualSeedWhileSqliteRejectsWrites() throws Exception {
        String beforeUrl = DatabaseConfig.currentDatabaseUrl();
        Path directory;
        try (IsolatedDesktopDatabase database = new IsolatedDesktopDatabase()) {
            directory = database.directory();
            database.initialize();
            Path file = directory.resolve("sample.db");
            byte[] before = Files.readAllBytes(file);
            var decks = database.read(() -> new DeckService().getDecks());
            assertEquals(8, decks.size());
            assertTrue(decks.stream().anyMatch(deck -> deck.getName().contains("Core Java & OOP")));
            try (var connection = DatabaseConfig.getConnection();
                    var statement = connection.createStatement()) {
                SQLException error =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        statement.executeUpdate(
                                                "INSERT INTO decks(name,description) VALUES('must"
                                                        + " not write','read only')"));
                assertEquals(8, error.getErrorCode());
            }
            assertArrayEquals(before, Files.readAllBytes(file));
            try (var files = Files.list(directory)) {
                assertEquals(1, files.count());
            }
        }
        assertFalse(Files.exists(directory));
        assertEquals(beforeUrl, DatabaseConfig.currentDatabaseUrl());
    }

    @Test
    void shutdownCannotRestoreDefaultUrlDuringAnActiveRead() throws Exception {
        String before = DatabaseConfig.currentDatabaseUrl();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var closeFinished = new CountDownLatch(1);
        try (var database = new IsolatedDesktopDatabase();
                var workers = Executors.newFixedThreadPool(2)) {
            database.initialize();
            String ownedUrl = DatabaseConfig.currentDatabaseUrl();
            var read =
                    workers.submit(
                            () ->
                                    database.read(
                                            () -> {
                                                entered.countDown();
                                                try {
                                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                                } catch (InterruptedException failure) {
                                                    throw new AssertionError(failure);
                                                }
                                                assertEquals(
                                                        ownedUrl,
                                                        DatabaseConfig.currentDatabaseUrl());
                                                return new DeckService().getDecks();
                                            }));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var close =
                        workers.submit(
                                () -> {
                                    closeStarted.countDown();
                                    database.close();
                                    closeFinished.countDown();
                                });
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS));
                assertEquals(ownedUrl, DatabaseConfig.currentDatabaseUrl());
                release.countDown();
                assertEquals(8, read.get(5, TimeUnit.SECONDS).size());
                close.get(5, TimeUnit.SECONDS);
                assertEquals(before, DatabaseConfig.currentDatabaseUrl());
                assertThrows(
                        IllegalStateException.class, () -> database.read(() -> fail("late read")));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closedSessionCannotInitializeOrReadAndCleanupIsIdempotent() throws Exception {
        String before = DatabaseConfig.currentDatabaseUrl();
        var database = new IsolatedDesktopDatabase();
        assertThrows(IllegalStateException.class, () -> database.read(() -> fail("early read")));
        database.close();
        assertThrows(IllegalStateException.class, database::initialize);
        assertThrows(IllegalStateException.class, () -> database.read(() -> fail("late read")));
        database.close();
        assertEquals(before, DatabaseConfig.currentDatabaseUrl());
        assertFalse(Files.exists(database.directory()));
    }

    @Test
    void realSubprocessReturnsServiceDataThenEofCleansItsDatabase() throws Exception {
        byte[] sentinel = "do not touch learner data".getBytes(StandardCharsets.UTF_8);
        Path profile = temporary.resolve("codefit.db");
        Files.write(profile, sentinel);
        Process child = launch("--parent", Long.toString(ProcessHandle.current().pid()));
        try (var reader = child.inputReader(StandardCharsets.UTF_8)) {
            assertEquals("{\"v\":1,\"type\":\"ready\"}", reader.readLine());
            child.outputWriter(StandardCharsets.UTF_8)
                    .append(DesktopProtocolTest.REQUEST)
                    .append('\n')
                    .flush();
            String result = reader.readLine();
            assertTrue(result.contains("Java BE 01 - Core Java & OOP"));
            assertTrue(result.contains("\"id\":\"" + DesktopProtocolTest.ID + "\""));
            assertFalse(result.contains("accepted_answers"));
            child.getOutputStream().close();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            assertArrayEquals(sentinel, Files.readAllBytes(profile));
            try (var files = Files.list(temporary)) {
                assertEquals(1, files.count());
            }
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void realProcessFailsClosedOnInvalidFrameAndWrongParent() throws Exception {
        Process wrong = launch("--parent", "0");
        assertTrue(wrong.waitFor(10, TimeUnit.SECONDS));
        assertEquals(2, wrong.exitValue());
        Process child = launch("--parent", Long.toString(ProcessHandle.current().pid()));
        try (var reader = child.inputReader(StandardCharsets.UTF_8)) {
            assertNotNull(reader.readLine());
            child.outputWriter(StandardCharsets.UTF_8)
                    .append("{\"v\":1,\"sql\":\"DROP TABLE decks\"}\n")
                    .flush();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(1, child.exitValue());
            assertNull(reader.readLine());
        } finally {
            child.destroyForcibly();
        }
    }

    private Process launch(String... arguments) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Djava.io.tmpdir=" + temporary);
        command.add("-cp");
        command.add(
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path")));
        command.add(DesktopReadOnlyMain.class.getName());
        command.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(command)
                .directory(temporary.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}

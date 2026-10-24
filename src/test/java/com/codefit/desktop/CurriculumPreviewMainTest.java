package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.knowledge.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class CurriculumPreviewMainTest {
    @TempDir Path directory;
    private final String id = UUID.randomUUID().toString();

    private String request() {
        return "{\"v\":1,\"id\":\"" + id + "\",\"method\":\"curriculum.preview.read\"}";
    }

    private String parse(String text) throws IOException {
        return CurriculumPreviewMain.request(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void acceptsOnlyTheExactFiniteRequest() throws Exception {
        assertEquals(id, parse(request() + "\n"));
        for (String invalid :
                List.of(
                        request(),
                        "",
                        "x".repeat(257) + "\n",
                        request().replace("\"v\":1", "\"v\":2"),
                        request().replace("\"v\":1", "\"v\":1,\"v\":1"),
                        request().replace("\"v\":1", "\"v\":1.0"),
                        request().replace("\"v\":1", "\"v\":\"1\""),
                        request().replace("\"v\":1", "\"v\":1,\"path\":\"/private\""),
                        request().replace(id, "bad"),
                        request().replace("curriculum.preview.read", "learner.skills.read"),
                        request() + "{}",
                        "\ufeff" + request())) {
            assertThrows(
                    IOException.class,
                    () ->
                            parse(
                                    invalid.endsWith("\n") || invalid.equals(request())
                                            ? invalid
                                            : invalid + "\n"));
        }
        assertThrows(
                IOException.class,
                () ->
                        CurriculumPreviewMain.request(
                                new ByteArrayInputStream(new byte[] {(byte) 255, 10})));
    }

    @Test
    void responseIsExactAllowlistedExportAndCannotInitializeAProfile() throws Exception {
        Path absent = directory.resolve("absent.db");
        DatabaseConfig.useDatabaseFile(absent);
        byte[] response = CurriculumPreviewMain.response(id);
        String expected =
                "{\"v\":1,\"id\":\""
                        + id
                        + "\",\"ok\":true,\"result\":{\"v\":1,\"kind\":\"author-draft-curriculum-preview\",\"source\":\"bundled-author-metadata\",\"learnerBound\":false,\"preview\":"
                        + new String(
                                KnowledgePreviewCli.export(KnowledgePreview.loadBundled()),
                                StandardCharsets.UTF_8)
                        + "}}";
        assertEquals(expected, new String(response, StandardCharsets.UTF_8));
        assertTrue(response.length < CurriculumPreviewMain.MAX_RESPONSE);
        assertFalse(expected.contains("private_checkpoint"));
        assertFalse(Files.exists(absent));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void responseDoesNotChangeSeededLearnerOrAscensionState() throws Exception {
        Path file = directory.resolve("learner.db");
        LearnerFixture.create(file);
        DatabaseConfig.useDatabaseFile(file);
        byte[] before = Files.readAllBytes(file);
        try (var connection = LearnerSnapshot.openReadOnly(file, false)) {
            connection.setAutoCommit(false);
            var time = java.time.Instant.parse("2026-10-24T00:00:00Z");
            var original = LearnerSkillProjection.read(connection, "a".repeat(64), id, time);
            assertArrayEquals(
                    CurriculumPreviewMain.response(id), CurriculumPreviewMain.response(id));
            assertEquals(
                    original, LearnerSkillProjection.read(connection, "a".repeat(64), id, time));
            assertEquals(4, original.nodes().size());
            assertTrue(
                    original.nodes().stream()
                            .noneMatch(n -> n.ref().id().startsWith("java.general.")));
        }
        assertArrayEquals(before, Files.readAllBytes(file));
    }
}

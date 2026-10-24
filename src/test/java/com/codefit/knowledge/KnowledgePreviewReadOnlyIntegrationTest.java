package com.codefit.knowledge;

import com.codefit.config.DatabaseConfig;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class KnowledgePreviewReadOnlyIntegrationTest {
    @TempDir Path directory;

    @Test
    void validAndRejectedCatalogsLeaveSeededLearnerDatabaseByteIdentical() throws Exception {
        Path database = directory.resolve("learner.db");
        DatabaseConfig.useDatabaseFile(database);
        DatabaseConfig.initialize();
        byte[] before = Files.readAllBytes(database);
        String url = DatabaseConfig.currentDatabaseUrl();

        PublicPreview first = KnowledgePreview.loadBundled();
        byte[] exported = KnowledgePreviewCli.export(first);
        assertEquals(first, KnowledgePreview.loadBundled());
        assertArrayEquals(exported, KnowledgePreviewCli.export(KnowledgePreview.loadBundled()));
        byte[] invalid;
        try (var input = getClass().getResourceAsStream(
                "/knowledge/hunter-pilot-v0.41/preview-catalog.json")) {
            invalid = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\"xp_enabled\": false", "\"xp_enabled\": true")
                    .getBytes(StandardCharsets.UTF_8);
        }
        assertThrows(PreviewFailure.class, () -> KnowledgePreview.verify(invalid));
        assertEquals(first, KnowledgePreview.loadBundled(), "Rejection cannot replace the pinned snapshot");

        assertEquals(url, DatabaseConfig.currentDatabaseUrl());
        assertArrayEquals(before, Files.readAllBytes(database),
                "Catalog, evidence, grading, XP, rewards and mastery tables must all remain unchanged");
        try (var files = Files.list(directory)) {
            assertEquals(List.of(database), files.toList(), "No second database or journal is created");
        }
    }

    @Test
    void previewDoesNotInitializeAnAbsentLearnerDatabase() throws Exception {
        Path absent = directory.resolve("absent.db");
        DatabaseConfig.useDatabaseFile(absent);
        String url = DatabaseConfig.currentDatabaseUrl();

        assertEquals(4, KnowledgePreview.loadBundled().skills().size());
        assertDoesNotThrow(() -> KnowledgePreviewCli.export(KnowledgePreview.loadBundled()));
        assertEquals(url, DatabaseConfig.currentDatabaseUrl());
        assertFalse(Files.exists(absent));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }
}

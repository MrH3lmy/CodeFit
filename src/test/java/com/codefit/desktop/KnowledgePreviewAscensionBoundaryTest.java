package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.knowledge.KnowledgePreview;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class KnowledgePreviewAscensionBoundaryTest {
    @TempDir Path directory;

    @Test
    void authorDraftCannotEnterTheAuthoritativeAscensionProjection() throws Exception {
        Path database = directory.resolve("learner.db");
        LearnerFixture.create(database);
        byte[] before = Files.readAllBytes(database);
        String session = UUID.randomUUID().toString();
        Instant captured = Instant.parse("2026-10-01T12:00:00Z");
        try (var connection = LearnerSnapshot.openReadOnly(database, false)) {
            connection.setAutoCommit(false);
            var original =
                    LearnerSkillProjection.read(connection, "a".repeat(64), session, captured);
            var preview = KnowledgePreview.loadBundled();
            var repeated =
                    LearnerSkillProjection.read(connection, "a".repeat(64), session, captured);
            assertEquals("available", original.status());
            assertEquals(4, original.nodes().size());
            assertEquals(3, original.edges().size());
            assertEquals(original, repeated);
            var draftIds = preview.skills().stream().map(s -> s.id()).collect(Collectors.toSet());
            assertTrue(original.nodes().stream().noneMatch(n -> draftIds.contains(n.ref().id())));
            assertTrue(
                    original.nodes().stream()
                            .allMatch(n -> n.estimate().status().equals("missing")));
            assertEquals("UNREVIEWED", preview.reviewStatus());
            assertTrue(preview.mappings().isEmpty());
            connection.rollback();
        }
        assertArrayEquals(before, Files.readAllBytes(database));
    }
}

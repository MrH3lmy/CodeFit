package com.codefit.knowledge;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeCoverageTest {
    static byte[] fixture() throws IOException {
        try (InputStream input = KnowledgeCoverageTest.class.getResourceAsStream(KnowledgeCoverage.RESOURCE)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    static List<byte[]> invalidFixtures() throws IOException {
        String valid = new String(fixture(), StandardCharsets.UTF_8);
        List<byte[]> fixtures = new ArrayList<>();
        for (String invalid : List.of(
                "", "null", "[]", "{", valid + "{}", "\ufeff" + valid,
                valid.replace("\"ledgerVersion\": \"1.0.0\"", "\"ledgerVersion\": \"0.9.0\""),
                valid.replace("\"schema\":", "\"unknown\":true,\"schema\":"),
                valid.replace("\"ledgerVersion\": \"1.0.0\",", ""),
                valid.replace("\"schema\": \"codefit-knowledge-coverage-ledger/1\"",
                        "\"schema\": \"codefit-knowledge-coverage-ledger/2\""),
                valid.replace("\"books\":", "\"books\":[],\"books\":"),
                valid.replace("\"provenance\": {", "\"provenance\": {\"privateText\":\"body\","),
                valid.replace("\"observedAt\": \"2026-10-23\",", ""),
                valid.replace("\"id\": \"ocp-java-se-17-guide\",", ""),
                valid.replace("\"structuralUnits\": 26,", "\"structuralUnits\": 26,\"extra\":0,"),
                valid.replace("\"reportedClaims\": {", "\"reportedClaims\": {\"extra\":0,"),
                valid.replace("\"preliminaryMappedUnits\": 157,", ""),
                valid.replace("\"effectiveJavaFirstPassAuthorNotes\": {",
                        "\"effectiveJavaFirstPassAuthorNotes\": {\"extra\":0,"),
                valid.replace("\"denominator\": 90", "\"denominator\": null"),
                valid.replace("\"revisionEvents\": {", "\"revisionEvents\": {\"extra\":0,"),
                valid.replace("\"records\": null", "\"missing\": null"),
                valid.replace("\"unitIds\": null", "\"unitIds\": [\"private-text\"]"),
                valid.replace("\"firstPassAuthoredUnits\": null", "\"firstPassAuthoredUnits\": 0"),
                valid.replace("\"structuralUnits\": 26", "\"structuralUnits\": 27"),
                valid.replace("\"structuralUnits\": 26", "\"structuralUnits\": 26.0"),
                valid.replace("\"structuralUnits\": 26", "\"structuralUnits\": \"26\""),
                valid.replace("\"preliminaryMappedUnits\": 157", "\"preliminaryMappedUnits\": 156"),
                valid.replace("\"reportedUnmappedUnits\": 347", "\"reportedUnmappedUnits\": 348"),
                valid.replace("\"count\": 424", "\"count\": 419"),
                valid.replace("\"additiveToMissionBlueprintSlots\": false",
                        "\"additiveToMissionBlueprintSlots\": true"),
                valid.replace("\"records\": null", "\"records\": []"),
                valid.replace("\"independentApprovals\": 0", "\"independentApprovals\": 1"),
                valid.replace("\"productionGraders\": 0", "\"productionGraders\": 1"),
                valid.replace("\"hunterProductionImports\": 0", "\"hunterProductionImports\": 1"),
                valid.replace("\"sourceVersion\": \"v0.41\"", "\"sourceVersion\": \"v0.42\""),
                valid.replace("\"previousCheckpoint\": \"NOT_AVAILABLE\"",
                        "\"previousCheckpoint\": \"VERIFIED\""),
                valid.replace("not an independently enumerated inventory",
                        "independently enumerated inventory"),
                valid.replace("\"title\": \"Effective Java\"", "\"title\": \"private body\""),
                valid + " ",
                "{\"a\":".repeat(8) + "0" + "}".repeat(8),
                "{\"a\":\"" + "a".repeat(513) + "\"}",
                "[" + "null,".repeat(257) + "null]")) {
            fixtures.add(invalid.getBytes(StandardCharsets.UTF_8));
        }
        fixtures.add(new byte[]{(byte) 255});
        fixtures.add(new byte[KnowledgeCoverage.MAX_BYTES + 1]);
        return fixtures;
    }

    @Test
    void exactCheckedInFixtureIsIncludedAtOnlyTheFixedResourcePathAndPin() throws Exception {
        assertEquals("/knowledge/hunter-pilot-v0.41/coverage-ledger-input.json", KnowledgeCoverage.RESOURCE);
        assertEquals("0466d16b2ec5809383b7d0bb40d068430bcd3890e06115f5be2cfffe46a4057e",
                KnowledgeCoverage.SHA256);
        assertArrayEquals(Files.readAllBytes(Path.of(KnowledgeCoverage.SOURCE_PATH)), fixture());
        assertEquals(KnowledgeCoverage.SHA256, KnowledgePreview.sha256(fixture()));
        assertNull(getClass().getResourceAsStream("/coverage-ledger-input.json"));
        assertNull(getClass().getResourceAsStream("/knowledge/hunter-pilot-v0.42/coverage-ledger-input.json"));
        assertEquals(KnowledgeCoverage.verify(fixture()), KnowledgeCoverage.loadBundled());
    }

    @Test
    void attributionUnknownsAndNonadditiveHistoryRemainDistinctFromEvidence() throws Exception {
        PublicCoverage coverage = KnowledgeCoverage.loadBundled();
        assertTrue(coverage.localReviewOnly());
        assertFalse(coverage.productionImportSupported());
        assertEquals("SOURCE_ATTRIBUTED_NOT_INDEPENDENTLY_VERIFIED", coverage.provenance().verification());
        assertEquals("NOT_AVAILABLE", coverage.provenance().previousCheckpoint());
        assertEquals(List.of(26, 90, 100, 269, 19),
                coverage.books().stream().map(b -> b.inventory().count()).toList());
        assertEquals(504, coverage.books().stream().mapToInt(b -> b.inventory().count()).sum());
        for (PublicCoverage.Book b : coverage.books()) {
            assertEquals("REPORTED_NOT_ENUMERATED", b.inventory().status());
            assertEquals("UNKNOWN", b.preliminaryMapped().status());
            assertNull(b.preliminaryMapped().count());
            assertEquals(b.inventory().count(), b.preliminaryMapped().denominator());
            for (PublicCoverage.Count count : List.of(b.independentReview(), b.runtimeLearning())) {
                assertEquals("ZERO_REPORTED", count.status());
                assertEquals(0, count.count());
                assertEquals(b.inventory().count(), count.denominator());
            }
            if (!b.id().equals("effective-java")) {
                assertNull(b.authorDraft().count());
                assertEquals("UNKNOWN", b.authorDraft().status());
            }
        }
        assertEquals(new PublicCoverage.Count("REPORTED_FIRST_PASS_NOT_REVIEWED", 90, 90),
                coverage.books().get(1).authorDraft());
        PublicCoverage.ReportedClaims claims = coverage.reportedClaims();
        assertEquals(504, claims.structuralUnits());
        assertEquals(157, claims.preliminaryMappedUnits());
        assertEquals(347, claims.reportedUnmappedUnits());
        assertEquals(new PublicCoverage.FirstPassNotes(90, 90), claims.effectiveJavaFirstPassAuthorNotes());
        assertEquals(419, claims.missionBlueprintSlots());
        assertEquals(360, claims.deepOverlaySlots());
        assertEquals(new PublicCoverage.RevisionEvents(424, false, null), claims.revisionEvents());
        assertEquals(0, claims.independentApprovals());
        assertEquals(0, claims.productionGraders());
        assertEquals(0, claims.hunterProductionImports());
        assertEquals(new PublicCoverage.Count("UNKNOWN", null, null), coverage.learnerValidated());
        byte[] compact = KnowledgeCoverage.export(coverage);
        assertEquals("9609e1794c79681eb5b0558f2b0c8d965c732434bda841c2e3c9172902a2830d",
                KnowledgePreview.sha256(compact));
        assertArrayEquals(compact, KnowledgeCoverage.export(KnowledgeCoverage.loadBundled()));
        assertNotEquals(KnowledgeCoverage.SHA256, KnowledgePreview.SHA256);
        assertFalse(new String(compact, StandardCharsets.UTF_8).contains("private-text"));
        assertThrows(UnsupportedOperationException.class, () -> coverage.books().clear());
    }

    @Test
    void rejectsEveryMalformedMissingExtraStaleOrMismatchedMetadataSnapshot() throws Exception {
        for (byte[] invalid : invalidFixtures()) {
            PreviewFailure failure = assertThrows(PreviewFailure.class,
                    () -> KnowledgeCoverage.verify(invalid));
            assertEquals(failure.code().name(), failure.getMessage());
            assertNull(failure.getCause());
        }
        assertThrows(PreviewFailure.class, () -> KnowledgeCoverage.verify(null));
        assertThrows(PreviewFailure.class, () -> KnowledgeCoverage.load(null));
        assertThrows(PreviewFailure.class, () -> KnowledgeCoverage.load(new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private/path"); }
        }));
        assertThrows(PreviewFailure.class,
                () -> KnowledgeCoverage.load(new ByteArrayInputStream(new byte[KnowledgeCoverage.MAX_BYTES + 1])));
        assertEquals(KnowledgeCoverage.verify(fixture()), KnowledgeCoverage.loadBundled());
    }
}

package com.codefit.knowledge.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class OriginalOnlyCatalogValidatorTest {
    private static final String REVISION = "synthetic-r1";
    private static final String PIN = "bed9bec779f0e0766c95c17a0e4d437fa0787d58b1c2fcc4c116ba54ca246cfb";

    private static byte[] fixture() throws IOException {
        try (InputStream input = OriginalOnlyCatalogValidatorTest.class
                .getResourceAsStream("/knowledge/original-only-v1/catalog.json")) {
            return Objects.requireNonNull(input).readAllBytes();
        }
    }

    private static String source() throws IOException { return new String(fixture(), StandardCharsets.UTF_8); }

    private static OriginalOnlyCatalogValidator.Result validate(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return OriginalOnlyCatalogValidator.validate(bytes, REVISION, OriginalOnlyCatalogValidator.sha256(bytes));
    }

    private static void rejected(OriginalOnlyCatalogValidator.Result result, String category, String code) {
        assertFalse(result.valid());
        assertNull(result.preview(), "No partial DTO on failure");
        assertNull(result.manifest(), "No partial manifest on failure");
        assertEquals(new OriginalOnlyCatalogValidator.Failure(category, code), result.error());
    }

    @Test
    void exactSyntheticRevisionProducesDeterministicMetadataOnlyArtifacts() throws Exception {
        byte[] bytes = fixture();
        assertEquals(PIN, OriginalOnlyCatalogValidator.sha256(bytes));
        var result = OriginalOnlyCatalogValidator.validate(bytes, REVISION, PIN);
        assertTrue(result.valid(), () -> String.valueOf(result.error()));
        assertEquals(result, OriginalOnlyCatalogValidator.validate(bytes, REVISION, PIN));
        assertEquals("329a301191c8187b4b70dc562bd05752c6271f8267a25497a999f10baa25c6da",
                OriginalOnlyCatalogValidator.sha256(result.preview().getBytes(StandardCharsets.UTF_8)));
        assertTrue(result.manifest().contains(OriginalOnlyCatalogValidator.sha256(result.preview().getBytes(StandardCharsets.UTF_8))));
        for (String flag : List.of("releaseEligible", "productionImportSupported", "challengeExposure",
                "graderAvailable", "xpEnabled", "rewardsEnabled", "masteryEnabled"))
            assertTrue(result.preview().contains("\"" + flag + "\":false"));
        for (String state : List.of("ABSENT", "UNSUPPORTED", "UNREVIEWED", "REVIEW_PENDING", "UNMAPPED"))
            assertTrue(result.preview().contains("\"" + state + "\""));
        for (String forbidden : List.of("Synthetic author note", "Synthetic pending note", "\"text\"",
                "\"history\"", "ORIGINAL_ONLY", "APPROVED", "SkillRef", "GraphRef"))
            assertFalse(result.preview().contains(forbidden));
        assertTrue(result.preview().contains("\"independentApprovals\":0"));
    }

    @Test
    void preservesLegacyStageIdentityWithoutCreatingASkillOrGraphMapping() throws Exception {
        var result = validate(source().replace("synthetic-one-predict", "EJ41-057-PREDICT"));
        assertTrue(result.valid());
        assertTrue(result.preview().contains("\"id\":\"EJ41-057-PREDICT\""));
        assertTrue(result.preview().contains("\"mappingStatus\":\"UNMAPPED\""));
        assertFalse(result.preview().contains("skillRef"));
        assertFalse(result.preview().contains("graphRef"));
    }

    @Test
    void doesNotAcceptTheOldPreviewAsANewCatalog() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/knowledge/hunter-pilot-v0.41/preview-catalog.json")) {
            byte[] oldPreview = Objects.requireNonNull(input).readAllBytes();
            rejected(OriginalOnlyCatalogValidator.validate(oldPreview, REVISION,
                    OriginalOnlyCatalogValidator.sha256(oldPreview)), "FORMAT", "TYPE");
        }
    }

    static Stream<Arguments> mutations() {
        return Stream.of(
                Arguments.of("codefit-original-only/1", "codefit-original-only/2", "FORMAT", "UNSUPPORTED_VALUE"),
                Arguments.of("ORIGINAL_ONLY", "PUBLISHER_TEXT", "ORIGINALITY", "UNSUPPORTED_VALUE"),
                Arguments.of("METADATA_ONLY", "AUTHORIZED_CHALLENGE", "RELEASE", "UNSUPPORTED_VALUE"),
                Arguments.of("UNMAPPED", "MAPPED", "MAPPING", "UNSUPPORTED_VALUE"),
                Arguments.of("\"bookId\": \"synthetic-book-one\"", "\"bookId\": \"unknown\"", "LINKS", "MISSING_REFERENCE"),
                Arguments.of("\"unitId\": \"synthetic-unit-one\"", "\"unitId\": \"unknown\"", "LINKS", "MISSING_REFERENCE"),
                Arguments.of("\"unitId\": \"synthetic-unit-one\"", "\"unitId\": \"synthetic-unit-two\"", "LINKS", "BOOK_UNIT_MISMATCH"),
                Arguments.of("\"skillId\": \"synthetic-skill-one\"", "\"skillId\": \"unknown\"", "LINKS", "STAGE_LINK_MISMATCH"),
                Arguments.of("\"id\": \"synthetic-one-predict\", \"bookId\": \"synthetic-book-one\"",
                        "\"id\": \"synthetic-one-predict\", \"bookId\": \"synthetic-book-two\"", "LINKS", "STAGE_LINK_MISMATCH"),
                Arguments.of("\"unitId\": \"synthetic-unit-one\",\n          \"skillId\"",
                        "\"unitId\": \"synthetic-unit-two\",\n          \"skillId\"", "LINKS", "STAGE_LINK_MISMATCH"),
                Arguments.of("\"stages\": [", "\"stages\": [{},", "FORMAT", "COUNT"),
                Arguments.of("\"books\": [", "\"books\": [{\"id\":\"extra-book\"},", "FORMAT", "COUNT"),
                Arguments.of("\"skillIds\": [\"synthetic-skill-one\"]", "\"skillIds\": []", "LINKS", "UNIT_SKILL_MISMATCH"),
                Arguments.of("\"prerequisites\": []", "\"prerequisites\": [\"unknown\"]", "LINKS", "MISSING_REFERENCE"),
                Arguments.of("\"prerequisites\": []", "\"prerequisites\": [\"synthetic-skill-two\"]", "LINKS", "CYCLE"),
                Arguments.of("\"prerequisites\": []", "\"prerequisites\": [\"synthetic-skill-one\"]", "LINKS", "CYCLE"),
                Arguments.of("\"prerequisites\": []", "\"prerequisites\": [\"synthetic-skill-two\", \"synthetic-skill-two\"]", "LINKS", "DUPLICATE_ID"),
                Arguments.of("\"id\": \"synthetic-book-two\"", "\"id\": \"synthetic-book-one\"", "LINKS", "DUPLICATE_ID"),
                Arguments.of("\"id\": \"synthetic-unit-two\"", "\"id\": \"synthetic-unit-one\"", "LINKS", "DUPLICATE_ID"),
                Arguments.of("\"id\": \"synthetic-skill-two\"", "\"id\": \"synthetic-skill-one\"", "LINKS", "DUPLICATE_ID"),
                Arguments.of("\"id\": \"synthetic-two-defend\"", "\"id\": \"synthetic-one-defend\"", "LINKS", "DUPLICATE_ID"),
                Arguments.of("\"id\": \"synthetic-one-predict\"", "\"id\": \"synthetic-book-one\"", "LINKS", "DUPLICATE_ID"),
                Arguments.of("\"stage\": \"DEBUG\"", "\"stage\": \"PREDICT\"", "STAGES", "STAGE_SET"),
                Arguments.of("\"stage\": \"DEBUG\"", "\"stage\": \"SOLVE\"", "STAGES", "STAGE_SET"),
                Arguments.of("\"status\": \"UNREVIEWED\"", "\"status\": \"APPROVED\"", "STAGES", "UNKNOWN_STATUS"),
                Arguments.of("\"coverage\": \"AUTHOR_REPORTED\"", "\"coverage\": \"VERIFIED\"", "COVERAGE", "UNKNOWN_STATUS"),
                Arguments.of("\"status\": \"ORIGINAL_AUTHOR_DRAFT\"", "\"status\": \"APPROVED\"", "STAGES", "INCONSISTENT_STATUS"),
                Arguments.of("\"technical\": {\"status\": \"UNREVIEWED\"", "\"technical\": {\"status\": \"APPROVED\"", "REVIEW", "UNSUPPORTED_DECISION"),
                Arguments.of("\"pedagogical\": {\"status\": \"UNREVIEWED\"", "\"pedagogical\": {\"status\": \"APPROVED\"", "REVIEW", "UNSUPPORTED_DECISION"),
                Arguments.of("\"history\": [\"UNREVIEWED\"]", "\"history\": [\"REVIEW_PENDING\", \"UNREVIEWED\"]", "REVIEW", "INVALID_TRANSITION"),
                Arguments.of("\"history\": [\"UNREVIEWED\", \"REVIEW_PENDING\"]", "\"history\": [\"REVIEW_PENDING\"]", "REVIEW", "INVALID_TRANSITION"),
                Arguments.of("\"independentReview\": {\"revision\": \"synthetic-r1\"", "\"independentReview\": {\"revision\": \"synthetic-r0\"", "REVISION", "STALE_REVIEW"),
                Arguments.of("\"revision\": \"synthetic-r1\"", "\"revision\": \"synthetic-r0\"", "REVISION", "STALE_REVISION"),
                Arguments.of("\"sourceKind\":", "\"sourceKind\": \"ORIGINAL_ONLY\", \"sourceKind\":", "FORMAT", "DUPLICATE_FIELD"),
                Arguments.of("\"sourceKind\": \"ORIGINAL_ONLY\",", "", "FORMAT", "FIELDS"),
                Arguments.of("\"prerequisites\": []", "\"prerequisites\": null", "FORMAT", "TYPE"),
                Arguments.of("\"mappingStatus\": \"UNMAPPED\"", "\"mappingStatus\": true", "FORMAT", "TYPE")
        );
    }

    @ParameterizedTest
    @MethodSource("mutations")
    void rejectsAdversarialMutationEvenWithMatchingHash(String before, String after, String category, String code) throws Exception {
        String input = source();
        assertTrue(input.contains(before));
        String changed = input.replaceFirst(Pattern.quote(before), Matcher.quoteReplacement(after));
        rejected(validate(changed), category, code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prompt", "answer", "rubric", "hints", "solution", "graderKey", "reviewerCredential",
            "releaseEligible", "independentApprovals", "skillRef", "graphRef", "privateUrl", "sourceBody"})
    void rejectsForbiddenFieldsAtEveryObjectBoundary(String field) throws Exception {
        for (String marker : List.of("\"schema\":", "\"id\":", "\"stage\":", "\"brief\": {",
                "\"independentReview\": {", "\"technical\": {", "\"pedagogical\": {")) {
            String changed = marker.endsWith("{")
                    ? source().replace(marker, marker + "\"" + field + "\": \"withheld\",")
                    : source().replace(marker, "\"" + field + "\": \"withheld\"," + marker);
            rejected(validate(changed), "FORMAT", "FIELDS");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../private", "/tmp/input", "https://invalid.example", "file:private",
            "C:\\\\private", "private%2fdata", "data@example", "bad..id", "bad_id"})
    void rejectsPathAndUrlIdentifiersAndBriefs(String unsafe) throws Exception {
        rejected(validate(source().replace("synthetic-one-predict", unsafe)), "FORMAT", "IDENTIFIER");
        if (!unsafe.matches("[A-Za-z0-9][A-Za-z0-9 ,;()'!?-]{0,255}"))
            rejected(validate(source().replace("Synthetic author note for validator testing only", unsafe)),
                    "STAGES", "INCONSISTENT_STATUS");
    }

    @Test
    void rejectsStaleBytesWithoutApprovalCarryOver() throws Exception {
        rejected(OriginalOnlyCatalogValidator.validate((source() + " ").getBytes(StandardCharsets.UTF_8), REVISION, PIN),
                "REVISION", "PIN_MISMATCH");
        rejected(OriginalOnlyCatalogValidator.validate(source().replace("Synthetic author note", "Changed author note")
                .getBytes(StandardCharsets.UTF_8), REVISION, PIN), "REVISION", "PIN_MISMATCH");
        rejected(OriginalOnlyCatalogValidator.validate(fixture(), REVISION, ""), "REVISION", "EXPECTED_PIN_REQUIRED");
    }

    @Test
    void rejectsMalformedAndBoundedInputs() throws Exception {
        rejected(OriginalOnlyCatalogValidator.validate(new byte[OriginalOnlyCatalogValidator.MAX_BYTES + 1], REVISION, PIN),
                "FORMAT", "TOO_LARGE");
        rejected(validate("[".repeat(15) + "\"x\"" + "]".repeat(15)), "FORMAT", "INVALID_JSON");
        rejected(validate("{\"schema\":\"" + "x".repeat(1000) + "\"}"), "FORMAT", "INVALID_JSON");
        rejected(validate(source() + "{}"), "FORMAT", "INVALID_JSON");
        rejected(validate(source().substring(0, source().length() - 3)), "FORMAT", "INVALID_JSON");
        rejected(OriginalOnlyCatalogValidator.validate(new byte[]{(byte) 0xff}, REVISION, PIN), "FORMAT", "INVALID_JSON");
        for (String scalar : List.of("null", "true", "1", "1.0", "\"x\""))
            rejected(validate(scalar), "FORMAT", "TYPE");
        rejected(validate("[" + "\"x\",".repeat(16_384) + "\"x\"]"), "FORMAT", "LIMIT");
    }

    @Test
    void cliIsBoundedRedactedAndHasNoPartialSuccess() throws Exception {
        var output = new ByteArrayOutputStream();
        try (PrintStream print = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            assertEquals(0, OriginalOnlyCatalogValidator.run(new String[]{REVISION, PIN}, new ByteArrayInputStream(fixture()), print));
            assertTrue(output.toString(StandardCharsets.UTF_8).startsWith("{\"manifest\":"));
            output.reset();
            assertEquals(1, OriginalOnlyCatalogValidator.run(new String[]{REVISION, PIN},
                    new ByteArrayInputStream("{\"private\":\"withheld\"}".getBytes(StandardCharsets.UTF_8)), print));
            assertEquals("{\"category\":\"FORMAT\",\"code\":\"FIELDS\",\"schema\":\"codefit-original-only-error/1\",\"valid\":false}\n",
                    output.toString(StandardCharsets.UTF_8));
            output.reset();
            assertEquals(1, OriginalOnlyCatalogValidator.run(new String[]{}, InputStream.nullInputStream(), print));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("preview"));
            output.reset();
            InputStream failing = new InputStream() {
                @Override public int read() throws IOException { throw new IOException("withheld"); }
            };
            assertEquals(1, OriginalOnlyCatalogValidator.run(new String[]{REVISION, PIN}, failing, print));
            assertFalse(output.toString(StandardCharsets.UTF_8).contains("withheld"));
            output.reset();
            assertEquals(1, OriginalOnlyCatalogValidator.run(new String[]{REVISION, PIN},
                    new ByteArrayInputStream(new byte[OriginalOnlyCatalogValidator.MAX_BYTES + 1]), print));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"code\":\"TOO_LARGE\""));
        }
    }

    @Test
    void subprocessDoesNotInitializeOrModifyLearnerProfile(@TempDir Path temp) throws Exception {
        Path profile = temp.resolve("codefit.db");
        byte[] sentinel = "synthetic learner profile sentinel".getBytes(StandardCharsets.UTF_8);
        Files.write(profile, sentinel);
        runProcess(temp, fixture(), 0);
        runProcess(temp, "{}".getBytes(StandardCharsets.UTF_8), 1);
        assertArrayEquals(sentinel, Files.readAllBytes(profile));
        Files.delete(profile);
        runProcess(temp, fixture(), 0);
        runProcess(temp, "{}".getBytes(StandardCharsets.UTF_8), 1);
        assertFalse(Files.exists(profile));
        try (var files = Files.list(temp)) { assertEquals(0, files.count()); }
    }

    private static void runProcess(Path directory, byte[] input, int expectedExit) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String cp = System.getProperty("surefire.test.class.path");
        Process process = new ProcessBuilder(java, "-Duser.home=" + directory, "-cp", cp,
                OriginalOnlyCatalogValidator.class.getName(), REVISION, PIN).directory(directory.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input); }
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(expectedExit, process.exitValue());
            assertEquals("", new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(stdout.contains("Synthetic author note"));
        } finally { process.destroyForcibly(); }
    }
}

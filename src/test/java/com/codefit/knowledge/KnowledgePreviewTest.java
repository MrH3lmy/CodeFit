package com.codefit.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static com.codefit.knowledge.PreviewFailure.Code.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgePreviewTest {
    private static final Path ORIGINAL = Path.of("docs/knowledge-compiler/hunter-pilot-v0.41/preview-catalog.json");

    private static byte[] original() throws IOException { return Files.readAllBytes(ORIGINAL); }
    private static String text() throws IOException { return Files.readString(ORIGINAL); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void rejects(PreviewFailure.Code code, byte[] input) {
        PreviewFailure failure = assertThrows(PreviewFailure.class, () -> KnowledgePreview.verify(input));
        assertEquals(code, failure.code());
        assertEquals(code.name(), failure.getMessage());
        assertNull(failure.getCause());
        assertTrue(failure.getMessage().length() < 40);
    }
    record Mutation(String from, String to, PreviewFailure.Code code) { }
    static Stream<Mutation> mutations() {
        return Stream.of(
                new Mutation("v0.41", "v0.42", VERSION),
                new Mutation("codefit-curriculum-preview/0.1", "codefit-curriculum-preview/9", VERSION),
                new Mutation("\"source_version\": \"v0.41\"", "\"source_version\": 41", TYPE),
                new Mutation("\"xp_enabled\": false", "\"xp_enabled\": \"false\"", TYPE),
                new Mutation("\"skills\": [", "\"skills\": {", INVALID_JSON),
                new Mutation("\"independent_approvals\": 0", "\"independent_approvals\": false", TYPE),
                new Mutation("\"independent_approvals\": 0", "\"independent_approvals\": 0.0", TYPE),
                new Mutation("\"independent_approvals\": 0", "\"independent_approvals\": 1", FLAGS),
                new Mutation("\"xp_enabled\": false", "\"xp_enabled\": true", FLAGS),
                new Mutation("\"mastery_enabled\": false", "\"mastery_enabled\": true", FLAGS),
                new Mutation("\"grader_available\": false", "\"grader_available\": true", FLAGS),
                new Mutation("\"production_import_supported\": false", "\"production_import_supported\": true", FLAGS),
                new Mutation("\"source_body_included\": false", "\"source_body_included\": true", FLAGS),
                new Mutation("\"release_eligible\": false", "\"release_eligible\": true", FLAGS),
                new Mutation("\"gradable\": false", "\"gradable\": true", FLAGS),
                new Mutation("\"mastery_eligible\": false", "\"mastery_eligible\": true", FLAGS),
                new Mutation("AUTHOR_DRAFT_PENDING_EXTERNAL_REVIEW", "APPROVED", FLAGS),
                new Mutation("original_author_metadata_only", "publisher_source", FLAGS),
                new Mutation("\"UNRESOLVED\"", "\"RESOLVED\"", FLAGS),
                new Mutation("\"schema\":", "\"secret-input\":", FIELDS),
                new Mutation("\"schema\":", "\"overlays\": [], \"schema\":", FIELDS),
                new Mutation("\"schema\":", "\"java_runtime\": \"21_VERIFIED\", \"schema\":", FIELDS),
                new Mutation("\"schema\":", "\"difficulty\": \"APPROVED\", \"schema\":", FIELDS),
                new Mutation("\"schema\":", "\"solution\": \"secret-input\", \"schema\":", FIELDS),
                new Mutation("\"title\":", "\"prompt\": \"secret-input\", \"title\":", FIELDS),
                new Mutation("\"title\":", "\"hint\": \"secret-input\", \"title\":", FIELDS),
                new Mutation("\"title\":", "\"reference_solution\": \"secret-input\", \"title\":", FIELDS),
                new Mutation("\"title\":", "\"grader_key\": \"secret-input\", \"title\":", FIELDS),
                new Mutation("\"id\": \"EJ41-057-PREDICT\"", "\"id\": \"EJ41-057-PREDICT\", \"answer\": \"secret-input\"", FIELDS),
                new Mutation("\"schema\":", "\"schema\": \"secret-input\", \"schema\":", DUPLICATE_FIELD),
                new Mutation("\"title\":", "\"title\": \"secret-input\", \"title\":", DUPLICATE_FIELD),
                new Mutation("\"stage\": \"PREDICT\"", "\"stage\": \"PREDICT\", \"stage\": \"BUILD\"", DUPLICATE_FIELD),
                new Mutation("\"java.general.local.scope.v1\"", "\"Bad secret-input\"", IDENTIFIER),
                new Mutation("\"java.control.flow\"", "\"../secret-input\"", IDENTIFIER),
                new Mutation("\"java.control.flow\"", "\"https://private.invalid/secret-input\"", IDENTIFIER),
                new Mutation("\"java.control.flow\"", "\"java.general.local.scope.v0\"", PIN_MISMATCH),
                new Mutation("EJ-ITEM-057", "EJ-ITEM-999", IDENTIFIER),
                new Mutation("EJ41-057-PREDICT", "EJ40-057-PREDICT", IDENTIFIER),
                new Mutation("43b67abff90d1f079a80f0a24a3a519bf1a482c674fed3f53c9b6e767bef4adb", "secret-input", FINGERPRINT),
                new Mutation("a4a1e168f2b00c26677e7f75052d5585cf4425f1641af697dd7927a7ae25e865", "A".repeat(64), FINGERPRINT),
                new Mutation("\"skill_id\": \"java.general.enhanced.iteration.v1\"",
                        "\"skill_id\": \"java.general.local.scope.v1\"", DUPLICATE_ID),
                new Mutation("\"source_item\": \"EJ-ITEM-058\"", "\"source_item\": \"EJ-ITEM-057\"", DUPLICATE_ID),
                new Mutation("EJ41-057-DEBUG", "EJ41-057-PREDICT", DUPLICATE_ID),
                new Mutation("\"stage\": \"DEBUG\"", "\"stage\": \"PREDICT\"", STAGES),
                new Mutation("\"stage\": \"DEFEND\"", "\"stage\": \"REVIEW\"", STAGES),
                new Mutation("\"java.control.flow\"", "\"java.general.missing.v1\"", PIN_MISMATCH),
                new Mutation("\"skill_id\": \"java.general.local.scope.v1\"",
                        "\"skill_id\": \"java.general.other.scope.v1\"", MISSING_REFERENCE),
                new Mutation("\"java.control.flow\"", "\"java.general.enhanced.iteration.v1\"", CYCLE),
                new Mutation("\"java.control.flow\"", "\"java.general.local.scope.v1\"", CYCLE),
                new Mutation("\"java.numeric.integer.overflow\"", "\"java.general.exact.decimal.v1\"", CYCLE),
                new Mutation("\"java.control.flow\"", "\"java.exceptions.checked\"", DUPLICATE_ID),
                new Mutation("Limit local state", "secret-input", PIN_MISMATCH),
                new Mutation("43b67abff90d1f079a80f0a24a3a519bf1a482c674fed3f53c9b6e767bef4adb", "0".repeat(64), PIN_MISMATCH),
                new Mutation("a4a1e168f2b00c26677e7f75052d5585cf4425f1641af697dd7927a7ae25e865", "0".repeat(64), PIN_MISMATCH),
                new Mutation("\"java.control.flow\"", "\"java.debugging.reproduce\"", PIN_MISMATCH),
                new Mutation("\"java.control.flow\"", "\"java.external.unknown\"", PIN_MISMATCH));
    }

    @Test
    void exactMappingBlockersAndPublicArtifactArePinned() {
        PublicPreview preview = KnowledgePreview.loadBundled();
        Set<PublicPreview.Blocker> expected = Set.of(
                new PublicPreview.Blocker("UNMAPPED_SKILL", "java.general.local.scope.v1"),
                new PublicPreview.Blocker("UNMAPPED_SKILL", "java.general.enhanced.iteration.v1"),
                new PublicPreview.Blocker("UNMAPPED_SKILL", "java.general.library.selection.v1"),
                new PublicPreview.Blocker("UNMAPPED_SKILL", "java.general.exact.decimal.v1"),
                new PublicPreview.Blocker("UNRESOLVED_EXTERNAL_PREREQUISITE", "java.control.flow"),
                new PublicPreview.Blocker("UNRESOLVED_EXTERNAL_PREREQUISITE", "java.exceptions.checked"),
                new PublicPreview.Blocker("UNRESOLVED_EXTERNAL_PREREQUISITE", "java.collections.iteration"),
                new PublicPreview.Blocker("UNRESOLVED_EXTERNAL_PREREQUISITE", "java.numeric.integer.overflow"),
                new PublicPreview.Blocker("UNRESOLVED_EXTERNAL_PREREQUISITE", "java.api.contracts"),
                new PublicPreview.Blocker("UNRESOLVED_EXTERNAL_PREREQUISITE", "java.api.preconditions.v1"));
        assertEquals(expected, new HashSet<>(preview.blockers()));
        assertEquals(expected.size(), preview.blockers().size());
        assertTrue(preview.mappings().isEmpty(), "No suffix stripping or related-sounding graph substitutions");
        assertEquals(List.of("java.general.local.scope.v1", "java.collections.iteration"),
                preview.skills().get(1).prerequisites(), "Keep the actual internal prerequisite unchanged");
        assertEquals("95ff42f24a88ac85400a4cda43daf9e0caabab8e4e324fa21c992f78a2ecb874",
                KnowledgePreview.sha256(KnowledgePreviewCli.export(preview)));
    }

    @ParameterizedTest
    @MethodSource("mutations")
    void rejectsTamperingBeforeOrAtPin(Mutation mutation) throws IOException {
        String changed = text().replace(mutation.from(), mutation.to());
        assertNotEquals(text(), changed);
        rejects(mutation.code(), utf8(changed));
    }

    @Test
    void originalOfflineReplayAndExactResource() throws IOException {
        byte[] input = original();
        assertArrayEquals(input, getClass().getResourceAsStream(
                "/knowledge/hunter-pilot-v0.41/preview-catalog.json").readAllBytes());
        assertEquals(KnowledgePreview.SHA256, KnowledgePreview.sha256(input));
        PublicPreview first = KnowledgePreview.verify(input);
        Arrays.fill(input, (byte) 0);
        assertEquals(first, KnowledgePreview.loadBundled());
        assertEquals(first, KnowledgePreview.verify(original()));
        assertEquals("PUBLIC_PREVIEW", first.exposure());
        assertEquals(KnowledgePreview.PUBLIC_SCHEMA, first.schema());
        assertEquals(KnowledgePreview.SHA256, first.revisionSha256());
        assertFalse(first.productionImportSupported());
        assertFalse(first.graderAvailable());
        assertFalse(first.xpEnabled());
        assertFalse(first.rewardsEnabled());
        assertFalse(first.masteryEnabled());
        assertEquals(0, first.independentApprovals());
        assertEquals("UNREVIEWED", first.reviewStatus());
        assertEquals("JDK21_VERIFICATION_PENDING", first.verification());
        assertTrue(first.caveat().contains("Java9-era"));
        assertTrue(first.caveat().contains("not a deployable"));
        assertEquals(4, first.skills().size());
        assertEquals(16, first.skills().stream().mapToInt(s -> s.stages().size()).sum());
        assertTrue(first.mappings().isEmpty(), "No actual graph members match this pilot");
        assertEquals(10, first.blockers().size());
        assertEquals(6, first.blockers().stream().filter(b -> b.code().equals("UNRESOLVED_EXTERNAL_PREREQUISITE")).count());
    }

    @Test
    void immutableDeepSnapshot() {
        var prerequisites = new ArrayList<>(List.of("java.control.flow"));
        var stages = new ArrayList<>(List.of(new PublicPreview.Stage("BUILD", "EJ41-057-BUILD")));
        var skills = new ArrayList<>(List.of(new PublicPreview.Skill("id", "title", "EJ-ITEM-057",
                "0".repeat(64), prerequisites, stages)));
        var snapshot = new PublicPreview(KnowledgePreview.PUBLIC_SCHEMA, "PUBLIC_PREVIEW", "v0.41",
                KnowledgePreview.SHA256, "UNREVIEWED", "pending", false, false, false, false, false, 0, "caveat",
                skills, new ArrayList<>(), new ArrayList<>());
        prerequisites.clear();
        stages.clear();
        skills.clear();
        assertEquals(1, snapshot.skills().size());
        assertEquals(1, snapshot.skills().getFirst().prerequisites().size());
        assertEquals(1, snapshot.skills().getFirst().stages().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.skills().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.skills().getFirst().prerequisites().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.skills().getFirst().stages().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.blockers().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.mappings().clear());
    }

    @Test
    void boundsEncodingAndTrailingTokens() throws IOException {
        rejects(TOO_LARGE, new byte[KnowledgePreview.MAX_BYTES + 1]);
        assertThrows(PreviewFailure.class, () -> KnowledgePreview.read(
                new ByteArrayInputStream(new byte[KnowledgePreview.MAX_BYTES + 1])));
        rejects(LIMIT, utf8("[".repeat(20) + "0" + "]".repeat(20)));
        rejects(LIMIT, utf8("{\"schema\":\"" + "x".repeat(1000) + "\"}"));
        rejects(LIMIT, utf8("[" + "0,".repeat(2200) + "0]"));
        rejects(INVALID_JSON, utf8(text() + "{}"));
        rejects(INVALID_JSON, utf8(text() + " secret-input"));
        rejects(INVALID_JSON, new byte[]{'{', '"', (byte) 0xc3, (byte) 0x28, '"', ':', '0', '}'});
        rejects(INVALID_JSON, new byte[0]);
        rejects(INVALID_JSON, utf8("{"));
        rejects(TYPE, utf8("null"));
        rejects(TYPE, utf8("[]"));
        rejects(TYPE, utf8(text().replace("\"independent_approvals\": 0", "\"independent_approvals\": null")));
        rejects(LIMIT, utf8(text().replace("\"independent_approvals\": 0",
                "\"independent_approvals\": 12345678901234567")));
        rejects(INVALID_JSON, utf8(text().substring(0, text().lastIndexOf(']'))));
        rejects(PIN_MISMATCH, utf8(text() + "\n"));
    }

    @Test
    void countCoverageAndReplacementOverlays() throws IOException {
        String source = text();
        int start = source.indexOf("    {"), end = source.indexOf("\n    },", start) + 7;
        String first = source.substring(start, end);
        rejects(COUNT, utf8(source.replace(first + "\n", "")));
        rejects(DUPLICATE_ID, utf8(source.substring(0, start) + first + "\n" + source.substring(start)));
        String stage = "        {\n          \"stage\": \"DEFEND\",\n          \"id\": \"EJ41-057-DEFEND\"\n        }";
        rejects(STAGES, utf8(source.replace(",\n" + stage, "")));
        rejects(DUPLICATE_ID, utf8(source.replace("EJ41-058-PREDICT", "EJ41-057-PREDICT")));
        rejects(FIELDS, utf8(source.replace("\"skills\": [", "\"replaced_drafts\": [\"EJ41-057-PREDICT\"], \"skills\": [")));
    }

    @Test
    void exportIsDeterministicAllowlistWithoutPrivateProvenanceOrProgression() throws IOException {
        PublicPreview preview = KnowledgePreview.loadBundled();
        byte[] bytes = KnowledgePreviewCli.export(preview);
        assertArrayEquals(bytes, KnowledgePreviewCli.export(KnowledgePreview.loadBundled()));
        String output = new String(bytes, StandardCharsets.UTF_8);
        for (String forbidden : List.of("private_checkpoint", "source_body", "solution", "gradable",
                "a4a1e168", "AUTHOR_DRAFT")) assertFalse(output.contains(forbidden), forbidden);
        assertTrue(output.contains("PUBLIC_PREVIEW"));
        assertTrue(output.contains("\"sourceItem\":\"EJ-ITEM-057\""));
        assertTrue(output.contains("\"sourceSectionSha256\":\"43b67abf"));
        for (String disabled : List.of("productionImportSupported", "graderAvailable", "xpEnabled",
                "rewardsEnabled", "masteryEnabled")) assertTrue(output.contains("\"" + disabled + "\":false"));
        assertTrue(output.contains("\"independentApprovals\":0"));
        // Architecture guard: adapter/CLI/DTO have no database, progression or host-method dependency.
        for (Class<?> type : List.of(KnowledgePreview.class, KnowledgePreviewCli.class, PublicPreview.class)) {
            String classBytes;
            try (InputStream stream = type.getResourceAsStream(type.getSimpleName() + ".class")) {
                classBytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            for (String forbidden : List.of("java/sql", "com/codefit/repository", "com/codefit/desktop",
                    "com/codefit/evolution/mastery", "com/codefit/evolution/quest",
                    "java/net", "java/lang/ProcessBuilder")) assertFalse(classBytes.contains(forbidden), forbidden);
        }
    }

    @Test
    void corruptedResourceManifestOrRevisionFailsClosed() throws Exception {
        assertCorruptResource("preview-catalog.sha256", utf8("0".repeat(64) + "  preview-catalog.json\n"), RESOURCE_PIN);
        assertCorruptResource("preview-catalog.json", utf8(text().replace("Limit local state", "Altered local state")), PIN_MISMATCH);
        assertCorruptResource("preview-catalog.json", utf8(text().replace("v0.41", "v0.42")), VERSION);
    }

    private static void assertCorruptResource(String suffix, byte[] replacement, PreviewFailure.Code code) throws Exception {
        String name = KnowledgePreview.class.getName();
        ClassLoader isolated = new ClassLoader(KnowledgePreview.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!requested.equals(name) && !requested.equals(PreviewFailure.class.getName()))
                    return super.loadClass(requested, resolve);
                Class<?> type = findLoadedClass(requested);
                if (type == null) {
                    try (InputStream input = getParent().getResourceAsStream(requested.replace('.', '/') + ".class")) {
                        byte[] bytes = input.readAllBytes();
                        type = defineClass(requested, bytes, 0, bytes.length);
                    } catch (IOException e) { throw new ClassNotFoundException(); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
            @Override
            public InputStream getResourceAsStream(String path) {
                return path.endsWith("/" + suffix) ? new ByteArrayInputStream(replacement) : super.getResourceAsStream(path);
            }
        };
        var invocation = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> isolated.loadClass(name).getMethod("loadBundled").invoke(null));
        assertEquals(PreviewFailure.class.getName(), invocation.getCause().getClass().getName());
        assertEquals(code, invocation.getCause().getClass().getMethod("code").invoke(invocation.getCause()));
    }

    @Test
    void cliBuildAndExportVerifyBeforeWriting() throws Exception {
        Path output = Path.of("target/knowledge-preview/public-preview.json");
        Path checksum = Path.of("target/knowledge-preview/public-preview.json.sha256");
        Files.deleteIfExists(output);
        Files.deleteIfExists(checksum);
        Files.deleteIfExists(output.getParent());
        assertEquals(0, cli("build", ORIGINAL.toString()).exit());
        assertFalse(Files.exists(output));
        assertEquals(0, cli("export", ORIGINAL.toString()).exit());
        byte[] exported = Files.readAllBytes(output);
        assertEquals(KnowledgePreview.sha256(exported) + "  public-preview.json\n", Files.readString(checksum));
        var modified = Files.getLastModifiedTime(output);
        assertEquals(0, cli("export", ORIGINAL.toString()).exit());
        assertArrayEquals(exported, Files.readAllBytes(output));
        assertEquals(modified, Files.getLastModifiedTime(output));
        Path invalid = Path.of("target/knowledge-preview/invalid-input.json");
        try {
            Files.writeString(invalid, text().replace("v0.41", "v0.42"));
            CliResult failure = cli("export", invalid.toString());
            assertEquals(1, failure.exit());
            assertEquals("VERSION", failure.output().strip());
            assertArrayEquals(exported, Files.readAllBytes(output));
            assertEquals(1, cli("import", ORIGINAL.toString()).exit());
            assertEquals(1, cli("export", ORIGINAL.toString(), "arbitrary.json").exit());
        } finally { Files.deleteIfExists(invalid); }
        byte[] checksumBytes = Files.readAllBytes(checksum);
        try {
            Files.writeString(output, "conflicting-existing-revision");
            CliResult conflict = cli("export", ORIGINAL.toString());
            assertEquals(1, conflict.exit());
            assertEquals("OUTPUT_CONFLICT", conflict.output().strip());
            assertEquals("conflicting-existing-revision", Files.readString(output));
            assertArrayEquals(checksumBytes, Files.readAllBytes(checksum));
            Files.delete(output);
            assertEquals("OUTPUT_CONFLICT", cli("export", ORIGINAL.toString()).output().strip());
            assertFalse(Files.exists(output), "Never repairs a partial pair by overwriting revision state");
            Files.delete(checksum);
            assertEquals("OUTPUT_CONFLICT", cli("export", ORIGINAL.toString()).output().strip());
            assertFalse(Files.exists(output));
            assertFalse(Files.exists(checksum));
            Files.delete(output.getParent());
            Files.createDirectory(Path.of("target/.knowledge-preview-staging"));
            assertEquals("IO", cli("export", ORIGINAL.toString()).output().strip());
            assertFalse(Files.exists(output.getParent()), "Staging collision never publishes a partial pair");
        } finally {
            Files.deleteIfExists(Path.of("target/.knowledge-preview-staging"));
            Files.deleteIfExists(output);
            Files.deleteIfExists(checksum);
            Files.deleteIfExists(output.getParent());
            assertEquals(0, cli("export", ORIGINAL.toString()).exit());
        }
        assertFalse(Files.exists(Path.of("target/.knowledge-preview-staging")));
    }

    private record CliResult(int exit, String output) { }
    private static CliResult cli(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), KnowledgePreviewCli.class.getName()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new CliResult(process.waitFor(), output);
    }
}

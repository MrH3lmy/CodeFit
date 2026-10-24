package com.codefit.knowledge;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.SkillGraph;
import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import static com.codefit.knowledge.PreviewFailure.Code.*;

/** Offline, pinned, original-author metadata reader. Never publishes graphs or learner evidence. */
public final class KnowledgePreview {
    public static final String REVISION = "v0.41";
    public static final String PUBLIC_SCHEMA = "codefit-public-knowledge-preview/0.1";
    public static final String SHA256 = "76377d04ecb0ff45690df32bbc001f66bfaa672e1935327e1c09b2b047a46f52";
    public static final int MAX_BYTES = 32_768;
    private static final String BASE = "/knowledge/hunter-pilot-v0.41/";
    private static final Set<String> REQUIRED_STAGES = Set.of("PREDICT", "DEBUG", "BUILD", "DEFEND");
    private static final Set<String> PILOT_IDS = Set.of(
            "java.general.local.scope.v1", "java.general.enhanced.iteration.v1",
            "java.general.library.selection.v1", "java.general.exact.decimal.v1");
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                    .maxStringLength(512).maxNumberLength(16).build()).build();

    private KnowledgePreview() { }

    public static PublicPreview loadBundled() {
        byte[] pin = resource("preview-catalog.sha256");
        if (!new String(pin, StandardCharsets.US_ASCII).equals(SHA256 + "  preview-catalog.json\n"))
            throw fail(RESOURCE_PIN);
        return verify(resource("preview-catalog.json"));
    }

    static byte[] read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw fail(TOO_LARGE);
        return bytes;
    }

    private static byte[] resource(String name) {
        try (InputStream input = KnowledgePreview.class.getResourceAsStream(BASE + name)) {
            if (input == null) throw fail(IO);
            return read(input);
        } catch (IOException e) { throw fail(IO); }
    }

    static PublicPreview verify(byte[] supplied) {
        if (supplied == null) throw fail(TYPE);
        if (supplied.length > MAX_BYTES) throw fail(TOO_LARGE);
        byte[] bytes = supplied.clone();
        Map<String, Object> root = object(parse(bytes));
        fields(root, "schema", "source_version", "private_checkpoint_sha256", "source_kind",
                "production_import_supported", "independent_approvals", "grader_available",
                "xp_enabled", "mastery_enabled", "source_body_included", "prerequisite_mapping_status", "skills");
        if (!text(root, "schema").equals("codefit-curriculum-preview/0.1")
                || !text(root, "source_version").equals(REVISION)) throw fail(VERSION);
        fingerprint(text(root, "private_checkpoint_sha256"));
        if (!text(root, "source_kind").equals("original_author_metadata_only")
                || !text(root, "prerequisite_mapping_status").equals("UNRESOLVED")) throw fail(FLAGS);
        disabled(root, "production_import_supported", "grader_available", "xp_enabled",
                "mastery_enabled", "source_body_included");
        if (!(root.get("independent_approvals") instanceof Long n)) throw fail(TYPE);
        if (n != 0) throw fail(FLAGS);
        List<PublicPreview.Skill> skills = new ArrayList<>();
        Set<String> ids = new HashSet<>(), stageIds = new HashSet<>(), sources = new HashSet<>();
        List<?> rawSkills = array(root.get("skills"));
        for (Object value : rawSkills) {
            Map<String, Object> skill = object(value);
            fields(skill, "skill_id", "title", "source_item", "source_section_sha256", "prerequisites",
                    "mission_stages", "review_status", "release_eligible", "gradable", "mastery_eligible");
            String id = identifier(text(skill, "skill_id"));
            if (!ids.add(id)) throw fail(DUPLICATE_ID);
            String source = text(skill, "source_item");
            if (!source.matches("EJ-ITEM-0(?:57|58|59|60)")) throw fail(IDENTIFIER);
            if (!sources.add(source)) throw fail(DUPLICATE_ID);
            fingerprint(text(skill, "source_section_sha256"));
            if (!text(skill, "review_status").equals("AUTHOR_DRAFT_PENDING_EXTERNAL_REVIEW")) throw fail(FLAGS);
            disabled(skill, "release_eligible", "gradable", "mastery_eligible");
            String title = text(skill, "title");
            if (title.isBlank() || title.length() > 256) throw fail(LIMIT);
            List<String> prerequisites = new ArrayList<>();
            for (Object prerequisite : array(skill.get("prerequisites"))) {
                if (!(prerequisite instanceof String p)) throw fail(TYPE);
                identifier(p);
                if (prerequisites.contains(p)) throw fail(DUPLICATE_ID);
                prerequisites.add(p);
            }
            if (prerequisites.size() > 16) throw fail(COUNT);
            List<PublicPreview.Stage> stages = new ArrayList<>();
            Set<String> coverage = new HashSet<>();
            for (Object rawStage : array(skill.get("mission_stages"))) {
                Map<String, Object> stage = object(rawStage);
                fields(stage, "stage", "id");
                String kind = text(stage, "stage"), stageId = text(stage, "id");
                if (!stageIds.add(stageId)) throw fail(DUPLICATE_ID);
                if (!REQUIRED_STAGES.contains(kind) || !coverage.add(kind)) throw fail(STAGES);
                if (!stageId.equals("EJ41-" + source.substring(8) + "-" + kind)) throw fail(IDENTIFIER);
                stages.add(new PublicPreview.Stage(kind, stageId));
            }
            if (!coverage.equals(REQUIRED_STAGES)) throw fail(STAGES);
            skills.add(new PublicPreview.Skill(id, title, source,
                    text(skill, "source_section_sha256"), prerequisites, stages));
        }
        if (skills.size() != 4) throw fail(COUNT);
        for (var skill : skills) {
            for (String prerequisite : skill.prerequisites()) {
                if (PILOT_IDS.contains(prerequisite) && !ids.contains(prerequisite))
                    throw fail(MISSING_REFERENCE);
            }
        }
        Map<String, PublicPreview.Skill> byId = new HashMap<>();
        skills.forEach(s -> byId.put(s.id(), s));
        Set<String> done = new HashSet<>(), visiting = new HashSet<>();
        for (var skill : skills) visit(skill.id(), byId, visiting, done);
        if (!sha256(bytes).equals(SHA256)) throw fail(PIN_MISMATCH);

        // Only the actual pinned seed is considered. No suffix, alias, or "latest" inference.
        SkillGraph graph = JavaDebuggingSkillSeed.graph();
        List<PublicPreview.Mapping> mappings = new ArrayList<>();
        List<PublicPreview.Blocker> blockers = new ArrayList<>();
        Set<String> references = new TreeSet<>(ids);
        skills.forEach(s -> references.addAll(s.prerequisites()));
        for (String reference : references) {
            var match = graph.definitions().stream().filter(d -> d.ref().skillId().equals(reference)).findFirst();
            if (match.isPresent()) mappings.add(new PublicPreview.Mapping(reference, match.get().ref(), graph.ref()));
            else blockers.add(new PublicPreview.Blocker(ids.contains(reference)
                    ? "UNMAPPED_SKILL" : "UNRESOLVED_EXTERNAL_PREREQUISITE", reference));
        }
        return new PublicPreview(PUBLIC_SCHEMA, "PUBLIC_PREVIEW", REVISION, SHA256,
                "UNREVIEWED", "JDK21_VERIFICATION_PENDING", false, false, false, false, false, 0,
                "Java9-era guidance requires independent JDK21 verification; not a deployable challenge catalog.",
                skills, mappings, blockers);
    }

    private static void visit(String id, Map<String, PublicPreview.Skill> skills, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw fail(CYCLE);
        for (String p : skills.get(id).prerequisites()) if (skills.containsKey(p)) visit(p, skills, visiting, done);
        visiting.remove(id);
        done.add(id);
    }

    private static Object parse(byte[] bytes) {
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonParser parser = JSON.createParser(utf8)) {
                parser.nextToken();
                Object result = value(parser, 0, new int[]{0});
                if (parser.nextToken() != null) throw fail(INVALID_JSON);
                return result;
            }
        } catch (com.fasterxml.jackson.core.exc.StreamConstraintsException e) { throw fail(LIMIT);
        } catch (IOException | ArithmeticException e) { throw fail(INVALID_JSON); }
    }

    private static Object value(JsonParser p, int depth, int[] count) throws IOException {
        if (depth > 8 || ++count[0] > 2048) throw fail(LIMIT);
        JsonToken token = p.currentToken();
        if (token == JsonToken.START_OBJECT) {
            Map<String, Object> result = new LinkedHashMap<>();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME) throw fail(INVALID_JSON);
                String key = p.currentName();
                if (result.containsKey(key)) throw fail(DUPLICATE_FIELD);
                p.nextToken();
                result.put(key, value(p, depth + 1, count));
            }
            return result;
        }
        if (token == JsonToken.START_ARRAY) {
            List<Object> result = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) result.add(value(p, depth + 1, count));
            return result;
        }
        if (token == JsonToken.VALUE_STRING) return p.getText();
        if (token == JsonToken.VALUE_TRUE) return true;
        if (token == JsonToken.VALUE_FALSE) return false;
        if (token == JsonToken.VALUE_NUMBER_INT) return p.getLongValue();
        if (token == JsonToken.VALUE_NULL || token == JsonToken.VALUE_NUMBER_FLOAT) throw fail(TYPE);
        throw fail(INVALID_JSON);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw fail(TYPE);
        return (Map<String, Object>) value;
    }
    private static List<?> array(Object value) {
        if (!(value instanceof List<?> list)) throw fail(TYPE);
        return list;
    }
    private static void fields(Map<String, Object> value, String... names) {
        if (!value.keySet().equals(Set.of(names))) throw fail(FIELDS);
    }
    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String s)) throw fail(TYPE);
        return s;
    }
    private static void disabled(Map<String, Object> value, String... keys) {
        for (String key : keys) {
            if (!(value.get(key) instanceof Boolean b)) throw fail(TYPE);
            if (b) throw fail(FLAGS);
        }
    }
    private static String identifier(String id) {
        if (id.length() > 128 || !id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")) throw fail(IDENTIFIER);
        return id;
    }
    private static void fingerprint(String hash) {
        if (!hash.matches("[0-9a-f]{64}")) throw fail(FINGERPRINT);
    }
    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA256_UNAVAILABLE"); }
    }
    static PreviewFailure fail(PreviewFailure.Code code) { return new PreviewFailure(code); }
}

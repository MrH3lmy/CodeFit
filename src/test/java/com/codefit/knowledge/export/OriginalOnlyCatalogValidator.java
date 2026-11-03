package com.codefit.knowledge.export;

import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Developer-only, stdin-to-stdout validation; deliberately absent from the application JAR. */
public final class OriginalOnlyCatalogValidator {
    static final int MAX_BYTES = 131_072;
    static final String SCHEMA = "codefit-original-only/1";
    private static final Set<String> STAGES = Set.of("PREDICT", "DEBUG", "BUILD", "DEFEND");
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                    .maxStringLength(512).maxNumberLength(8).build()).build();

    record Failure(String category, String code) {}
    record Result(String preview, String manifest, Failure error) {
        boolean valid() { return error == null; }
    }
    private static final class Rejected extends RuntimeException {
        final Failure failure;
        Rejected(String category, String code) { failure = new Failure(category, code); }
    }

    private OriginalOnlyCatalogValidator() {}

    static Result validate(byte[] supplied, String expectedRevision, String expectedSha256) {
        try {
            if (supplied == null) throw reject("FORMAT", "TYPE");
            if (supplied.length > MAX_BYTES) throw reject("FORMAT", "TOO_LARGE");
            byte[] bytes = supplied.clone();
            identifier(expectedRevision);
            if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}"))
                throw reject("REVISION", "EXPECTED_PIN_REQUIRED");
            Map<String, Object> root = object(parse(bytes));
            fields(root, "schema", "revision", "sourceKind", "permittedRelease", "books", "units", "skills");
            exact(root, "schema", SCHEMA, "FORMAT");
            String revision = identifier(text(root, "revision"));
            if (!revision.equals(expectedRevision)) throw reject("REVISION", "STALE_REVISION");
            exact(root, "sourceKind", "ORIGINAL_ONLY", "ORIGINALITY");
            exact(root, "permittedRelease", "METADATA_ONLY", "RELEASE");

            List<?> books = array(root.get("books"), 5, 5);
            Set<String> allIds = new HashSet<>(), bookIds = new HashSet<>();
            for (Object value : books) {
                var book = object(value);
                fields(book, "id");
                bookIds.add(unique(book, allIds));
            }
            Map<String, Map<String, Object>> units = new LinkedHashMap<>();
            for (Object value : array(root.get("units"), 5, 100)) {
                var unit = object(value);
                fields(unit, "id", "bookId", "coverage", "skillIds");
                String id = unique(unit, allIds);
                reference(text(unit, "bookId"), bookIds);
                coverage(unit);
                identifiers(unit.get("skillIds"), 100);
                units.put(id, unit);
            }
            Set<String> representedBooks = new HashSet<>();
            units.values().forEach(u -> representedBooks.add(text(u, "bookId")));
            if (!representedBooks.equals(bookIds)) throw reject("LINKS", "BOOK_WITHOUT_UNIT");

            Map<String, Map<String, Object>> skills = new LinkedHashMap<>();
            for (Object value : array(root.get("skills"), 0, 100)) {
                var skill = object(value);
                fields(skill, "id", "bookId", "unitId", "mappingStatus", "prerequisites", "stages");
                String id = unique(skill, allIds);
                reference(text(skill, "unitId"), units.keySet());
                if (!text(skill, "bookId").equals(text(units.get(text(skill, "unitId")), "bookId")))
                    throw reject("LINKS", "BOOK_UNIT_MISMATCH");
                exact(skill, "mappingStatus", "UNMAPPED", "MAPPING");
                identifiers(skill.get("prerequisites"), 16);
                Set<String> kinds = new HashSet<>();
                for (Object rawStage : array(skill.get("stages"), 4, 4)) {
                    var stage = object(rawStage);
                    fields(stage, "id", "bookId", "unitId", "skillId", "stage", "status",
                            "coverage", "brief", "independentReview", "permittedRelease");
                    String stageId = text(stage, "id");
                    if (stageId.length() > 128 || !stageId.matches("[A-Za-z][A-Za-z0-9]*(?:[.-][A-Za-z0-9]+)*"))
                        throw reject("FORMAT", "IDENTIFIER");
                    if (!allIds.add(stageId)) throw reject("LINKS", "DUPLICATE_ID");
                    String kind = text(stage, "stage");
                    if (!STAGES.contains(kind) || !kinds.add(kind)) throw reject("STAGES", "STAGE_SET");
                    for (String link : List.of("bookId", "unitId"))
                        if (!text(stage, link).equals(text(skill, link)))
                            throw reject("LINKS", "STAGE_LINK_MISMATCH");
                    if (!text(stage, "skillId").equals(id)) throw reject("LINKS", "STAGE_LINK_MISMATCH");
                    exact(stage, "permittedRelease", "METADATA_ONLY", "RELEASE");
                    validateStage(stage, revision);
                }
                skills.put(id, skill);
            }
            for (var unit : units.values()) {
                Set<String> declared = identifiers(unit.get("skillIds"), 100);
                Set<String> actual = new HashSet<>();
                skills.forEach((id, s) -> { if (text(s, "unitId").equals(text(unit, "id"))) actual.add(id); });
                if (!declared.equals(actual)) throw reject("LINKS", "UNIT_SKILL_MISMATCH");
            }
            for (var skill : skills.values())
                for (String id : identifiers(skill.get("prerequisites"), 16)) reference(id, skills.keySet());
            Set<String> done = new HashSet<>();
            for (String id : skills.keySet()) visit(id, skills, new HashSet<>(), done);
            if (!sha256(bytes).equals(expectedSha256)) throw reject("REVISION", "PIN_MISMATCH");

            String preview = preview(root, skills);
            String manifest = json(Map.of("schema", "codefit-original-only-manifest/1",
                    "revision", revision, "inputSha256", expectedSha256, "previewSha256", sha256(preview.getBytes(StandardCharsets.UTF_8)),
                    "permittedRelease", "METADATA_ONLY"));
            return new Result(preview, manifest, null);
        } catch (Rejected e) {
            return new Result(null, null, e.failure);
        }
    }

    private static void validateStage(Map<String, Object> stage, String revision) {
        String status = text(stage, "status"), coverage = coverage(stage);
        var brief = object(stage.get("brief"));
        fields(brief, "status", "text");
        String body = text(brief, "text"), briefStatus = text(brief, "status");
        var review = object(stage.get("independentReview"));
        fields(review, "revision", "technical", "pedagogical");
        if (!text(review, "revision").equals(revision)) throw reject("REVISION", "STALE_REVIEW");
        String technical = decision(review.get("technical")), pedagogical = decision(review.get("pedagogical"));
        boolean consistent = switch (status) {
            case "ABSENT" -> briefStatus.equals("ABSENT") && body.isEmpty() && coverage.equals("UNREPORTED")
                    && technical.equals("UNREVIEWED") && pedagogical.equals("UNREVIEWED");
            case "UNSUPPORTED" -> briefStatus.equals("UNSUPPORTED") && body.isEmpty() && coverage.equals("UNREPORTED")
                    && technical.equals("UNSUPPORTED") && pedagogical.equals("UNSUPPORTED");
            case "UNREVIEWED", "REVIEW_PENDING" -> briefStatus.equals("ORIGINAL_AUTHOR_DRAFT")
                    && body.matches("[A-Za-z0-9][A-Za-z0-9 ,;()'!?-]{0,255}")
                    && coverage.equals("AUTHOR_REPORTED")
                    && Set.of("UNREVIEWED", "REVIEW_PENDING").contains(technical)
                    && Set.of("UNREVIEWED", "REVIEW_PENDING").contains(pedagogical)
                    && status.equals(technical.equals("REVIEW_PENDING") || pedagogical.equals("REVIEW_PENDING")
                            ? "REVIEW_PENDING" : "UNREVIEWED");
            default -> throw reject("STAGES", "UNKNOWN_STATUS");
        };
        if (!consistent) throw reject("STAGES", "INCONSISTENT_STATUS");
    }

    private static String decision(Object value) {
        var decision = object(value);
        fields(decision, "status", "history");
        String status = text(decision, "status");
        List<?> history = array(decision.get("history"), 1, 2);
        List<String> expected = switch (status) {
            case "UNREVIEWED" -> List.of("UNREVIEWED");
            case "REVIEW_PENDING", "UNSUPPORTED" -> List.of("UNREVIEWED", status);
            default -> throw reject("REVIEW", "UNSUPPORTED_DECISION");
        };
        if (!history.equals(expected)) throw reject("REVIEW", "INVALID_TRANSITION");
        return status;
    }

    private static String preview(Map<String, Object> root, Map<String, Map<String, Object>> skills) {
        List<Object> publicSkills = new ArrayList<>();
        for (var skill : skills.values()) {
            List<Object> stages = new ArrayList<>();
            for (Object raw : (List<?>) skill.get("stages")) {
                var stage = object(raw);
                var review = object(stage.get("independentReview"));
                stages.add(Map.of("id", text(stage, "id"), "stage", text(stage, "stage"),
                        "status", text(stage, "status"), "coverage", text(stage, "coverage"),
                        "briefStatus", text(object(stage.get("brief")), "status"),
                        "technicalStatus", text(object(review.get("technical")), "status"),
                        "pedagogicalStatus", text(object(review.get("pedagogical")), "status")));
            }
            publicSkills.add(Map.of("id", text(skill, "id"), "bookId", text(skill, "bookId"),
                    "unitId", text(skill, "unitId"), "mappingStatus", "UNMAPPED",
                    "prerequisites", skill.get("prerequisites"), "stages", stages));
        }
        Map<String, Object> dto = new TreeMap<>();
        dto.put("schema", "codefit-original-only-public-preview/1");
        dto.put("revision", text(root, "revision"));
        dto.put("exposure", "PUBLIC_PREVIEW");
        dto.put("reviewStatus", "UNREVIEWED");
        dto.put("permittedRelease", "METADATA_ONLY");
        for (String flag : List.of("releaseEligible", "productionImportSupported", "challengeExposure",
                "graderAvailable", "xpEnabled", "rewardsEnabled", "masteryEnabled")) dto.put(flag, false);
        dto.put("independentApprovals", 0);
        List<Object> books = new ArrayList<>(), units = new ArrayList<>();
        for (Object value : (List<?>) root.get("books")) books.add(Map.of("id", text(object(value), "id")));
        for (Object value : (List<?>) root.get("units")) {
            var unit = object(value);
            units.add(Map.of("id", text(unit, "id"), "bookId", text(unit, "bookId"),
                    "coverage", text(unit, "coverage"), "skillIds", unit.get("skillIds")));
        }
        dto.put("books", books);
        dto.put("units", units);
        dto.put("skills", publicSkills);
        return json(dto);
    }

    private static void visit(String id, Map<String, Map<String, Object>> skills, Set<String> visiting, Set<String> done) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw reject("LINKS", "CYCLE");
        for (String p : identifiers(skills.get(id).get("prerequisites"), 16)) visit(p, skills, visiting, done);
        visiting.remove(id);
        done.add(id);
    }

    private static String coverage(Map<String, Object> value) {
        String status = text(value, "coverage");
        if (!Set.of("UNREPORTED", "AUTHOR_REPORTED").contains(status)) throw reject("COVERAGE", "UNKNOWN_STATUS");
        return status;
    }

    private static Set<String> identifiers(Object value, int max) {
        Set<String> result = new LinkedHashSet<>();
        for (Object id : array(value, 0, max)) {
            if (!(id instanceof String s)) throw reject("FORMAT", "TYPE");
            if (!result.add(identifier(s))) throw reject("LINKS", "DUPLICATE_ID");
        }
        return result;
    }

    private static String unique(Map<String, Object> value, Set<String> ids) {
        String id = identifier(text(value, "id"));
        if (!ids.add(id)) throw reject("LINKS", "DUPLICATE_ID");
        return id;
    }

    private static void reference(String id, Set<String> ids) {
        identifier(id);
        if (!ids.contains(id)) throw reject("LINKS", "MISSING_REFERENCE");
    }

    private static String identifier(String id) {
        if (id == null || id.length() > 128 || !id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*"))
            throw reject("FORMAT", "IDENTIFIER");
        return id;
    }

    private static void fields(Map<String, Object> value, String... names) {
        if (!value.keySet().equals(Set.of(names))) throw reject("FORMAT", "FIELDS");
    }

    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String s)) throw reject("FORMAT", "TYPE");
        return s;
    }

    private static void exact(Map<String, Object> value, String key, String expected, String category) {
        if (!text(value, key).equals(expected)) throw reject(category, "UNSUPPORTED_VALUE");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw reject("FORMAT", "TYPE");
        return (Map<String, Object>) value;
    }

    private static List<?> array(Object value, int min, int max) {
        if (!(value instanceof List<?> list)) throw reject("FORMAT", "TYPE");
        if (list.size() < min || list.size() > max) throw reject("FORMAT", "COUNT");
        return list;
    }

    private static Object parse(byte[] bytes) {
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonParser parser = JSON.createParser(utf8)) {
                parser.nextToken();
                Object value = value(parser, new int[]{0});
                if (parser.nextToken() != null) throw reject("FORMAT", "INVALID_JSON");
                return value;
            }
        } catch (IOException e) { throw reject("FORMAT", "INVALID_JSON"); }
    }

    private static Object value(JsonParser parser, int[] count) throws IOException {
        if (++count[0] > 16_384) throw reject("FORMAT", "LIMIT");
        if (parser.currentToken() == JsonToken.START_OBJECT) {
            Map<String, Object> result = new TreeMap<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw reject("FORMAT", "INVALID_JSON");
                String key = parser.currentName();
                if (result.containsKey(key)) throw reject("FORMAT", "DUPLICATE_FIELD");
                parser.nextToken();
                result.put(key, value(parser, count));
            }
            return result;
        }
        if (parser.currentToken() == JsonToken.START_ARRAY) {
            List<Object> result = new ArrayList<>();
            while (parser.nextToken() != JsonToken.END_ARRAY) result.add(value(parser, count));
            return result;
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING) return parser.getText();
        throw reject("FORMAT", "TYPE");
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String json(Object value) {
        try {
            StringWriter output = new StringWriter();
            try (JsonGenerator generator = JSON.createGenerator(output)) { write(generator, value); }
            return output + "\n";
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static void write(JsonGenerator generator, Object value) throws IOException {
        if (value instanceof Map<?, ?> map) {
            generator.writeStartObject();
            for (String key : map.keySet().stream().map(Object::toString).sorted().toList()) {
                generator.writeFieldName(key);
                write(generator, map.get(key));
            }
            generator.writeEndObject();
        } else if (value instanceof List<?> list) {
            generator.writeStartArray();
            for (Object element : list) write(generator, element);
            generator.writeEndArray();
        } else if (value instanceof String text) generator.writeString(text);
        else if (value instanceof Boolean flag) generator.writeBoolean(flag);
        else if (value instanceof Integer number) generator.writeNumber(number);
        else throw new IllegalStateException();
    }

    private static Rejected reject(String category, String code) { return new Rejected(category, code); }

    static int run(String[] args, InputStream input, PrintStream output) {
        Result result;
        try {
            if (args.length != 2) throw reject("FORMAT", "CLI_ARGUMENTS");
            result = validate(input.readNBytes(MAX_BYTES + 1), args[0], args[1]);
        } catch (Rejected e) { result = new Result(null, null, e.failure);
        } catch (IOException e) { result = new Result(null, null, new Failure("FORMAT", "IO")); }
        if (result.valid()) {
            // Both artifacts are prepared before any output; the manifest hashes the exact preview plus LF.
            output.print("{\"manifest\":" + result.manifest().stripTrailing()
                    + ",\"preview\":" + result.preview().stripTrailing() + "}\n");
            return 0;
        }
        output.print(json(Map.of("schema", "codefit-original-only-error/1", "valid", false,
                "category", result.error().category(), "code", result.error().code())));
        return 1;
    }

    public static void main(String[] args) { System.exit(run(args, System.in, System.out)); }
}

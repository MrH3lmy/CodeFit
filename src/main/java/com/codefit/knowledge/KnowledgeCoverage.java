package com.codefit.knowledge;

import com.fasterxml.jackson.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import static com.codefit.knowledge.PreviewFailure.Code.*;
import static com.codefit.knowledge.PublicCoverage.*;

/** Fixed-classpath, byte-pinned coverage reader with no filesystem, network or learner capability. */
public final class KnowledgeCoverage {
    public static final String RESOURCE = "/knowledge/hunter-pilot-v0.41/coverage-ledger-input.json";
    public static final String SOURCE_PATH =
            "docs/knowledge-compiler/hunter-pilot-v0.41/coverage-ledger-input.json";
    public static final String SHA256 =
            "0466d16b2ec5809383b7d0bb40d068430bcd3890e06115f5be2cfffe46a4057e";
    public static final int MAX_BYTES = 8192;
    private static final String LEDGER_SCHEMA = "codefit-knowledge-coverage-ledger/1";
    private static final String[] IDS = {"ocp-java-se-17-guide", "effective-java",
            "100-java-mistakes", "java-coding-problems", "complete-coding-interview-guide-java"};
    private static final String[] TITLES = {"OCP Java SE 17 Guide", "Effective Java",
            "100 Java Mistakes", "Java Coding Problems", "Complete Coding Interview Guide in Java"};
    private static final int[] UNITS = {26, 90, 100, 269, 19};
    private static final JsonFactory JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(6)
                    .maxStringLength(512).maxNumberLength(4).build()).build();

    private KnowledgeCoverage() {}

    public static PublicCoverage loadBundled() {
        return load(KnowledgeCoverage.class.getResourceAsStream(RESOURCE));
    }

    static PublicCoverage load(InputStream supplied) {
        if (supplied == null) throw fail(IO);
        try (InputStream input = supplied) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            return verify(bytes);
        } catch (IOException failure) { throw fail(IO); }
    }

    static PublicCoverage verify(byte[] supplied) {
        if (supplied == null) throw fail(TYPE);
        if (supplied.length > MAX_BYTES) throw fail(TOO_LARGE);
        byte[] bytes = supplied.clone();
        Map<String, Object> root = object(parse(bytes));
        fields(root, "schema", "ledgerVersion", "provenance", "books", "reportedClaims");
        exact(root, "schema", LEDGER_SCHEMA);
        exact(root, "ledgerVersion", "1.0.0");
        Map<String, Object> p = object(root.get("provenance"));
        fields(p, "observedAt", "sourceVersion", "source", "verification",
                "privateSourceBodies", "previousCheckpoint", "limitations");
        exact(p, "observedAt", "2026-10-23");
        exact(p, "sourceVersion", "v0.41");
        exact(p, "source", "v0.41 delivery report, as attributed in CodeFit issue #298");
        exact(p, "verification", "SOURCE_ATTRIBUTED_NOT_INDEPENDENTLY_VERIFIED");
        exact(p, "privateSourceBodies", "NOT_INDEPENDENTLY_VERIFIED");
        exact(p, "previousCheckpoint", "NOT_AVAILABLE");
        List<String> limitations = strings(p.get("limitations"));
        if (limitations.size() != 3) throw fail(COUNT);
        Provenance provenance = new Provenance(text(p, "observedAt"), text(p, "sourceVersion"),
                text(p, "source"), text(p, "verification"), text(p, "privateSourceBodies"),
                text(p, "previousCheckpoint"), limitations);
        List<?> rawBooks = array(root.get("books"));
        if (rawBooks.size() != 5) throw fail(COUNT);
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < IDS.length; i++) {
            Map<String, Object> b = object(rawBooks.get(i));
            fields(b, "id", "title", "structuralUnits", "unitIds", "firstPassAuthoredUnits");
            exact(b, "id", IDS[i]);
            exact(b, "title", TITLES[i]);
            number(b, "structuralUnits", UNITS[i]);
            absent(b, "unitIds");
            Integer firstPass = null;
            if (i == 1) {
                number(b, "firstPassAuthoredUnits", 90);
                firstPass = 90;
            } else absent(b, "firstPassAuthoredUnits");
            books.add(new Book(IDS[i], TITLES[i], new Count("REPORTED_NOT_ENUMERATED", UNITS[i], null),
                    unknown(UNITS[i]), new Count(firstPass == null ? "UNKNOWN"
                    : "REPORTED_FIRST_PASS_NOT_REVIEWED", firstPass, UNITS[i]),
                    new Count("ZERO_REPORTED", 0, UNITS[i]),
                    new Count("ZERO_REPORTED", 0, UNITS[i])));
        }
        Map<String, Object> c = object(root.get("reportedClaims"));
        fields(c, "structuralUnits", "preliminaryMappedUnits", "reportedUnmappedUnits",
                "effectiveJavaFirstPassAuthorNotes", "missionBlueprintSlots", "deepOverlaySlots",
                "revisionEvents", "independentApprovals", "productionGraders", "hunterProductionImports");
        number(c, "structuralUnits", 504);
        number(c, "preliminaryMappedUnits", 157);
        number(c, "reportedUnmappedUnits", 347);
        number(c, "missionBlueprintSlots", 419);
        number(c, "deepOverlaySlots", 360);
        number(c, "independentApprovals", 0);
        number(c, "productionGraders", 0);
        number(c, "hunterProductionImports", 0);
        Map<String, Object> notes = object(c.get("effectiveJavaFirstPassAuthorNotes"));
        fields(notes, "count", "denominator");
        number(notes, "count", 90);
        number(notes, "denominator", 90);
        Map<String, Object> revisions = object(c.get("revisionEvents"));
        fields(revisions, "count", "additiveToMissionBlueprintSlots", "records");
        number(revisions, "count", 424);
        if (!Boolean.FALSE.equals(revisions.get("additiveToMissionBlueprintSlots"))) throw fail(FLAGS);
        absent(revisions, "records");
        if (!KnowledgePreview.sha256(bytes).equals(SHA256)) throw fail(PIN_MISMATCH);
        return new PublicCoverage(1, "codefit-knowledge-coverage/1", LEDGER_SCHEMA, "1.0.0",
                SHA256, SOURCE_PATH, true, false, provenance, books,
                new ReportedClaims(504, 157, 347, new FirstPassNotes(90, 90), 419, 360,
                        new RevisionEvents(424, false, null), 0, 0, 0), unknown(null),
                List.of("UNIT_IDS_UNAVAILABLE", "PER_BOOK_PRELIMINARY_MAPPING_UNKNOWN",
                        "FIRST_PASS_NOTES_NOT_INDEPENDENT_APPROVAL",
                        "INDEPENDENT_REVIEW_EVIDENCE_UNAVAILABLE",
                        "REVISION_EVENT_RECORDS_UNAVAILABLE", "RUNTIME_LEARNING_NOT_ESTABLISHED",
                        "LEARNER_VALIDATION_UNKNOWN"));
    }

    public static byte[] export(PublicCoverage coverage) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator j = JSON.createGenerator(out)) {
            j.writeStartObject();
            j.writeNumberField("v", coverage.v());
            j.writeStringField("schema", coverage.schema());
            j.writeStringField("ledgerSchema", coverage.ledgerSchema());
            j.writeStringField("ledgerVersion", coverage.ledgerVersion());
            j.writeStringField("ledgerSha256", coverage.ledgerSha256());
            j.writeStringField("sourcePath", coverage.sourcePath());
            j.writeBooleanField("localReviewOnly", coverage.localReviewOnly());
            j.writeBooleanField("productionImportSupported", coverage.productionImportSupported());
            Provenance p = coverage.provenance();
            j.writeObjectFieldStart("provenance");
            j.writeStringField("observedAt", p.observedAt());
            j.writeStringField("sourceVersion", p.sourceVersion());
            j.writeStringField("source", p.source());
            j.writeStringField("verification", p.verification());
            j.writeStringField("privateSourceBodies", p.privateSourceBodies());
            j.writeStringField("previousCheckpoint", p.previousCheckpoint());
            strings(j, "limitations", p.limitations());
            j.writeEndObject();
            j.writeArrayFieldStart("books");
            for (Book b : coverage.books()) {
                j.writeStartObject();
                j.writeStringField("id", b.id());
                j.writeStringField("title", b.title());
                count(j, "inventory", b.inventory());
                count(j, "preliminaryMapped", b.preliminaryMapped());
                count(j, "authorDraft", b.authorDraft());
                count(j, "independentReview", b.independentReview());
                count(j, "runtimeLearning", b.runtimeLearning());
                j.writeEndObject();
            }
            j.writeEndArray();
            ReportedClaims c = coverage.reportedClaims();
            j.writeObjectFieldStart("reportedClaims");
            j.writeNumberField("structuralUnits", c.structuralUnits());
            j.writeNumberField("preliminaryMappedUnits", c.preliminaryMappedUnits());
            j.writeNumberField("reportedUnmappedUnits", c.reportedUnmappedUnits());
            j.writeObjectFieldStart("effectiveJavaFirstPassAuthorNotes");
            j.writeNumberField("count", c.effectiveJavaFirstPassAuthorNotes().count());
            j.writeNumberField("denominator", c.effectiveJavaFirstPassAuthorNotes().denominator());
            j.writeEndObject();
            j.writeNumberField("missionBlueprintSlots", c.missionBlueprintSlots());
            j.writeNumberField("deepOverlaySlots", c.deepOverlaySlots());
            j.writeObjectFieldStart("revisionEvents");
            j.writeNumberField("count", c.revisionEvents().count());
            j.writeBooleanField("additiveToMissionBlueprintSlots",
                    c.revisionEvents().additiveToMissionBlueprintSlots());
            j.writeNullField("records");
            j.writeEndObject();
            j.writeNumberField("independentApprovals", c.independentApprovals());
            j.writeNumberField("productionGraders", c.productionGraders());
            j.writeNumberField("hunterProductionImports", c.hunterProductionImports());
            j.writeEndObject();
            count(j, "learnerValidated", coverage.learnerValidated());
            strings(j, "followupGaps", coverage.followupGaps());
            j.writeEndObject();
        }
        return out.toByteArray();
    }

    private static void count(JsonGenerator j, String name, Count c) throws IOException {
        j.writeObjectFieldStart(name);
        j.writeStringField("status", c.status());
        if (c.count() == null) j.writeNullField("count"); else j.writeNumberField("count", c.count());
        if (c.denominator() == null) j.writeNullField("denominator");
        else j.writeNumberField("denominator", c.denominator());
        j.writeEndObject();
    }

    private static void strings(JsonGenerator j, String name, List<String> values) throws IOException {
        j.writeArrayFieldStart(name);
        for (String value : values) j.writeString(value);
        j.writeEndArray();
    }

    private static Count unknown(Integer denominator) { return new Count("UNKNOWN", null, denominator); }

    private static Object parse(byte[] bytes) {
        try {
            String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try (JsonParser p = JSON.createParser(utf8)) {
                p.nextToken();
                Object result = value(p, 0, new int[]{0});
                if (p.nextToken() != null) throw fail(INVALID_JSON);
                return result;
            }
        } catch (com.fasterxml.jackson.core.exc.StreamConstraintsException e) { throw fail(LIMIT);
        } catch (IOException e) { throw fail(INVALID_JSON); }
    }

    private static Object value(JsonParser p, int depth, int[] count) throws IOException {
        if (depth > 6 || ++count[0] > 256) throw fail(LIMIT);
        JsonToken token = p.currentToken();
        if (token == JsonToken.START_OBJECT) {
            Map<String, Object> result = new LinkedHashMap<>();
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME) throw fail(INVALID_JSON);
                String key = p.currentName();
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
        if (token == JsonToken.VALUE_NUMBER_INT) return p.getLongValue();
        if (token == JsonToken.VALUE_TRUE) return true;
        if (token == JsonToken.VALUE_FALSE) return false;
        if (token == JsonToken.VALUE_NULL) return null;
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
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : array(value)) {
            if (!(item instanceof String s) || s.isBlank()) throw fail(TYPE);
            result.add(s);
        }
        return result;
    }
    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String s)) throw fail(TYPE);
        return s;
    }
    private static void fields(Map<String, Object> value, String... names) {
        if (!value.keySet().equals(Set.of(names))) throw fail(FIELDS);
    }
    private static void exact(Map<String, Object> value, String key, String expected) {
        if (!text(value, key).equals(expected)) throw fail(VERSION);
    }
    private static void number(Map<String, Object> value, String key, int expected) {
        if (!(value.get(key) instanceof Long n)) throw fail(TYPE);
        if (n != expected) throw fail(COUNT);
    }
    private static void absent(Map<String, Object> value, String key) {
        if (value.get(key) != null) throw fail(FLAGS);
    }
    private static PreviewFailure fail(PreviewFailure.Code code) { return new PreviewFailure(code); }
}

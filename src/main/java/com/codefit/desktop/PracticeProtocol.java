package com.codefit.desktop;

import com.codefit.evolution.quest.CatalogRef;
import com.fasterxml.jackson.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Separate protocol-v1 finite matrix. No polymorphic or arbitrary domain deserialization. */
final class PracticeProtocol {
    private static final JsonFactory JSON =
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(2)
                                    .maxStringLength(32768)
                                    .maxNumberLength(16)
                                    .build())
                    .build();

    record Request(
            String id,
            String method,
            String profile,
            String session,
            String command,
            CatalogRef quest,
            CatalogRef challenge,
            String presentation,
            long revision,
            String source,
            String reflection,
            String assistance) {}

    static Request parse(byte[] frame) throws IOException {
        if (frame.length > 524288) throw new IOException("INVALID_INPUT");
        var values = new HashMap<String, Object>();
        try (var p =
                JSON.createParser(
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(frame))
                                .toString())) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("INVALID_INPUT");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME)
                    throw new IOException("INVALID_INPUT");
                String key = p.currentName();
                var token = p.nextToken();
                Object value =
                        token == JsonToken.VALUE_STRING
                                ? p.getText()
                                : token == JsonToken.VALUE_NUMBER_INT ? p.getLongValue() : null;
                if (value == null || values.put(key, value) != null)
                    throw new IOException("INVALID_INPUT");
            }
            if (p.nextToken() != null) throw new IOException("INVALID_INPUT");
        }
        if (!Long.valueOf(1).equals(values.get("v"))) throw new IOException("INVALID_INPUT");
        String id = str(values, "id"), method = str(values, "method");
        var keys = new HashSet<>(Set.of("v", "id", "method", "profileId", "sessionId"));
        if (Set.of(
                        "practice.status",
                        "practice.open",
                        "practice.draft.save",
                        "practice.draft.status",
                        "practice.submission.prepare",
                        "practice.submission.status",
                        "practice.submission.resume",
                        "practice.submission.cancel",
                        "practice.submission.debrief")
                .contains(method)) keys.add("commandId");
        if (method.equals("practice.open"))
            keys.addAll(Set.of("questId", "questVersion", "challengeId", "challengeVersion"));
        if (Set.of(
                        "practice.presentation.resume",
                        "practice.draft.read",
                        "practice.draft.save",
                        "practice.draft.status",
                        "practice.submissions",
                        "practice.submission.prepare",
                        "practice.submission.status",
                        "practice.submission.resume",
                        "practice.submission.cancel",
                        "practice.submission.debrief")
                .contains(method)) keys.add("presentationId");
        if (method.equals("practice.draft.save"))
            keys.addAll(Set.of("expectedRevision", "source", "reflection"));
        if (method.equals("practice.submission.prepare"))
            keys.addAll(Set.of("expectedRevision", "assistance"));
        if (!Set.of(
                                "practice.list",
                                "practice.status",
                                "practice.open",
                                "practice.presentations",
                                "practice.presentation.resume",
                                "practice.draft.read",
                                "practice.draft.save",
                                "practice.draft.status",
                                "practice.submissions",
                                "practice.submission.prepare",
                                "practice.submission.status",
                                "practice.submission.resume",
                                "practice.submission.cancel",
                                "practice.submission.debrief")
                        .contains(method)
                || !keys.equals(values.keySet())
                || !ApplicationProtocol.uuid(id)
                || !ApplicationProtocol.uuid(str(values, "profileId"))
                || !ApplicationProtocol.uuid(str(values, "sessionId")))
            throw new IOException("INVALID_INPUT");
        String command = values.containsKey("commandId") ? str(values, "commandId") : null;
        if (command != null && !ApplicationProtocol.uuid(command))
            throw new IOException("INVALID_INPUT");
        String presentation =
                values.containsKey("presentationId") ? str(values, "presentationId") : null;
        if (presentation != null
                && (!presentation.startsWith("desktop-practice-")
                        || !ApplicationProtocol.uuid(presentation.substring(17))))
            throw new IOException("INVALID_INPUT");
        long revision = 0;
        if (method.equals("practice.draft.save") || method.equals("practice.submission.prepare")) {
            if (!(values.get("expectedRevision") instanceof Long n)
                    || n < 0
                    || n >= 9007199254740000L) throw new IOException("INVALID_INPUT");
            revision = n;
            if (method.equals("practice.draft.save") && str(values, "reflection").length() > 8000)
                throw new IOException("INVALID_INPUT");
        }
        if (method.equals("practice.submission.prepare")
                && !Set.of("UNKNOWN", "NONE_DECLARED", "DECLARED")
                        .contains(str(values, "assistance")))
            throw new IOException("INVALID_INPUT");
        return new Request(
                id,
                method,
                str(values, "profileId"),
                str(values, "sessionId"),
                command,
                method.equals("practice.open") ? ref(values, "quest") : null,
                method.equals("practice.open") ? ref(values, "challenge") : null,
                presentation,
                revision,
                values.containsKey("source") ? str(values, "source") : null,
                values.containsKey("reflection") ? str(values, "reflection") : null,
                values.containsKey("assistance") ? str(values, "assistance") : null);
    }

    private static String str(Map<String, Object> v, String k) throws IOException {
        if (!(v.get(k) instanceof String s)) throw new IOException("INVALID_INPUT");
        return s;
    }

    private static CatalogRef ref(Map<String, Object> v, String prefix) throws IOException {
        if (!(v.get(prefix + "Version") instanceof Long n) || n < 1 || n > 10000)
            throw new IOException("INVALID_INPUT");
        return new CatalogRef(str(v, prefix + "Id"), n.intValue());
    }

    interface Writer {
        void write(JsonGenerator g) throws IOException;
    }

    static byte[] message(Writer writer) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeNumberField("v", 1);
            writer.write(g);
            g.writeEndObject();
        }
        if (out.size() > 524288) throw new IOException("RESPONSE_LIMIT");
        return out.toByteArray();
    }

    static void evaluation(JsonGenerator g, PracticeEvaluationService.Result r) throws IOException {
        if (r == null) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        g.writeStringField("commandId", r.commandId());
        g.writeStringField("presentationId", r.presentationId());
        g.writeNumberField("draftRevision", r.draftRevision());
        if (r.evaluationRevision() == null) g.writeNullField("evaluationRevision");
        else g.writeNumberField("evaluationRevision", r.evaluationRevision());
        g.writeStringField("assistance", r.assistance());
        g.writeStringField("challengeId", r.challengeId());
        g.writeNumberField("challengeVersion", r.challengeVersion());
        g.writeStringField("submittedAt", r.submittedAt());
        g.writeStringField("artifactSha256", r.artifactSha256());
        g.writeBooleanField("active", r.active());
        g.writeBooleanField("cancellationRequested", r.cancellationRequested());
        g.writeStringField("stage", r.view().stage().name());
        g.writeStringField(
                "outcome", r.view().outcome() == null ? null : r.view().outcome().name());
        g.writeBooleanField("withdrawn", r.view().withdrawn());
        g.writeArrayFieldStart("checks");
        for (var check : r.view().checks()) {
            g.writeStartObject();
            g.writeStringField("id", check.id());
            g.writeStringField("status", check.status().name());
            g.writeStringField("feedback", check.feedback());
            g.writeEndObject();
        }
        g.writeEndArray();
        g.writeEndObject();
    }

    static void draft(JsonGenerator g, PracticeDraftService.Draft d) throws IOException {
        g.writeObjectFieldStart("draft");
        g.writeStringField("presentationId", d.presentationId());
        g.writeNumberField("revision", d.revision());
        g.writeStringField("source", d.source());
        g.writeStringField("reflection", d.reflection());
        g.writeEndObject();
    }

    static void saved(JsonGenerator g, PracticeDraftService.Saved r) throws IOException {
        g.writeFieldName("saved");
        if (r == null) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        g.writeStringField("commandId", r.commandId());
        g.writeStringField("presentationId", r.presentationId());
        g.writeStringField("digest", r.digest());
        g.writeNumberField("expectedRevision", r.expectedRevision());
        g.writeNumberField("revision", r.revision());
        g.writeStringField("outcome", r.outcome());
        g.writeEndObject();
    }

    static void receipt(JsonGenerator g, PracticeApplicationService.Receipt r) throws IOException {
        if (r == null) {
            g.writeNull();
            return;
        }
        g.writeStartObject();
        g.writeStringField("commandId", r.commandId());
        g.writeStringField("presentationId", r.presentationId());
        g.writeStringField("questId", r.questId());
        g.writeNumberField("questVersion", r.questVersion());
        g.writeStringField("challengeId", r.challengeId());
        g.writeNumberField("challengeVersion", r.challengeVersion());
        g.writeStringField("presentedAt", r.presentedAt());
        g.writeEndObject();
    }
}

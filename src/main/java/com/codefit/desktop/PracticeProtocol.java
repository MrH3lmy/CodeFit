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
                                    .maxStringLength(128)
                                    .maxNumberLength(10)
                                    .build())
                    .build();

    record Request(
            String id,
            String method,
            String profile,
            String session,
            String command,
            CatalogRef quest,
            CatalogRef challenge) {}

    static Request parse(byte[] frame) throws IOException {
        if (frame.length > 4096) throw new IOException("INVALID_INPUT");
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
        if (Set.of("practice.status", "practice.open").contains(method)) keys.add("commandId");
        if (method.equals("practice.open"))
            keys.addAll(Set.of("questId", "questVersion", "challengeId", "challengeVersion"));
        if (!Set.of("practice.list", "practice.status", "practice.open").contains(method)
                || !keys.equals(values.keySet())
                || !ApplicationProtocol.uuid(id)
                || !ApplicationProtocol.uuid(str(values, "profileId"))
                || !ApplicationProtocol.uuid(str(values, "sessionId")))
            throw new IOException("INVALID_INPUT");
        String command = values.containsKey("commandId") ? str(values, "commandId") : null;
        if (command != null && !ApplicationProtocol.uuid(command))
            throw new IOException("INVALID_INPUT");
        return new Request(
                id,
                method,
                str(values, "profileId"),
                str(values, "sessionId"),
                command,
                method.equals("practice.open") ? ref(values, "quest") : null,
                method.equals("practice.open") ? ref(values, "challenge") : null);
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
        if (out.size() > 65536) throw new IOException("RESPONSE_LIMIT");
        return out.toByteArray();
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

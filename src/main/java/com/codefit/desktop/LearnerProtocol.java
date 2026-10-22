package com.codefit.desktop;

import com.fasterxml.jackson.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Version 3 capability: exactly one read operation, bound to capture session and source identity.
 */
final class LearnerProtocol {
    private static final JsonFactory JSON =
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(2)
                                    .maxStringLength(128)
                                    .maxNumberLength(2)
                                    .build())
                    .build();

    record Request(String id, String profileId, String sessionId) {}

    static Request parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 1024) throw new IOException("INVALID_INPUT");
        Map<String, String> fields = new HashMap<>();
        try (var p =
                JSON.createParser(
                        StandardCharsets.UTF_8
                                .newDecoder()
                                .decode(ByteBuffer.wrap(bytes))
                                .toString())) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("INVALID_INPUT");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME)
                    throw new IOException("INVALID_INPUT");
                String name = p.currentName();
                var token = p.nextToken();
                if (name.equals("v")
                        ? token != JsonToken.VALUE_NUMBER_INT
                        : token != JsonToken.VALUE_STRING) throw new IOException("INVALID_INPUT");
                fields.put(name, p.getText());
            }
            if (p.nextToken() != null) throw new IOException("INVALID_INPUT");
        }
        if (!fields.keySet().equals(Set.of("v", "id", "method", "profileId", "sessionId"))
                || !"3".equals(fields.get("v"))
                || !"learner.overview.read".equals(fields.get("method"))
                || !ApplicationProtocol.uuid(fields.get("id"))
                || !ApplicationProtocol.uuid(fields.get("sessionId"))
                || !fields.get("profileId").matches("[0-9a-f]{64}"))
            throw new IOException("INVALID_INPUT");
        return new Request(fields.get("id"), fields.get("profileId"), fields.get("sessionId"));
    }

    static byte[] readFrame(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int b; (b = input.read()) != -1; ) {
            if (b == '\n') return bytes.toByteArray();
            if (bytes.size() >= 1024) throw new IOException("INVALID_INPUT");
            bytes.write(b);
        }
        if (bytes.size() != 0) throw new IOException("INVALID_INPUT");
        return null;
    }

    interface Writer {
        void write(JsonGenerator j) throws IOException;
    }

    private static byte[] frame(Writer writer) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var j = JSON.createGenerator(bytes)) {
            j.writeStartObject();
            j.writeNumberField("v", 3);
            writer.write(j);
            j.writeEndObject();
        }
        if (bytes.size() > 8192) throw new IOException("READ_FAILED");
        return bytes.toByteArray();
    }

    static byte[] ready(LearnerSnapshot snapshot) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("type", "ready");
                    j.writeStringField("profileId", snapshot.profileId());
                    j.writeStringField("sessionId", snapshot.sessionId());
                });
    }

    static byte[] startupFailed(String code) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("type", "startup-error");
                    j.writeStringField("code", code);
                });
    }

    static byte[] failed(String id, String code) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", false);
                    j.writeStringField("code", code);
                });
    }

    static byte[] result(String id, LearnerReadProjection.Overview o) throws IOException {
        return frame(
                j -> {
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", true);
                    j.writeObjectFieldStart("result");
                    j.writeNumberField("v", 1);
                    j.writeStringField("kind", "learner-overview");
                    j.writeStringField("source", "learner-snapshot");
                    j.writeStringField("profileId", o.profileId());
                    j.writeStringField("sessionId", o.sessionId());
                    j.writeStringField("capturedAt", o.capturedAt().toString());
                    j.writeNumberField("schemaVersion", 25);
                    j.writeBooleanField("readOnly", true);
                    j.writeObjectFieldStart("identity");
                    j.writeStringField(
                            "status", o.displayName() == null ? "unavailable" : "available");
                    j.writeStringField("displayName", o.displayName());
                    j.writeEndObject();
                    j.writeObjectFieldStart("progression");
                    j.writeStringField("status", o.xp() == null ? "unavailable" : "available");
                    if (o.xp() == null) {
                        j.writeNullField("xp");
                        j.writeNullField("level");
                    } else {
                        j.writeNumberField("xp", o.xp());
                        j.writeNumberField("level", o.level());
                    }
                    j.writeEndObject();
                    j.writeObjectFieldStart("activity");
                    j.writeNumberField("reviewCount", o.reviewCount());
                    j.writeNumberField("dueScheduledCards", o.dueScheduledCards());
                    j.writeStringField("reviewDay", o.reviewDay());
                    j.writeEndObject();
                    for (String key : List.of("mastery", "recommendations", "missions")) {
                        j.writeObjectFieldStart(key);
                        j.writeStringField("status", "unavailable");
                        j.writeEndObject();
                    }
                    j.writeEndObject();
                });
    }
}

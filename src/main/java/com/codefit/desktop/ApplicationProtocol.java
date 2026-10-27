package com.codefit.desktop;

import com.codefit.model.Deck;
import com.fasterxml.jackson.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Protocol v2 is a finite command matrix. Strict fields, types, UTF-8, bounds and version at Java.
 */
final class ApplicationProtocol {
    static final int MAX_REQUEST_BYTES = 16384;
    static final int MAX_RESPONSE_BYTES = 65536;
    private static final JsonFactory JSON =
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(2)
                                    .maxStringLength(2048)
                                    .maxNumberLength(16)
                                    .build())
                    .build();

    record Request(
            String id,
            String method,
            long afterId,
            String profileId,
            String commandId,
            String name,
            String description) {}

    static boolean uuid(String value) {
        return value != null
                && value.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    }

    static boolean validText(String value, int limit) {
        if (value == null || value.length() > limit) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isISOControl(ch)) return false;
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    return false;
            } else if (Character.isLowSurrogate(ch)) return false;
        }
        return true;
    }

    static Request parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES)
            throw new IOException("INVALID_REQUEST");
        // Jackson accepts escaped surrogate code units; field validation below rejects unmatched
        // ones.
        Map<String, Object> fields = new HashMap<>();
        String utf8 = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonParser p = JSON.createParser(utf8)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("INVALID_REQUEST");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME)
                    throw new IOException("INVALID_REQUEST");
                String key = p.currentName();
                JsonToken token = p.nextToken();
                Object value =
                        switch (token) {
                            case VALUE_STRING -> p.getText();
                            case VALUE_NUMBER_INT -> p.getLongValue();
                            default -> throw new IOException("INVALID_REQUEST");
                        };
                if (fields.put(key, value) != null) throw new IOException("INVALID_REQUEST");
            }
            if (p.nextToken() != null) throw new IOException("INVALID_REQUEST");
        }
        if (!Long.valueOf(2).equals(fields.get("v")) || !uuid(string(fields, "id")))
            throw new IOException("INVALID_REQUEST");
        String method = string(fields, "method");
        Set<String> expected =
                switch (method) {
                    case "state.read" -> Set.of("v", "id", "method", "afterId");
                    case "commands.status" -> Set.of("v", "id", "method", "profileId", "commandId");
                    case "decks.create" ->
                            Set.of(
                                    "v",
                                    "id",
                                    "method",
                                    "profileId",
                                    "commandId",
                                    "name",
                                    "description");
                    default -> throw new IOException("INVALID_REQUEST");
                };
        if (!fields.keySet().equals(expected)) throw new IOException("INVALID_REQUEST");
        long afterId = 0;
        if (method.equals("state.read")) {
            if (!(fields.get("afterId") instanceof Long n) || n < 0 || n > 9007199254740991L)
                throw new IOException("INVALID_REQUEST");
            afterId = (Long) fields.get("afterId");
        } else if (!uuid(string(fields, "profileId")) || !uuid(string(fields, "commandId")))
            throw new IOException("INVALID_REQUEST");
        if (method.equals("decks.create")
                && (!validText(string(fields, "name"), 256)
                        || string(fields, "name").isBlank()
                        || !validText(string(fields, "description"), 2048)))
            throw new IOException("INVALID_REQUEST");
        return new Request(
                string(fields, "id"),
                method,
                afterId,
                string(fields, "profileId"),
                string(fields, "commandId"),
                string(fields, "name"),
                string(fields, "description"));
    }

    private static String string(Map<String, Object> fields, String key) throws IOException {
        Object value = fields.get(key);
        if (value == null) return "";
        if (!(value instanceof String text)) throw new IOException("INVALID_REQUEST");
        return text;
    }

    static byte[] readFrame(InputStream input) throws IOException {
        return readFrame(input, MAX_REQUEST_BYTES);
    }

    static byte[] readFrame(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1; ) {
            if (value == '\n') return frame.toByteArray();
            if (frame.size() == limit) throw new IOException("REQUEST_TOO_LARGE");
            frame.write(value);
        }
        if (frame.size() != 0) throw new IOException("INCOMPLETE_REQUEST");
        return null;
    }

    private interface Writer {
        void write(JsonGenerator json) throws IOException;
    }

    private static byte[] frame(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JsonGenerator j = JSON.createGenerator(bytes)) {
            writer.write(j);
        }
        if (bytes.size() > MAX_RESPONSE_BYTES) throw new IOException("RESULT_TOO_LARGE");
        return bytes.toByteArray();
    }

    static byte[] ready(String profileId) throws IOException {
        return frame(
                j -> {
                    j.writeStartObject();
                    j.writeNumberField("v", 2);
                    j.writeStringField("type", "ready");
                    j.writeStringField("profileId", profileId);
                    j.writeEndObject();
                });
    }

    static byte[] startupFailed(String code) throws IOException {
        return frame(
                j -> {
                    j.writeStartObject();
                    j.writeNumberField("v", 2);
                    j.writeStringField("type", "startup-error");
                    j.writeStringField("code", code);
                    j.writeEndObject();
                });
    }

    static byte[] failed(String id, String code) throws IOException {
        return frame(
                j -> {
                    j.writeStartObject();
                    j.writeNumberField("v", 2);
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", false);
                    j.writeStringField("code", code);
                    j.writeEndObject();
                });
    }

    static byte[] result(String id, Object result) throws IOException {
        return frame(
                j -> {
                    j.writeStartObject();
                    j.writeNumberField("v", 2);
                    j.writeStringField("id", id);
                    j.writeBooleanField("ok", true);
                    j.writeObjectFieldStart("result");
                    if (result instanceof DesktopApplicationService.State state) {
                        j.writeStringField("kind", "state");
                        j.writeStringField("source", "experimental-profile");
                        j.writeStringField("profileId", state.profileId());
                        j.writeBooleanField("readOnly", true);
                        j.writeObjectFieldStart("progression");
                        j.writeStringField("status", "unsupported");
                        j.writeEndObject();
                        j.writeArrayFieldStart("decks");
                        for (Deck deck : state.decks()) deck(j, deck);
                        j.writeEndArray();
                        if (state.nextAfterId() == null) j.writeNullField("nextAfterId");
                        else j.writeNumberField("nextAfterId", state.nextAfterId());
                    } else if (result instanceof DesktopApplicationService.Receipt receipt) {
                        receipt(j, receipt);
                    } else if (result instanceof DesktopApplicationService.CommandStatus status) {
                        j.writeStringField("kind", "command-status");
                        j.writeStringField("commandId", status.commandId());
                        j.writeFieldName("receipt");
                        if (status.receipt() == null) j.writeNull();
                        else {
                            j.writeStartObject();
                            receipt(j, status.receipt());
                            j.writeEndObject();
                        }
                    } else throw new IOException("INVALID_RESULT");
                    j.writeEndObject();
                    j.writeEndObject();
                });
    }

    private static void receipt(JsonGenerator j, DesktopApplicationService.Receipt receipt)
            throws IOException {
        j.writeStringField("kind", "receipt");
        j.writeStringField("commandId", receipt.commandId());
        j.writeStringField("status", receipt.status());
        if (receipt.status().equals("created")) {
            j.writeFieldName("deck");
            deck(j, receipt.deck());
        } else if (receipt.status().equals("rejected")) j.writeStringField("code", "NAME_EXISTS");
        else throw new IOException("INVALID_RESULT");
    }

    private static void deck(JsonGenerator j, Deck deck) throws IOException {
        if (deck.getId() < 1
                || deck.getId() > 9007199254740991L
                || !validText(deck.getName(), 256)
                || deck.getName().isBlank()
                || !validText(deck.getDescription(), 2048)) throw new IOException("INVALID_RESULT");
        j.writeStartObject();
        j.writeNumberField("id", deck.getId());
        j.writeStringField("name", deck.getName());
        j.writeStringField("description", deck.getDescription());
        j.writeEndObject();
    }

    static void write(OutputStream output, byte[] frame) throws IOException {
        output.write(frame);
        output.write('\n');
        output.flush();
    }
}

package com.codefit.desktop;

import com.codefit.model.Deck;
import com.fasterxml.jackson.core.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Version 1 is deliberately one read-only use case, not a reflection/RPC dispatcher. */
final class DesktopProtocol {
    static final int MAX_REQUEST_BYTES = 1024;
    static final int MAX_RESPONSE_BYTES = 65536;
    private static final JsonFactory JSON =
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(4)
                                    .maxStringLength(128)
                                    .maxNumberLength(10)
                                    .build())
                    .build();

    record Request(String id) {}

    static Request parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_REQUEST_BYTES)
            throw new IOException("INVALID_REQUEST");
        String id = null, method = null;
        Integer version = null;
        Set<String> fields = new HashSet<>();
        try (JsonParser parser = JSON.createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT)
                throw new IOException("INVALID_REQUEST");
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME)
                    throw new IOException("INVALID_REQUEST");
                String key = parser.currentName();
                if (!fields.add(key)) throw new IOException("INVALID_REQUEST");
                JsonToken token = parser.nextToken();
                switch (key) {
                    case "v" -> {
                        if (token != JsonToken.VALUE_NUMBER_INT)
                            throw new IOException("INVALID_REQUEST");
                        version = parser.getIntValue();
                    }
                    case "id" -> {
                        if (token != JsonToken.VALUE_STRING)
                            throw new IOException("INVALID_REQUEST");
                        id = parser.getText();
                    }
                    case "method" -> {
                        if (token != JsonToken.VALUE_STRING)
                            throw new IOException("INVALID_REQUEST");
                        method = parser.getText();
                    }
                    default -> throw new IOException("INVALID_REQUEST");
                }
            }
            if (parser.nextToken() != null
                    || !Integer.valueOf(1).equals(version)
                    || id == null
                    || !id.matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                    || !"decks.list".equals(method)) throw new IOException("INVALID_REQUEST");
        }
        return new Request(id);
    }

    /** Returns null on clean EOF; a partial frame is never interpreted as a complete command. */
    static byte[] readFrame(InputStream input) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1; ) {
            if (value == '\n') return frame.toByteArray();
            if (frame.size() == MAX_REQUEST_BYTES) throw new IOException("REQUEST_TOO_LARGE");
            frame.write(value);
        }
        if (frame.size() != 0) throw new IOException("INCOMPLETE_REQUEST");
        return null;
    }

    static byte[] decks(String id, List<Deck> decks) throws IOException {
        if (decks.size() > 64) throw new IOException("RESULT_TOO_LARGE");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JsonGenerator json = JSON.createGenerator(output)) {
            json.writeStartObject();
            json.writeNumberField("v", 1);
            json.writeStringField("id", id);
            json.writeBooleanField("ok", true);
            json.writeObjectFieldStart("result");
            json.writeStringField("source", "isolated-seed");
            json.writeBooleanField("readOnly", true);
            json.writeArrayFieldStart("decks");
            for (Deck deck : decks) {
                if (deck.getId() <= 0
                        || deck.getId() > 9007199254740991L
                        || deck.getName() == null
                        || deck.getName().length() > 256
                        || deck.getDescription() == null
                        || deck.getDescription().length() > 2048)
                    throw new IOException("INVALID_RESULT");
                json.writeStartObject();
                json.writeNumberField("id", deck.getId());
                json.writeStringField("name", deck.getName());
                json.writeStringField("description", deck.getDescription());
                json.writeEndObject();
            }
            json.writeEndArray();
            json.writeEndObject();
            json.writeEndObject();
        }
        if (output.size() > MAX_RESPONSE_BYTES) throw new IOException("RESULT_TOO_LARGE");
        return output.toByteArray();
    }

    static byte[] failed(String id) {
        // id has already passed the UUID grammar; errors never include paths or source data.
        return ("{\"v\":1,\"id\":\"" + id + "\",\"ok\":false,\"code\":\"READ_FAILED\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    static void write(OutputStream output, byte[] frame) throws IOException {
        output.write(frame);
        output.write('\n');
        output.flush();
    }
}

package com.codefit.desktop;

import com.codefit.knowledge.KnowledgePreview;
import com.codefit.knowledge.KnowledgePreviewCli;
import com.codefit.knowledge.PreviewFailure;
import com.fasterxml.jackson.core.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** One request, one pinned public metadata response, no profile or database capability. */
public final class CurriculumPreviewMain {
    static final int MAX_REQUEST = 256;
    static final int MAX_RESPONSE = 32768;
    private static final JsonFactory JSON =
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(
                            StreamReadConstraints.builder()
                                    .maxNestingDepth(1)
                                    .maxStringLength(64)
                                    .maxNumberLength(2)
                                    .build())
                    .build();

    private CurriculumPreviewMain() {}

    static String request(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != '\n') {
            if (b < 0 || bytes.size() >= MAX_REQUEST) throw new IOException("INVALID_REQUEST");
            bytes.write(b);
        }
        String text =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(ByteBuffer.wrap(bytes.toByteArray()))
                        .toString();
        Map<String, Object> fields = new HashMap<>();
        try (JsonParser p = JSON.createParser(text)) {
            if (p.nextToken() != JsonToken.START_OBJECT) throw new IOException("INVALID_REQUEST");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                if (p.currentToken() != JsonToken.FIELD_NAME)
                    throw new IOException("INVALID_REQUEST");
                String key = p.currentName();
                var token = p.nextToken();
                Object value;
                if (token == JsonToken.VALUE_STRING) value = p.getText();
                else if (token == JsonToken.VALUE_NUMBER_INT) value = p.getLongValue();
                else throw new IOException("INVALID_REQUEST");
                fields.put(key, value);
            }
            if (p.nextToken() != null) throw new IOException("INVALID_REQUEST");
        }
        if (!fields.keySet().equals(Set.of("v", "id", "method"))
                || !Long.valueOf(1).equals(fields.get("v"))
                || !(fields.get("id") instanceof String id)
                || !ApplicationProtocol.uuid(id)
                || !"curriculum.preview.read".equals(fields.get("method")))
            throw new IOException("INVALID_REQUEST");
        return (String) fields.get("id");
    }

    static byte[] response(String id) throws IOException {
        byte[] preview;
        try {
            preview = KnowledgePreviewCli.export(KnowledgePreview.loadBundled());
        } catch (PreviewFailure failure) {
            return failure(id);
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JsonGenerator j = JSON.createGenerator(bytes)) {
            j.writeStartObject();
            j.writeNumberField("v", 1);
            j.writeStringField("id", id);
            j.writeBooleanField("ok", true);
            j.writeObjectFieldStart("result");
            j.writeNumberField("v", 1);
            j.writeStringField("kind", "author-draft-curriculum-preview");
            j.writeStringField("source", "bundled-author-metadata");
            j.writeBooleanField("learnerBound", false);
            j.writeFieldName("preview");
            j.writeRawValue(new String(preview, StandardCharsets.UTF_8));
            j.writeEndObject();
            j.writeEndObject();
        }
        if (bytes.size() > MAX_RESPONSE) throw new IOException("RESULT_TOO_LARGE");
        return bytes.toByteArray();
    }

    private static byte[] failure(String id) {
        // id is accepted only after canonical UUID validation; no exception details cross stdout.
        return ("{\"v\":1,\"id\":\"" + id + "\",\"ok\":false,\"code\":\"INVALID_CATALOG\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        if (Runtime.version().feature() != 21 || args.length != 2 || !args[0].equals("--parent")) {
            System.exit(2);
            return;
        }
        ProcessHandle parent;
        try {
            parent = ProcessHandle.current().parent().orElseThrow();
            if (parent.pid() != Long.parseLong(args[1]) || !parent.isAlive())
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            System.exit(2);
            return;
        }
        var watchdog =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "curriculum-preview-watchdog");
                            t.setDaemon(true);
                            return t;
                        });
        watchdog.scheduleWithFixedDelay(
                () -> {
                    if (!parent.isAlive()) Runtime.getRuntime().halt(0);
                },
                200,
                200,
                TimeUnit.MILLISECONDS);
        watchdog.schedule(() -> Runtime.getRuntime().halt(3), 15, TimeUnit.SECONDS);
        try {
            byte[] response = response(request(System.in));
            System.out.write(response);
            System.out.write('\n');
            System.out.flush();
        } catch (Exception failure) {
            System.err.println("CURRICULUM_READ_FAILED");
            System.exit(1);
        } finally {
            watchdog.shutdownNow();
        }
    }
}

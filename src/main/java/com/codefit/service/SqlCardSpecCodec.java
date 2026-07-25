package com.codefit.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes/decodes a {@link SqlCardSpec} into the same accepted-answers text column other card
 * types use for their accepted-answer strings (see {@link AcceptedAnswerCodec}). SQL_QUERY cards
 * no longer text-match a saved answer, so this column is repurposed to hold the fixture
 * schema/seed/reference-query configuration as a single-line, escaped JSON object rather than
 * introducing a parallel table or column.
 *
 * <p>The encoded string always starts with a curly brace and never contains a raw newline or tab
 * (values are escaped), so it round-trips unchanged through {@link AcceptedAnswerCodec#normalize}
 * (which only special-cases {@code [...]} JSON arrays and otherwise treats a single line as one
 * opaque answer) and through the TSV import/export format, which forbids raw tabs/newlines in a
 * field.</p>
 */
public final class SqlCardSpecCodec {

    private SqlCardSpecCodec() {
    }

    public static String encode(SqlCardSpec spec) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"schema\":").append(quoteOrNull(spec.schemaSql())).append(',');
        json.append("\"seed\":").append(quoteOrNull(spec.seedSql())).append(',');
        json.append("\"reference\":").append(quoteOrNull(spec.referenceQuery())).append(',');
        json.append("\"expectedError\":").append(quoteOrNull(spec.expectedError())).append(',');
        json.append("\"orderMatters\":").append(spec.orderMatters()).append(',');
        json.append("\"allowDdl\":").append(spec.allowControlledDdl()).append(',');
        json.append("\"timeoutMillis\":").append(spec.timeoutMillis());
        return json.append('}').toString();
    }

    /** @throws IllegalArgumentException if {@code stored} is not a decodable SQL card configuration */
    public static SqlCardSpec decode(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("This card has no SQL fixture configuration.");
        }
        Map<String, String> fields = parseFlatJsonObject(stored.strip());
        String schema = fields.getOrDefault("schema", "");
        String seed = fields.getOrDefault("seed", "");
        String reference = fields.get("reference");
        String expectedError = fields.get("expectedError");
        boolean orderMatters = Boolean.parseBoolean(fields.get("orderMatters"));
        boolean allowDdl = Boolean.parseBoolean(fields.get("allowDdl"));
        int timeoutMillis = parseIntOrDefault(fields.get("timeoutMillis"), SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);
        boolean noReference = reference == null || reference.isBlank();
        boolean noExpectedError = expectedError == null || expectedError.isBlank();
        if (noReference && noExpectedError) {
            throw new IllegalArgumentException("This card must configure a reference query or an expected error.");
        }
        return new SqlCardSpec(schema, seed, reference, expectedError, orderMatters, allowDdl, timeoutMillis);
    }

    private static int parseIntOrDefault(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static String quoteOrNull(String value) {
        return value == null ? "null" : quote(value);
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    /**
     * Minimal flat-object parser: only string/boolean/number/null values are supported (no nested
     * objects or arrays), which is all {@link #encode} ever produces. Field order is not assumed.
     */
    private static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        int length = json.length();
        int index = json.indexOf('{');
        if (index < 0) {
            throw new IllegalArgumentException("Not a valid SQL card configuration.");
        }
        index++;
        while (true) {
            index = skipWhitespace(json, index, length);
            if (index >= length) {
                throw new IllegalArgumentException("Unterminated SQL card configuration.");
            }
            if (json.charAt(index) == '}') {
                return result;
            }
            if (json.charAt(index) != '"') {
                throw new IllegalArgumentException("Expected a field name in SQL card configuration.");
            }
            StringBuilder key = new StringBuilder();
            index = parseString(json, index, length, key);
            index = skipWhitespace(json, index, length);
            if (index >= length || json.charAt(index) != ':') {
                throw new IllegalArgumentException("Expected ':' in SQL card configuration.");
            }
            index = skipWhitespace(json, index + 1, length);
            String value;
            if (index < length && json.charAt(index) == '"') {
                StringBuilder valueBuilder = new StringBuilder();
                index = parseString(json, index, length, valueBuilder);
                value = valueBuilder.toString();
            } else {
                int start = index;
                while (index < length && json.charAt(index) != ',' && json.charAt(index) != '}') {
                    index++;
                }
                String literal = json.substring(start, index).strip();
                value = literal.equals("null") ? null : literal;
            }
            result.put(key.toString(), value);
            index = skipWhitespace(json, index, length);
            if (index < length && json.charAt(index) == ',') {
                index++;
            }
        }
    }

    private static int skipWhitespace(String json, int index, int length) {
        while (index < length && Character.isWhitespace(json.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int parseString(String json, int start, int length, StringBuilder out) {
        int index = start + 1;
        while (index < length) {
            char c = json.charAt(index);
            if (c == '"') {
                return index + 1;
            }
            if (c == '\\') {
                index++;
                if (index >= length) {
                    throw new IllegalArgumentException("Invalid escape in SQL card configuration.");
                }
                char escaped = json.charAt(index);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (index + 4 >= length) {
                            throw new IllegalArgumentException("Invalid unicode escape in SQL card configuration.");
                        }
                        out.append((char) Integer.parseInt(json.substring(index + 1, index + 5), 16));
                        index += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape in SQL card configuration.");
                }
                index++;
            } else {
                out.append(c);
                index++;
            }
        }
        throw new IllegalArgumentException("Unterminated string in SQL card configuration.");
    }
}

package com.codefit.service;

import com.codefit.model.RegexCardConfig;
import com.codefit.model.RegexCardFlag;
import com.codefit.model.RegexMatchMode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Encodes and decodes the {@link RegexCardConfig} for a {@link com.codefit.model.CardType#REGEX_PATTERN}
 * card as a single-line JSON object, e.g.
 * {@code {"mustMatch":["555-1234"],"mustNotMatch":["abc"],"flags":["CASE_INSENSITIVE"],"matchMode":"FULL_MATCH"}}.
 *
 * <p>It is stored in the same {@code acceptedAnswers} column every other card type already uses (see
 * {@link AcceptedAnswerCodec}), not a new column, so it round-trips through TSV import/export and the
 * existing single-value persistence path unchanged: {@link AcceptedAnswerCodec#decode} only special-cases
 * text starting with {@code [}, so a single-line JSON *object* passes through as one plain-text
 * alternative exactly as authored. Every example string is escaped the same way
 * {@link AcceptedAnswerCodec} escapes alternatives, so the encoded blob never contains a literal tab or
 * newline that would break a TSV row.</p>
 */
public final class RegexCardCodec {

    private static final RegexCardConfig EMPTY = new RegexCardConfig(List.of(), List.of(), Set.of(), RegexMatchMode.FIND);

    private RegexCardCodec() {
    }

    public static String encode(RegexCardConfig config) {
        RegexCardConfig safe = config == null ? EMPTY : config;
        return "{\"mustMatch\":" + encodeStringArray(safe.mustMatch())
                + ",\"mustNotMatch\":" + encodeStringArray(safe.mustNotMatch())
                + ",\"flags\":" + encodeStringArray(safe.flags().stream().map(Enum::name).toList())
                + ",\"matchMode\":\"" + safe.matchMode().name() + "\"}";
    }

    /** Never throws: a blank or malformed blob decodes to an empty, always-{@code MISCONFIGURED} config. */
    public static RegexCardConfig decode(String stored) {
        if (stored == null || stored.isBlank()) {
            return EMPTY;
        }
        try {
            return parse(stored.strip());
        } catch (RuntimeException malformed) {
            return EMPTY;
        }
    }

    private static RegexCardConfig parse(String json) {
        Cursor cursor = new Cursor(json);
        cursor.expect('{');
        List<String> mustMatch = List.of();
        List<String> mustNotMatch = List.of();
        Set<RegexCardFlag> flags = Set.of();
        RegexMatchMode matchMode = RegexMatchMode.FIND;

        cursor.skipWhitespace();
        if (cursor.peek() == '}') {
            cursor.next();
            return new RegexCardConfig(mustMatch, mustNotMatch, flags, matchMode);
        }
        while (true) {
            cursor.skipWhitespace();
            String key = cursor.readString();
            cursor.skipWhitespace();
            cursor.expect(':');
            cursor.skipWhitespace();
            switch (key) {
                case "mustMatch" -> mustMatch = cursor.readStringArray();
                case "mustNotMatch" -> mustNotMatch = cursor.readStringArray();
                case "flags" -> flags = toFlags(cursor.readStringArray());
                case "matchMode" -> matchMode = RegexMatchMode.valueOf(cursor.readString());
                default -> throw new IllegalArgumentException("Unknown regex card config key: " + key);
            }
            cursor.skipWhitespace();
            char next = cursor.next();
            if (next == '}') {
                break;
            }
            if (next != ',') {
                throw new IllegalArgumentException("Malformed regex card config near index " + cursor.index);
            }
        }
        return new RegexCardConfig(mustMatch, mustNotMatch, flags, matchMode);
    }

    private static Set<RegexCardFlag> toFlags(List<String> names) {
        Set<RegexCardFlag> flags = new LinkedHashSet<>();
        for (String name : names) {
            flags.add(RegexCardFlag.valueOf(name));
        }
        return flags;
    }

    private static String encodeStringArray(List<String> values) {
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                array.append(",");
            }
            array.append(quote(values.get(i)));
        }
        return array.append("]").toString();
    }

    private static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2);
        quoted.append('"');
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

    private static final class Cursor {
        private final String json;
        private int index;

        Cursor(String json) {
            this.json = json;
        }

        char peek() {
            return json.charAt(index);
        }

        char next() {
            return json.charAt(index++);
        }

        void expect(char expected) {
            skipWhitespace();
            char actual = next();
            if (actual != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' but found '" + actual + "'");
            }
        }

        void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        String readString() {
            skipWhitespace();
            expect('"');
            StringBuilder value = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return value.toString();
                }
                if (c == '\\') {
                    char escaped = next();
                    switch (escaped) {
                        case '"' -> value.append('"');
                        case '\\' -> value.append('\\');
                        case '/' -> value.append('/');
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> {
                            String hex = json.substring(index, index + 4);
                            value.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                        }
                        default -> throw new IllegalArgumentException("Unsupported escape \\" + escaped);
                    }
                } else {
                    value.append(c);
                }
            }
        }

        List<String> readStringArray() {
            skipWhitespace();
            expect('[');
            List<String> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                next();
                return values;
            }
            while (true) {
                values.add(readString());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return values;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("Expected ',' or ']' in array near index " + index);
                }
                skipWhitespace();
            }
        }
    }
}

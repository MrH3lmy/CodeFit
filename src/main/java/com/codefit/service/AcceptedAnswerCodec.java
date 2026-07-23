package com.codefit.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Encodes and decodes the accepted-answer alternatives stored on a {@link com.codefit.model.Flashcard}.
 *
 * <p>Multiple alternatives are stored as a compact JSON array of strings, e.g.
 * {@code ["@ControllerAdvice","@RestControllerAdvice"]}. A single alternative is stored as plain
 * text so existing single-answer cards remain unchanged. Legacy plain text (including multiline
 * text, one alternative per line) decodes the same way it always has, so old data keeps working
 * until it is migrated. Regex patterns containing {@code |} are plain text, not JSON, so they are
 * never split.
 */
public final class AcceptedAnswerCodec {

    private AcceptedAnswerCodec() {
    }

    public static List<String> decode(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        String trimmed = stored.strip();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            List<String> parsed = tryParseJsonStringArray(trimmed);
            if (parsed != null) {
                return parsed;
            }
        }
        return trimmed.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    public static String encode(List<String> answers) {
        Set<String> cleaned = new LinkedHashSet<>();
        if (answers != null) {
            for (String answer : answers) {
                if (answer != null && !answer.isBlank()) {
                    cleaned.add(answer.strip());
                }
            }
        }
        if (cleaned.isEmpty()) {
            return "";
        }
        if (cleaned.size() == 1) {
            return cleaned.iterator().next();
        }
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String answer : cleaned) {
            if (!first) {
                json.append(",");
            }
            json.append(quote(answer));
            first = false;
        }
        return json.append("]").toString();
    }

    /** Normalizes raw user/import input (plain text, one alternative per line) into the stored format. */
    public static String normalize(String rawInput) {
        return encode(decode(rawInput));
    }

    private static List<String> tryParseJsonStringArray(String json) {
        List<String> result = new ArrayList<>();
        int length = json.length() - 1;
        int index = 1;
        boolean expectValue = true;
        while (index < length) {
            char current = json.charAt(index);
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == ',') {
                if (expectValue) {
                    return null;
                }
                expectValue = true;
                index++;
                continue;
            }
            if (current != '"' || !expectValue) {
                return null;
            }
            int stringEnd = parseJsonString(json, index, length, result);
            if (stringEnd < 0) {
                return null;
            }
            index = stringEnd;
            expectValue = false;
        }
        if (expectValue && !result.isEmpty()) {
            return null;
        }
        return result;
    }

    private static int parseJsonString(String json, int start, int length, List<String> out) {
        StringBuilder value = new StringBuilder();
        int index = start + 1;
        while (index < length) {
            char c = json.charAt(index);
            if (c == '"') {
                out.add(value.toString());
                return index + 1;
            }
            if (c == '\\') {
                index++;
                if (index >= length) {
                    return -1;
                }
                char escaped = json.charAt(index);
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
                        if (index + 4 >= length) {
                            return -1;
                        }
                        try {
                            value.append((char) Integer.parseInt(json.substring(index + 1, index + 5), 16));
                        } catch (NumberFormatException exception) {
                            return -1;
                        }
                        index += 4;
                    }
                    default -> {
                        return -1;
                    }
                }
                index++;
            } else {
                value.append(c);
                index++;
            }
        }
        return -1;
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
}

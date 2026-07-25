package com.codefit.service;

import com.codefit.model.JavaCardConfig;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes and decodes {@link JavaCardConfig} into a single line stored in a
 * {@link com.codefit.model.CardType#JAVA_CODE} card's {@code acceptedAnswers} column, the same
 * column other card types use for their text answer key. Reusing that column (rather than adding
 * dedicated schema/TSV columns) means Java exercises round-trip through the existing add/update
 * card, TSV import/export, and database paths unchanged.
 *
 * <p>The template and expected output can contain arbitrary text, including newlines and tabs, so
 * both are Base64-encoded to keep the stored value a single line — required for TSV export, which
 * rejects fields containing a tab or newline (see {@code FlashcardImportExportService#toTsvRow}).
 */
public final class JavaExerciseCodec {

    private static final String PREFIX = "JAVA_EXERCISE_V1:";

    private JavaExerciseCodec() {
    }

    public static boolean isEncoded(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public static String encode(JavaCardConfig config) {
        String exceptionPart = config.expectedExceptionSimpleName() == null ? "" : config.expectedExceptionSimpleName();
        return PREFIX + config.timeoutSeconds() + ":" + config.memoryLimitMb() + ":" + base64(exceptionPart)
                + ":" + base64(config.expectedOutput() == null ? "" : config.expectedOutput())
                + ":" + base64(config.template());
    }

    public static JavaCardConfig decode(String stored) {
        if (!isEncoded(stored)) {
            throw new IllegalArgumentException("Not an encoded Java exercise.");
        }
        String[] parts = stored.substring(PREFIX.length()).split(":", 5);
        if (parts.length != 5) {
            throw new IllegalArgumentException("Malformed Java exercise encoding.");
        }
        int timeoutSeconds = Integer.parseInt(parts[0]);
        int memoryLimitMb = Integer.parseInt(parts[1]);
        String expectedException = unbase64(parts[2]);
        String expectedOutput = unbase64(parts[3]);
        String template = unbase64(parts[4]);
        return new JavaCardConfig(template, expectedOutput, expectedException.isBlank() ? null : expectedException,
                timeoutSeconds, memoryLimitMb);
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String unbase64(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
}

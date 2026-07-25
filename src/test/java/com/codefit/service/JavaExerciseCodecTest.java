package com.codefit.service;

import com.codefit.model.JavaCardConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaExerciseCodecTest {

    @Test
    void roundTripsTemplateAndExpectedOutput() {
        JavaCardConfig config = new JavaCardConfig(
                "public class Solution {\n public static void main(String[] a) {\n" + JavaCardConfig.SOLUTION_PLACEHOLDER + "\n}\n}",
                "42\n",
                null,
                5,
                128);

        String encoded = JavaExerciseCodec.encode(config);
        JavaCardConfig decoded = JavaExerciseCodec.decode(encoded);

        assertEquals(config.template(), decoded.template());
        assertEquals(config.expectedOutput(), decoded.expectedOutput());
        assertNull(decoded.expectedExceptionSimpleName());
        assertEquals(5, decoded.timeoutSeconds());
        assertEquals(128, decoded.memoryLimitMb());
    }

    @Test
    void roundTripsExpectedException() {
        JavaCardConfig config = new JavaCardConfig(
                "class Solution { void run() {" + JavaCardConfig.SOLUTION_PLACEHOLDER + "} }",
                null,
                "ArithmeticException",
                3,
                64);

        JavaCardConfig decoded = JavaExerciseCodec.decode(JavaExerciseCodec.encode(config));

        assertTrue(decoded.expectsException());
        assertEquals("ArithmeticException", decoded.expectedExceptionSimpleName());
    }

    @Test
    void encodedValueHasNoTabsOrNewlinesSoItSurvivesTsvExport() {
        JavaCardConfig config = new JavaCardConfig(
                "public class Solution {\n\tpublic static void main(String[] a) {\n" + JavaCardConfig.SOLUTION_PLACEHOLDER + "\n}\n}",
                "line one\nline two\twith a tab",
                null,
                5,
                128);

        String encoded = JavaExerciseCodec.encode(config);

        assertFalse(encoded.contains("\t"));
        assertFalse(encoded.contains("\n"));
        assertTrue(JavaExerciseCodec.isEncoded(encoded));
    }

    @Test
    void templateMustContainSolutionPlaceholder() {
        org.junit.jupiter.api.function.Executable construction = () ->
                new JavaCardConfig("class Solution {}", "", null, 5, 128);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, construction);
    }

    @Test
    void assembleSourceSubstitutesThePlaceholder() {
        JavaCardConfig config = new JavaCardConfig(
                "int x = " + JavaCardConfig.SOLUTION_PLACEHOLDER + ";",
                "", null, 5, 128);

        assertEquals("int x = 41 + 1;", config.assembleSource("41 + 1"));
    }
}

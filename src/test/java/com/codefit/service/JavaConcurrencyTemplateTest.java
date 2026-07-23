package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.ValidationMode;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaConcurrencyTemplateTest {

    private static final String HEADER =
            "front\tback\tcard_type\taccepted_answers\tvalidation_mode\thint\tskill_category\ttime_limit_seconds";

    private static final List<TemplateFile> TEMPLATE_FILES = List.of(
            new TemplateFile("/templates/java-concurrency-in-practice/01-fundamentals.tsv", 40),
            new TemplateFile("/templates/java-concurrency-in-practice/02-task-execution-cancellation.tsv", 40),
            new TemplateFile("/templates/java-concurrency-in-practice/03-liveness-performance-testing.tsv", 36),
            new TemplateFile("/templates/java-concurrency-in-practice/04-locks-atomics-memory-model.tsv", 44)
    );

    @Test
    void templatesContain160ValidUniqueCards() throws IOException {
        Set<String> normalizedPrompts = new HashSet<>();
        int totalCards = 0;

        for (TemplateFile templateFile : TEMPLATE_FILES) {
            int fileCards = validateTemplate(templateFile.path(), normalizedPrompts);
            assertEquals(templateFile.expectedCards(), fileCards,
                    () -> templateFile.path() + " should contain the documented number of cards");
            totalCards += fileCards;
        }

        assertEquals(160, totalCards);
        assertEquals(totalCards, normalizedPrompts.size());
    }

    private int validateTemplate(String resourcePath, Set<String> normalizedPrompts) throws IOException {
        InputStream stream = JavaConcurrencyTemplateTest.class.getResourceAsStream(resourcePath);
        assertNotNull(stream, () -> "Missing template resource " + resourcePath);

        int cardCount = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            assertEquals(HEADER, reader.readLine(), () -> "Unexpected header in " + resourcePath);

            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }

                String[] fields = line.split("\\t", -1);
                assertEquals(8, fields.length,
                        () -> resourcePath + ":" + lineNumber + " must have exactly eight TSV fields");

                String front = fields[0];
                String back = fields[1];
                String acceptedAnswers = fields[3];
                String hint = fields[5];
                String skillCategory = fields[6];
                String timeLimit = fields[7];

                assertFalse(front.isBlank(), () -> resourcePath + ":" + lineNumber + " has a blank prompt");
                assertFalse(back.isBlank(), () -> resourcePath + ":" + lineNumber + " has a blank answer");
                assertFalse(acceptedAnswers.isBlank(),
                        () -> resourcePath + ":" + lineNumber + " has blank accepted answers");
                assertFalse(hint.isBlank(), () -> resourcePath + ":" + lineNumber + " has a blank hint");
                assertTrue(skillCategory.startsWith("JCIP Ch"),
                        () -> resourcePath + ":" + lineNumber + " has an unexpected skill category");

                CardType.valueOf(fields[2]);
                ValidationMode.valueOf(fields[4]);
                assertFalse(AcceptedAnswerCodec.decode(acceptedAnswers).isEmpty(),
                        () -> resourcePath + ":" + lineNumber + " has undecodable accepted answers");

                int seconds = Integer.parseInt(timeLimit);
                assertTrue(seconds > 0,
                        () -> resourcePath + ":" + lineNumber + " must use a positive time limit");

                assertFalse(line.contains("\r"),
                        () -> resourcePath + ":" + lineNumber + " contains a carriage return");
                String normalizedPrompt = front.strip().toLowerCase(Locale.ROOT);
                assertTrue(normalizedPrompts.add(normalizedPrompt),
                        () -> "Duplicate prompt: " + front);
                cardCount++;
            }
        }
        return cardCount;
    }

    private record TemplateFile(String path, int expectedCards) {
    }
}

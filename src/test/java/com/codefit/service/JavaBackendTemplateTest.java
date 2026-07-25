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

/**
 * Validates the expanded Java Backend starter decks under
 * {@code src/main/resources/templates/java-be/} (issue #100). Every file must decode as a
 * well-formed extended TSV template: exactly eight tab-separated fields per row, only
 * supported {@link CardType} and {@link ValidationMode} enum values, non-blank prompts,
 * answers, hints, and skill categories, decodable accepted answers, positive time limits,
 * and no duplicate prompts within or across the modules.
 */
class JavaBackendTemplateTest {

    private static final String HEADER =
            "front\tback\tcard_type\taccepted_answers\tvalidation_mode\thint\tskill_category\ttime_limit_seconds";

    private static final List<TemplateFile> TEMPLATE_FILES = List.of(
            new TemplateFile("/templates/java-be/01-core-java.tsv", 20),
            new TemplateFile("/templates/java-be/02-collections-streams-generics.tsv", 20),
            new TemplateFile("/templates/java-be/03-jdbc-sql.tsv", 20),
            new TemplateFile("/templates/java-be/04-spring-boot-rest.tsv", 20),
            new TemplateFile("/templates/java-be/05-jpa-hibernate.tsv", 20),
            new TemplateFile("/templates/java-be/06-testing.tsv", 20),
            new TemplateFile("/templates/java-be/07-security.tsv", 20),
            new TemplateFile("/templates/java-be/08-deployment.tsv", 20)
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
        assertEquals(totalCards, normalizedPrompts.size(), "Prompts must be unique within and across every module");
    }

    private int validateTemplate(String resourcePath, Set<String> normalizedPrompts) throws IOException {
        InputStream stream = JavaBackendTemplateTest.class.getResourceAsStream(resourcePath);
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
                int currentLine = lineNumber;

                String[] fields = line.split("\\t", -1);
                assertEquals(8, fields.length,
                        () -> resourcePath + ":" + currentLine + " must have exactly eight TSV fields");

                String front = fields[0];
                String back = fields[1];
                String acceptedAnswers = fields[3];
                String hint = fields[5];
                String skillCategory = fields[6];
                String timeLimit = fields[7];

                assertFalse(front.isBlank(), () -> resourcePath + ":" + currentLine + " has a blank prompt");
                assertFalse(back.isBlank(), () -> resourcePath + ":" + currentLine + " has a blank answer");
                assertFalse(acceptedAnswers.isBlank(),
                        () -> resourcePath + ":" + currentLine + " has blank accepted answers");
                assertFalse(hint.isBlank(), () -> resourcePath + ":" + currentLine + " has a blank hint");
                assertFalse(skillCategory.isBlank(),
                        () -> resourcePath + ":" + currentLine + " has a blank skill category");

                CardType.valueOf(fields[2]);
                ValidationMode.valueOf(fields[4]);
                assertFalse(AcceptedAnswerCodec.decode(acceptedAnswers).isEmpty(),
                        () -> resourcePath + ":" + currentLine + " has undecodable accepted answers");

                int seconds = Integer.parseInt(timeLimit);
                assertTrue(seconds > 0,
                        () -> resourcePath + ":" + currentLine + " must use a positive time limit");

                assertFalse(line.contains("\r"),
                        () -> resourcePath + ":" + currentLine + " contains a carriage return");
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

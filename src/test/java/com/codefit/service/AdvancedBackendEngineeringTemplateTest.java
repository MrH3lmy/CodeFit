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
 * Validates the starter TSV decks for modules 2-10 of the Advanced Backend Engineering training
 * path (module 1 reuses and is already covered by {@link JavaConcurrencyTemplateTest}). Mirrors
 * that test's structure so both template sets are checked the same way.
 */
class AdvancedBackendEngineeringTemplateTest {

    private static final String HEADER =
            "front\tback\tcard_type\taccepted_answers\tvalidation_mode\thint\tskill_category\ttime_limit_seconds";
    private static final int MINIMUM_CARDS_PER_MODULE = 20;

    private static final List<TemplateFile> TEMPLATE_FILES = List.of(
            new TemplateFile("/templates/advanced-backend-engineering/02-database-transactions-locking-isolation.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/03-idempotency-race-condition-prevention.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/04-kafka-delivery-semantics-outbox-dlq.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/05-distributed-transactions-sagas.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/06-oauth2-oidc-service-authentication.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/07-caching-consistency-invalidation.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/08-observability-production-debugging.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/09-jvm-memory-gc-performance.tsv"),
            new TemplateFile("/templates/advanced-backend-engineering/10-api-database-failure-scenarios.tsv")
    );

    @Test
    void everyModuleTemplateHasAMeaningfulStarterDeckOfValidUniqueCards() throws IOException {
        Set<String> normalizedPrompts = new HashSet<>();
        int totalCards = 0;

        for (TemplateFile templateFile : TEMPLATE_FILES) {
            int fileCards = validateTemplate(templateFile.path(), normalizedPrompts);
            assertTrue(fileCards >= MINIMUM_CARDS_PER_MODULE,
                    () -> templateFile.path() + " should ship a real starter deck (at least "
                            + MINIMUM_CARDS_PER_MODULE + " cards), found " + fileCards);
            totalCards += fileCards;
        }

        // Every module's prompts are unique against every other module's prompts too, so the
        // combined path never presents the learner with a duplicate question across modules.
        assertEquals(totalCards, normalizedPrompts.size());
        int finalTotalCards = totalCards;
        assertTrue(finalTotalCards >= MINIMUM_CARDS_PER_MODULE * TEMPLATE_FILES.size(),
                () -> "expected a substantial starter curriculum, found only " + finalTotalCards + " cards total");
    }

    private int validateTemplate(String resourcePath, Set<String> normalizedPrompts) throws IOException {
        InputStream stream = AdvancedBackendEngineeringTemplateTest.class.getResourceAsStream(resourcePath);
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
                String cardType = fields[2];
                String acceptedAnswers = fields[3];
                String validationMode = fields[4];
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

                CardType.valueOf(cardType);
                ValidationMode.valueOf(validationMode);
                assertFalse(AcceptedAnswerCodec.decode(acceptedAnswers).isEmpty(),
                        () -> resourcePath + ":" + currentLine + " has undecodable accepted answers");

                int seconds = Integer.parseInt(timeLimit);
                assertTrue(seconds > 0,
                        () -> resourcePath + ":" + currentLine + " must use a positive time limit");

                assertFalse(line.contains("\r"),
                        () -> resourcePath + ":" + currentLine + " contains a carriage return");
                String normalizedPrompt = front.strip().toLowerCase(Locale.ROOT);
                assertTrue(normalizedPrompts.add(normalizedPrompt),
                        () -> "Duplicate prompt across advanced-backend-engineering templates: " + front);
                cardCount++;
            }
        }
        return cardCount;
    }

    private record TemplateFile(String path) {
    }
}

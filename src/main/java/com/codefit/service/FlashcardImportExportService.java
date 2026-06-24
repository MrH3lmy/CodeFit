package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FlashcardImportExportService {
    private static final int BASIC_FIELD_COUNT = 2;
    private static final int EXTENDED_FIELD_COUNT = 8;

    private final FlashcardService flashcardService;

    public FlashcardImportExportService() {
        this(new FlashcardService());
    }

    public FlashcardImportExportService(FlashcardService flashcardService) {
        this.flashcardService = flashcardService;
    }

    public void exportDeckToAnkiTsv(long deckId, Path outputPath) {
        validateDeckAndPath(deckId, outputPath);
        List<Flashcard> cards = flashcardService.getCardsForDeck(deckId);
        List<String> errors = new ArrayList<>();

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            for (int index = 0; index < cards.size(); index++) {
                Flashcard card = cards.get(index);
                List<String> fields = List.of(
                        field(card.getFront()),
                        field(card.getBack()),
                        card.getCardType().name(),
                        field(card.getAcceptedAnswers()),
                        card.getValidationMode().name(),
                        field(card.getHint()),
                        field(card.getSkillCategory()),
                        card.getTimeLimitSeconds() == null ? "" : card.getTimeLimitSeconds().toString()
                );
                try {
                    writer.write(toTsvRow(fields));
                    writer.newLine();
                } catch (IllegalArgumentException exception) {
                    errors.add("Card " + (index + 1) + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to export cards to " + outputPath, exception);
        }

        if (!errors.isEmpty()) {
            throw new ImportExportException("Some cards could not be exported because TSV fields cannot contain tabs or newlines.", errors);
        }
    }

    public ImportSummary importAnkiTsv(long deckId, Path inputPath) {
        validateDeckAndPath(deckId, inputPath);
        int imported = 0;
        int skippedDuplicates = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                if (lineNumber == 1 && isHeaderRow(line)) {
                    continue;
                }
                String[] fields = line.split("\\t", -1);
                if (fields.length != BASIC_FIELD_COUNT && fields.length != EXTENDED_FIELD_COUNT) {
                    errors.add("Line " + lineNumber + ": expected 2 or 8 tab-separated fields, found " + fields.length + ".");
                    continue;
                }
                try {
                    if (flashcardService.cardExistsInDeck(deckId, fields[0])) {
                        skippedDuplicates++;
                        continue;
                    }
                    flashcardService.addCard(
                            deckId,
                            fields[0],
                            fields[1],
                            parseEnum(CardType.class, get(fields, 2), CardType.RECALL, "card_type", lineNumber),
                            blankToDefault(get(fields, 3), fields[1]),
                            parseEnum(ValidationMode.class, get(fields, 4), ValidationMode.CASE_INSENSITIVE, "validation_mode", lineNumber),
                            null,
                            blankToNull(get(fields, 5)),
                            parseTimeLimit(get(fields, 7), lineNumber),
                            blankToNull(get(fields, 6))
                    );
                    imported++;
                } catch (RuntimeException exception) {
                    errors.add("Line " + lineNumber + ": " + exception.getMessage());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to import cards from " + inputPath, exception);
        }

        if (!errors.isEmpty()) {
            throw new ImportExportException(new ImportSummary(imported, skippedDuplicates, errors));
        }
        return new ImportSummary(imported, skippedDuplicates, errors);
    }

    private void validateDeckAndPath(long deckId, Path path) {
        if (deckId <= 0) {
            throw new IllegalArgumentException("Choose a deck before importing or exporting cards.");
        }
        if (path == null) {
            throw new IllegalArgumentException("Choose a file path before importing or exporting cards.");
        }
    }

    private boolean isHeaderRow(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        return normalized.equals("front\tback") || normalized.startsWith("front\tback\tcard_type");
    }

    private String toTsvRow(List<String> fields) {
        for (String field : fields) {
            if (field.contains("\t") || field.contains("\n") || field.contains("\r")) {
                throw new IllegalArgumentException("unsupported tab or newline in field: " + preview(field));
            }
        }
        return String.join("\t", fields);
    }

    private String preview(String value) {
        return value.length() <= 30 ? value : value.substring(0, 27) + "...";
    }

    private String field(String value) {
        return value == null ? "" : value;
    }

    private String get(String[] fields, int index) {
        return index < fields.length ? fields[index] : "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Integer parseTimeLimit(String value, int lineNumber) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(value);
            if (seconds <= 0) {
                throw new IllegalArgumentException("Line " + lineNumber + ": time_limit_seconds must be positive.");
            }
            return seconds;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("time_limit_seconds must be a positive integer.");
        }
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumClass, String value, T fallback, String fieldName, int lineNumber) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(fieldName + " has unsupported value '" + value + "' on line " + lineNumber + ".");
        }
    }

    public record ImportSummary(int imported, int skippedDuplicates, List<String> errors) {
        public String message() {
            return "Imported " + imported + " cards" + (skippedDuplicates > 0 ? ", skipped " + skippedDuplicates + " duplicates" : "") + ".";
        }
    }

    public static class ImportExportException extends RuntimeException {
        private final ImportSummary summary;
        private final List<String> rowErrors;

        public ImportExportException(ImportSummary summary) {
            super(summary.message() + " Errors: " + String.join("; ", summary.errors()));
            this.summary = summary;
            this.rowErrors = summary.errors();
        }

        public ImportExportException(String message, List<String> rowErrors) {
            super(message + " Errors: " + String.join("; ", rowErrors));
            this.summary = null;
            this.rowErrors = rowErrors;
        }

        public ImportSummary getSummary() {
            return summary;
        }

        public List<String> getRowErrors() {
            return rowErrors;
        }
    }
}

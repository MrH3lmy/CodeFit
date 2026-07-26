package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Deck;
import com.codefit.model.ValidationMode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Installs the bundled Database Internals curriculum without exposing TSV files to the learner. */
public class DatabaseInternalsPackService {
    static final PackDeck[] PACK_DECKS = {
            new PackDeck(
                    "DI 01 - Architecture, Layout & File Formats",
                    "Trace database requests through core components and reason about physical layouts, indexes, pages, versioning, and corruption detection.",
                    "/templates/database-internals/01-architecture-layout-file-formats.tsv"),
            new PackDeck(
                    "DI 02 - B-Trees, Buffer Management & Recovery",
                    "Reason about B-Tree structure and maintenance, buffer-pool behavior, write-ahead logging, ARIES, and concurrency control.",
                    "/templates/database-internals/02-btrees-buffer-management-recovery.tsv"),
            new PackDeck(
                    "DI 03 - LSM Trees & Storage Trade-offs",
                    "Follow data through memtables, SSTables, compaction, amplification trade-offs, key-value separation, and SSD-aware storage stacks.",
                    "/templates/database-internals/03-lsm-trees-storage-tradeoffs.tsv"),
            new PackDeck(
                    "DI 04 - Distributed Foundations & Consistency",
                    "Build production judgment around partial failure, clocks, failure detectors, leader epochs, CAP, consistency models, quorums, and CRDTs.",
                    "/templates/database-internals/04-distributed-foundations-consistency.tsv"),
            new PackDeck(
                    "DI 05 - Anti-Entropy, Transactions & Consensus",
                    "Reason about replica repair, gossip, distributed commit, partitioning, Paxos, Raft, ZAB, Byzantine faults, and replicated-log recovery.",
                    "/templates/database-internals/05-anti-entropy-transactions-consensus.tsv")
    };

    private final DeckService deckService;
    private final FlashcardService flashcardService;

    public DatabaseInternalsPackService() {
        this(new DeckService(), new FlashcardService());
    }

    DatabaseInternalsPackService(DeckService deckService, FlashcardService flashcardService) {
        this.deckService = deckService;
        this.flashcardService = flashcardService;
    }

    /** Creates missing decks and imports only prompts that are not already present. Safe to repeat. */
    public InstallSummary install() {
        Map<String, Deck> decksByName = new LinkedHashMap<>();
        for (Deck deck : deckService.getDecks()) {
            decksByName.putIfAbsent(normalizeName(deck.getName()), deck);
        }

        int decksCreated = 0;
        int cardsImported = 0;
        int duplicatesSkipped = 0;

        for (PackDeck definition : PACK_DECKS) {
            String normalizedName = normalizeName(definition.name());
            Deck deck = decksByName.get(normalizedName);
            if (deck == null) {
                deck = deckService.createDeck(definition.name(), definition.description());
                decksByName.put(normalizedName, deck);
                decksCreated++;
            }

            for (BundledCard card : loadCards(definition.resourcePath())) {
                if (flashcardService.cardExistsInDeck(deck.getId(), card.front())) {
                    duplicatesSkipped++;
                    continue;
                }
                flashcardService.addCard(
                        deck.getId(),
                        card.front(),
                        card.back(),
                        card.cardType(),
                        card.acceptedAnswers(),
                        card.validationMode(),
                        null,
                        card.hint(),
                        card.timeLimitSeconds(),
                        card.skillCategory());
                cardsImported++;
            }
        }

        return new InstallSummary(decksCreated, cardsImported, duplicatesSkipped);
    }

    List<BundledCard> loadCards(String resourcePath) {
        InputStream input = DatabaseInternalsPackService.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Missing bundled curriculum resource: " + resourcePath);
        }
        return parseCards(input, resourcePath);
    }

    static List<BundledCard> parseCards(InputStream input, String sourceName) {
        if (input == null) {
            throw new IllegalArgumentException("Curriculum input is required.");
        }

        List<BundledCard> cards = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || (lineNumber == 1 && FlashcardImportExportService.isHeaderRow(line))) {
                    continue;
                }

                String[] fields = line.split("\\t", -1);
                if (fields.length != 8) {
                    throw new IllegalStateException(sourceName + " line " + lineNumber
                            + ": expected 8 tab-separated fields, found " + fields.length + ".");
                }

                CardType cardType = FlashcardImportExportService.parseEnum(
                        CardType.class, fields[2], CardType.RECALL, "card_type", lineNumber);
                ValidationMode validationMode = FlashcardImportExportService.parseEnum(
                        ValidationMode.class, fields[4], ValidationMode.CASE_INSENSITIVE,
                        "validation_mode", lineNumber);
                String acceptedAnswers = FlashcardImportExportService.blankToDefault(fields[3], fields[1]);
                FlashcardImportExportService.requireGradableRegexConfig(
                        cardType, validationMode, acceptedAnswers, lineNumber);

                cards.add(new BundledCard(
                        fields[0],
                        fields[1],
                        cardType,
                        acceptedAnswers,
                        validationMode,
                        FlashcardImportExportService.blankToNull(fields[5]),
                        FlashcardImportExportService.blankToNull(fields[6]),
                        FlashcardImportExportService.parseTimeLimit(fields[7], lineNumber)));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read bundled curriculum: " + sourceName, exception);
        }
        return List.copyOf(cards);
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    record PackDeck(String name, String description, String resourcePath) {
    }

    record BundledCard(String front, String back, CardType cardType, String acceptedAnswers,
                       ValidationMode validationMode, String hint, String skillCategory,
                       Integer timeLimitSeconds) {
    }

    public record InstallSummary(int decksCreated, int cardsImported, int duplicatesSkipped) {
        public String message() {
            String result = "Database Internals installed: " + decksCreated + " decks created, "
                    + cardsImported + " cards imported";
            if (duplicatesSkipped > 0) {
                result += ", " + duplicatesSkipped + " duplicates skipped";
            }
            return result + ".";
        }
    }
}

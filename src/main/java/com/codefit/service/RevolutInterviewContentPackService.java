package com.codefit.service;

import com.codefit.model.Deck;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Installs the seven Revolut-specific interview decks used by {@link RevolutJavaInterviewProfile}.
 * This is deliberately a content pack, not a fourth sequential {@link com.codefit.model.TrainingPath}:
 * the interview profile composes these decks with existing JCIP/ABE/DI material across domains.
 *
 * <p>Each deck is split into a focused 20-card core resource plus a 30-card advanced supplement.
 * Keeping those resources separate lets existing installations safely sync the additional material
 * without replacing or duplicating the original prompts. Installation remains idempotent: existing
 * decks are reused and a card is skipped when the same prompt already exists in that deck.</p>
 */
public class RevolutInterviewContentPackService {
    public static final String RJ01_DECK = "RJ 01 - Modern Java 17/21 & Core Interview";
    public static final String RJ02_DECK = "RJ 02 - PostgreSQL Performance & jOOQ";
    public static final String RJ03_DECK = "RJ 03 - DDD, CQRS & Event-Driven Design";
    public static final String RJ04_DECK = "RJ 04 - System Design Building Blocks";
    public static final String RJ05_DECK = "RJ 05 - Fintech System Design";
    public static final String RJ06_DECK = "RJ 06 - Revolut Stack Awareness";
    public static final String RJ07_DECK = "RJ 07 - Team Fit & STAR";

    static final PackDeck[] PACK_DECKS = {
            new PackDeck(RJ01_DECK,
                    "Modern Java 17/21 interview judgment: records, sealed hierarchies, pattern matching, generics, streams, CompletableFuture, and virtual-thread trade-offs.",
                    "/templates/revolut-java-interview/01-modern-java-core-interview.tsv",
                    "/templates/revolut-java-interview/01-modern-java-core-interview-advanced.tsv"),
            new PackDeck(RJ02_DECK,
                    "PostgreSQL execution plans, indexes, MVCC/operations, and SQL-first jOOQ transaction and schema practices.",
                    "/templates/revolut-java-interview/02-postgresql-performance-jooq.tsv",
                    "/templates/revolut-java-interview/02-postgresql-performance-jooq-advanced.tsv"),
            new PackDeck(RJ03_DECK,
                    "DDD boundaries and invariants, integration events, CQRS, event sourcing, replay, ordering, and idempotency trade-offs.",
                    "/templates/revolut-java-interview/03-ddd-cqrs-event-driven.tsv",
                    "/templates/revolut-java-interview/03-ddd-cqrs-event-driven-advanced.tsv"),
            new PackDeck(RJ04_DECK,
                    "System-design building blocks practiced as explicit requirements, scaling, storage, consistency, resilience, and operability decisions.",
                    "/templates/revolut-java-interview/04-system-design-building-blocks.tsv",
                    "/templates/revolut-java-interview/04-system-design-building-blocks-advanced.tsv"),
            new PackDeck(RJ05_DECK,
                    "Fintech system design around ledgers, payment state machines, idempotency, reconciliation, settlement, risk, and auditability.",
                    "/templates/revolut-java-interview/05-fintech-system-design.tsv",
                    "/templates/revolut-java-interview/05-fintech-system-design-advanced.tsv"),
            new PackDeck(RJ06_DECK,
                    "Concept and trade-off awareness for the current role stack: Kubernetes, Redis, GCP, Prometheus/Grafana/New Relic, Flyway, jOOQ, Spock, and containers.",
                    "/templates/revolut-java-interview/06-revolut-stack-awareness.tsv",
                    "/templates/revolut-java-interview/06-revolut-stack-awareness-advanced.tsv"),
            new PackDeck(RJ07_DECK,
                    "Behavioral interview drills for STAR, ownership, conflict, ambiguity, communication, product impact, mentoring, feedback, and motivation.",
                    "/templates/revolut-java-interview/07-team-fit-star.tsv",
                    "/templates/revolut-java-interview/07-team-fit-star-advanced.tsv")
    };

    private final DeckService deckService;
    private final FlashcardService flashcardService;

    public RevolutInterviewContentPackService() {
        this(new DeckService(), new FlashcardService());
    }

    RevolutInterviewContentPackService(DeckService deckService, FlashcardService flashcardService) {
        this.deckService = deckService;
        this.flashcardService = flashcardService;
    }

    public List<String> deckNames() {
        return List.of(PACK_DECKS).stream().map(PackDeck::name).toList();
    }

    /** Creates missing RJ decks and imports only prompts that are not already present. Safe to repeat. */
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

            for (DatabaseInternalsPackService.BundledCard card : loadCards(definition)) {
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

    /** Core + advanced supplement for one interview deck. */
    List<DatabaseInternalsPackService.BundledCard> loadCards(PackDeck definition) {
        List<DatabaseInternalsPackService.BundledCard> cards = new ArrayList<>(loadCards(definition.resourcePath()));
        cards.addAll(loadCards(definition.supplementalResourcePath()));
        return List.copyOf(cards);
    }

    List<DatabaseInternalsPackService.BundledCard> loadCards(String resourcePath) {
        var input = RevolutInterviewContentPackService.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IllegalStateException("Missing bundled Revolut interview resource: " + resourcePath);
        }
        return DatabaseInternalsPackService.parseCards(input, resourcePath);
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    record PackDeck(String name, String description, String resourcePath, String supplementalResourcePath) {
    }

    public record InstallSummary(int decksCreated, int cardsImported, int duplicatesSkipped) {
        public String message() {
            return "Revolut interview content installed: " + decksCreated + " decks created, "
                    + cardsImported + " cards imported, " + duplicatesSkipped + " duplicates skipped.";
        }
    }
}

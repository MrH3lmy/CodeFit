package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.TrainingPath;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TrainingPathService {
    private static final TrainingPath JAVA_BACKEND_PATH = new TrainingPath(
            "Java Backend",
            List.of(
                    new TrainingPath.TrainingPathModule(1, "Core Java & OOP", "Build a dependable foundation in Java syntax, classes, inheritance, polymorphism, exceptions, and JVM concepts.", "Java BE 01 - Core Java & OOP", List.of(), 0.8),
                    new TrainingPath.TrainingPathModule(2, "Collections, Streams & Generics", "Use collections, generics, lambdas, and stream pipelines to model and transform backend data safely.", "Java BE 02 - Collections, Streams & Generics", List.of(1), 0.8),
                    new TrainingPath.TrainingPathModule(3, "JDBC & SQL", "Connect Java code to relational databases with SQL, prepared statements, transactions, and schema fundamentals.", "Java BE 03 - JDBC & SQL", List.of(1, 2), 0.8),
                    new TrainingPath.TrainingPathModule(4, "Spring Boot REST APIs", "Create Spring Boot controllers, request/response DTOs, validation rules, and RESTful endpoints.", "Java BE 04 - Spring Boot REST APIs", List.of(2, 3), 0.8),
                    new TrainingPath.TrainingPathModule(5, "Persistence with JPA/Hibernate", "Map domain data with entities, repositories, relationships, query methods, and transaction boundaries.", "Java BE 05 - Persistence with JPA/Hibernate", List.of(3, 4), 0.8),
                    new TrainingPath.TrainingPathModule(6, "Testing with JUnit/Mockito", "Verify service and controller behavior with unit tests, mocks, integration tests, and repeatable test slices.", "Java BE 06 - Testing with JUnit/Mockito", List.of(4), 0.8),
                    new TrainingPath.TrainingPathModule(7, "Security & Auth", "Apply authentication, authorization, password handling, JWT/session tradeoffs, and Spring Security concepts.", "Java BE 07 - Security & Auth", List.of(4), 0.8),
                    new TrainingPath.TrainingPathModule(8, "Build, Git & Deployment", "Package services, manage dependencies, use Git workflows, configure environments, and prepare apps for deployment.", "Java BE 08 - Build, Git & Deployment", List.of(6, 7), 0.8)
            ),
            Pattern.compile("^\\s*Java\\s+BE\\s+(\\d{1,2})\\b.*", Pattern.CASE_INSENSITIVE),
            3,
            0.8
    );

    /**
     * A second path aimed at senior backend engineers rather than beginner Java recall: production
     * concurrency, data-consistency, messaging, distributed-systems, auth, operability, and
     * performance topics with scenario-diagnosis and trade-off-analysis style cards. Module 1 reuses
     * the Java Concurrency in Practice curriculum (four decks) added separately; every other module
     * ships its own starter deck under {@code templates/advanced-backend-engineering/}.
     */
    private static final TrainingPath ADVANCED_BACKEND_ENGINEERING_PATH = new TrainingPath(
            "Advanced Backend Engineering",
            List.of(
                    new TrainingPath.TrainingPathModule(1, "Java Concurrency & Thread Safety",
                            "Reason correctly about shared mutable state, task execution, and the Java Memory Model so concurrent backend code stays safe and live under production load.",
                            List.of("JCIP 01 - Fundamentals", "JCIP 02 - Task Execution & Cancellation",
                                    "JCIP 03 - Liveness, Performance & Testing", "JCIP 04 - Locks, Atomics & Memory Model"),
                            List.of(), 0.8),
                    new TrainingPath.TrainingPathModule(2, "Database Transactions, Locking & Isolation",
                            "Choose isolation levels and locking strategies deliberately, and diagnose lost updates, phantom reads, and deadlocks in production transactions.",
                            "ABE 02 - Database Transactions, Locking & Isolation", List.of(1), 0.85),
                    new TrainingPath.TrainingPathModule(3, "Idempotency & Race-Condition Prevention",
                            "Design idempotent APIs and background jobs that stay correct under retries, duplicate delivery, and concurrent requests.",
                            "ABE 03 - Idempotency & Race-Condition Prevention", List.of(1, 2), 0.85),
                    new TrainingPath.TrainingPathModule(4, "Kafka Delivery Semantics, Outbox & DLQs",
                            "Reason about at-least-once, at-most-once, and exactly-once tradeoffs, design transactional outbox publishing, and build safe retry and dead-letter handling for Kafka consumers.",
                            "ABE 04 - Kafka Delivery Semantics, Outbox & DLQs", List.of(2, 3), 0.8),
                    new TrainingPath.TrainingPathModule(5, "Distributed Transactions & Sagas",
                            "Coordinate multi-service consistency with sagas and compensating actions instead of relying on unsafe two-phase commit across service boundaries.",
                            "ABE 05 - Distributed Transactions & Sagas", List.of(2, 4), 0.8),
                    new TrainingPath.TrainingPathModule(6, "OAuth2, OIDC & Service Authentication",
                            "Apply OAuth2 grant types, OIDC identity flows, and service-to-service authentication such as mTLS and client credentials correctly in backend systems.",
                            "ABE 06 - OAuth2, OIDC & Service Authentication", List.of(1), 0.75),
                    new TrainingPath.TrainingPathModule(7, "Caching, Consistency & Invalidation",
                            "Choose caching strategies and invalidation approaches that keep read paths fast without serving stale or inconsistent data.",
                            "ABE 07 - Caching, Consistency & Invalidation", List.of(1, 2), 0.8),
                    new TrainingPath.TrainingPathModule(8, "Observability & Production Debugging",
                            "Use logs, metrics, traces, and thread/heap dumps to diagnose production incidents quickly instead of guessing.",
                            "ABE 08 - Observability & Production Debugging", List.of(1), 0.75),
                    new TrainingPath.TrainingPathModule(9, "JVM Memory, Garbage Collection & Performance",
                            "Reason about heap regions, garbage collectors, and JVM tuning flags well enough to diagnose latency spikes, memory leaks, and throughput regressions.",
                            "ABE 09 - JVM Memory, Garbage Collection & Performance", List.of(1), 0.75),
                    new TrainingPath.TrainingPathModule(10, "API & Database Failure Scenarios",
                            "Design and diagnose backend systems for partial failure: timeouts, retries, backpressure, circuit breakers, and cascading outages across APIs and databases.",
                            "ABE 10 - API & Database Failure Scenarios", List.of(2, 4, 7, 8, 9), 0.8)
            ),
            Pattern.compile("^\\s*ABE\\s+(\\d{1,2})\\b.*", Pattern.CASE_INSENSITIVE),
            10,
            0.8
    );

    /** A focused deep-dive path covering local storage engines and distributed database internals. */
    private static final TrainingPath DATABASE_INTERNALS_PATH = new TrainingPath(
            "Database Internals",
            List.of(
                    new TrainingPath.TrainingPathModule(1, "Architecture, Layout & File Formats",
                            "Trace requests through database subsystems and choose physical layouts, indexes, pages, versioning, and integrity checks according to workload.",
                            "DI 01 - Architecture, Layout & File Formats", List.of(), 0.8),
                    new TrainingPath.TrainingPathModule(2, "B-Trees, Buffer Management & Recovery",
                            "Reason about B-Tree structure and maintenance, buffer-pool behavior, WAL, ARIES, and concurrency control under production failure and load.",
                            "DI 02 - B-Trees, Buffer Management & Recovery", List.of(1), 0.85),
                    new TrainingPath.TrainingPathModule(3, "LSM Trees & Storage Trade-offs",
                            "Follow writes through memtables, SSTables, compaction, amplification trade-offs, key-value separation, and SSD-aware storage stacks.",
                            "DI 03 - LSM Trees & Storage Trade-offs", List.of(1, 2), 0.85),
                    new TrainingPath.TrainingPathModule(4, "Distributed Foundations & Consistency",
                            "Reason about partial failure, clocks, failure detection, leader epochs, CAP, consistency models, session guarantees, quorums, and CRDTs.",
                            "DI 04 - Distributed Foundations & Consistency", List.of(1), 0.8),
                    new TrainingPath.TrainingPathModule(5, "Anti-Entropy, Transactions & Consensus",
                            "Explain replica repair, gossip, distributed commit, partitioning, coordination avoidance, Paxos, Raft, ZAB, Byzantine faults, and log recovery.",
                            "DI 05 - Anti-Entropy, Transactions & Consensus", List.of(2, 4), 0.85)
            ),
            Pattern.compile("^\\s*DI\\s+(\\d{1,2})\\b.*", Pattern.CASE_INSENSITIVE),
            5,
            0.8
    );

    private final FlashcardService flashcardService = new FlashcardService();
    private final MasteryService masteryService = new MasteryService();

    public List<TrainingPath> getTrainingPaths() {
        return List.of(JAVA_BACKEND_PATH, ADVANCED_BACKEND_ENGINEERING_PATH, DATABASE_INTERNALS_PATH);
    }

    public TrainingPath getJavaBackendPath() {
        return JAVA_BACKEND_PATH;
    }

    public TrainingPath getAdvancedBackendEngineeringPath() {
        return ADVANCED_BACKEND_ENGINEERING_PATH;
    }

    public TrainingPath getDatabaseInternalsPath() {
        return DATABASE_INTERNALS_PATH;
    }

    public Optional<TrainingPathRecommendation> recommendNextModule(List<Deck> decks) {
        return getTrainingPaths().stream()
                .map(path -> recommend(path, getPathProgress(path, decks)))
                .flatMap(Optional::stream)
                .findFirst();
    }

    /**
     * Pure decision logic for a single path, independent of the database, so the recommendation
     * rules can be unit tested directly against hand-built progress data. Mirrors
     * {@link MasteryService#evaluate} being separated from repository access.
     */
    static Optional<TrainingPathRecommendation> recommend(TrainingPath path, List<TrainingPathModuleProgress> pathDecks) {
        if (pathDecks.isEmpty()) {
            return Optional.empty();
        }

        Optional<TrainingPathModuleProgress> emptyStarterModule = pathDecks.stream()
                .filter(progress -> progress.module().getOrder() <= path.getStarterCardModuleLimit())
                .filter(progress -> progress.cardCount() == 0)
                .min(Comparator.comparingInt(progress -> progress.module().getOrder()));
        if (emptyStarterModule.isPresent()) {
            return Optional.of(new TrainingPathRecommendation(path, emptyStarterModule.get(), null,
                    TrainingPathAction.ADD_STARTER_CARDS));
        }

        Optional<TrainingPathModuleProgress> weakestDueModule = pathDecks.stream()
                .filter(progress -> progress.dueCount() > 0)
                .min(Comparator.comparingInt(TrainingPathModuleProgress::progressPercent)
                        .thenComparing(TrainingPathModuleProgress::dueCount, Comparator.reverseOrder())
                        .thenComparingInt(progress -> progress.module().getOrder()));
        if (weakestDueModule.isPresent()) {
            return Optional.of(new TrainingPathRecommendation(path, weakestDueModule.get(), null,
                    TrainingPathAction.REVIEW_DUE_MODULE));
        }

        // A module counts as complete once its own durable-mastery threshold is met, not a fixed
        // path-wide percentage, so modules with different depth/difficulty can require different
        // bars before the engine moves the learner along.
        Optional<TrainingPathModuleProgress> completedModule = pathDecks.stream()
                .filter(progress -> progress.cardCount() > 0)
                .filter(progress -> progress.reviewProgress() >= progress.module().getMasteryThreshold())
                .filter(progress -> nextModule(progress, pathDecks).isPresent())
                .max(Comparator.comparingInt(progress -> progress.module().getOrder()));
        if (completedModule.isPresent()) {
            TrainingPathModuleProgress current = completedModule.get();
            return Optional.of(new TrainingPathRecommendation(path, current, nextModule(current, pathDecks).get(),
                    TrainingPathAction.MOVE_TO_NEXT_MODULE));
        }

        return Optional.empty();
    }

    /** Deck ids backing a given path/module, e.g. to bias new-card selection toward a learner's chosen focus module (#110). */
    public Set<Long> resolveModuleDeckIds(String pathName, int moduleOrder, List<Deck> decks) {
        return getTrainingPaths().stream()
                .filter(path -> path.getName().equalsIgnoreCase(pathName))
                .findFirst()
                .flatMap(path -> path.findModuleByOrder(moduleOrder))
                .map(module -> decks.stream().filter(module::matchesDeck).map(Deck::getId).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    /**
     * Focus-aware variant of {@link #recommendNextModule}: only suggests advancing the learner's
     * explicitly chosen focus module, and only once that module's own mastery threshold (via
     * {@link MasteryService}, not a raw attempted/seen count) is met (#110). Unlike
     * recommendNextModule, this never surfaces ADD_STARTER_CARDS/REVIEW_DUE_MODULE for other
     * modules — those still reach the learner through the dashboard's existing recommendation.
     */
    public Optional<TrainingPathRecommendation> recommendFocusChange(String activePathName, int focusModuleOrder, List<Deck> decks) {
        return getTrainingPaths().stream()
                .filter(path -> path.getName().equalsIgnoreCase(activePathName))
                .findFirst()
                .flatMap(path -> recommendFocusChange(path, focusModuleOrder, getPathProgress(path, decks)));
    }

    static Optional<TrainingPathRecommendation> recommendFocusChange(TrainingPath path, int focusModuleOrder,
                                                                       List<TrainingPathModuleProgress> pathProgress) {
        return pathProgress.stream()
                .filter(progress -> progress.module().getOrder() == focusModuleOrder)
                .findFirst()
                .filter(progress -> progress.cardCount() > 0 && progress.reviewProgress() >= progress.module().getMasteryThreshold())
                .flatMap(current -> nextModule(current, pathProgress)
                        .map(next -> new TrainingPathRecommendation(path, current, next, TrainingPathAction.MOVE_TO_NEXT_MODULE)));
    }

    private List<TrainingPathModuleProgress> getPathProgress(TrainingPath path, List<Deck> decks) {
        return path.getModules().stream()
                .map(module -> toProgress(module, decks))
                .filter(progress -> progress.deck() != null)
                .sorted(Comparator.comparingInt(progress -> progress.module().getOrder()))
                .toList();
    }

    private TrainingPathModuleProgress toProgress(TrainingPath.TrainingPathModule module, List<Deck> decks) {
        List<Deck> matchingDecks = decks.stream().filter(module::matchesDeck).toList();
        Deck representativeDeck = matchingDecks.stream().findFirst().orElse(null);
        List<Flashcard> cards = matchingDecks.stream()
                .flatMap(deck -> flashcardService.getCardsForDeck(deck.getId()).stream())
                .toList();
        long dueCount = countDueCards(cards);
        int progressPercent = calculateProgressPercent(cards);
        return new TrainingPathModuleProgress(module, representativeDeck, cards.size(), dueCount, progressPercent);
    }

    private static Optional<TrainingPathModuleProgress> nextModule(TrainingPathModuleProgress current,
                                                            List<TrainingPathModuleProgress> pathDecks) {
        return pathDecks.stream()
                .filter(progress -> progress.module().getOrder() > current.module().getOrder())
                .min(Comparator.comparingInt(progress -> progress.module().getOrder()));
    }

    private long countDueCards(List<Flashcard> cards) {
        LocalDate today = LocalDate.now();
        return cards.stream()
                .filter(card -> card.getDueDate() != null && !card.getDueDate().isAfter(today))
                .count();
    }

    private int calculateProgressPercent(List<Flashcard> cards) {
        if (cards.isEmpty()) {
            return 0;
        }
        return (int) Math.round(masteryService.summarize(cards).masteredPercent());
    }

    public enum TrainingPathAction {
        ADD_STARTER_CARDS,
        REVIEW_DUE_MODULE,
        MOVE_TO_NEXT_MODULE
    }

    public record TrainingPathModuleProgress(TrainingPath.TrainingPathModule module, Deck deck, int cardCount,
                                              long dueCount, int progressPercent) {
        public double reviewProgress() {
            return progressPercent / 100.0;
        }
    }

    public record TrainingPathRecommendation(TrainingPath path, TrainingPathModuleProgress current,
                                              TrainingPathModuleProgress next, TrainingPathAction action) {
    }
}

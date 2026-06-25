package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.TrainingPath;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public class TrainingPathService {
    private static final TrainingPath JAVA_BACKEND_PATH = new TrainingPath(
            "Java Backend",
            List.of(
                    new TrainingPath.TrainingPathModule(1, "Core Java & OOP", "Build a dependable foundation in Java syntax, classes, inheritance, polymorphism, exceptions, and JVM concepts.", "Java BE 01 - Core Java & OOP"),
                    new TrainingPath.TrainingPathModule(2, "Collections, Streams & Generics", "Use collections, generics, lambdas, and stream pipelines to model and transform backend data safely.", "Java BE 02 - Collections, Streams & Generics"),
                    new TrainingPath.TrainingPathModule(3, "JDBC & SQL", "Connect Java code to relational databases with SQL, prepared statements, transactions, and schema fundamentals.", "Java BE 03 - JDBC & SQL"),
                    new TrainingPath.TrainingPathModule(4, "Spring Boot REST APIs", "Create Spring Boot controllers, request/response DTOs, validation rules, and RESTful endpoints.", "Java BE 04 - Spring Boot REST APIs"),
                    new TrainingPath.TrainingPathModule(5, "Persistence with JPA/Hibernate", "Map domain data with entities, repositories, relationships, query methods, and transaction boundaries.", "Java BE 05 - Persistence with JPA/Hibernate"),
                    new TrainingPath.TrainingPathModule(6, "Testing with JUnit/Mockito", "Verify service and controller behavior with unit tests, mocks, integration tests, and repeatable test slices.", "Java BE 06 - Testing with JUnit/Mockito"),
                    new TrainingPath.TrainingPathModule(7, "Security & Auth", "Apply authentication, authorization, password handling, JWT/session tradeoffs, and Spring Security concepts.", "Java BE 07 - Security & Auth"),
                    new TrainingPath.TrainingPathModule(8, "Build, Git & Deployment", "Package services, manage dependencies, use Git workflows, configure environments, and prepare apps for deployment.", "Java BE 08 - Build, Git & Deployment")
            ),
            Pattern.compile("^\\s*Java\\s+BE\\s+(\\d{1,2})\\b.*", Pattern.CASE_INSENSITIVE),
            3,
            0.8
    );

    private final FlashcardService flashcardService = new FlashcardService();

    public List<TrainingPath> getTrainingPaths() {
        return List.of(JAVA_BACKEND_PATH);
    }

    public TrainingPath getJavaBackendPath() {
        return JAVA_BACKEND_PATH;
    }

    public Optional<TrainingPathRecommendation> recommendNextModule(List<Deck> decks) {
        return getTrainingPaths().stream()
                .map(path -> recommendNextModule(path, decks))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<TrainingPathRecommendation> recommendNextModule(TrainingPath path, List<Deck> decks) {
        List<TrainingPathModuleProgress> pathDecks = getPathProgress(path, decks);
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

        Optional<TrainingPathModuleProgress> completedModule = pathDecks.stream()
                .filter(progress -> progress.cardCount() > 0)
                .filter(progress -> progress.reviewProgress() >= path.getModuleCompletionThreshold())
                .filter(progress -> nextModule(progress, pathDecks).isPresent())
                .max(Comparator.comparingInt(progress -> progress.module().getOrder()));
        if (completedModule.isPresent()) {
            TrainingPathModuleProgress current = completedModule.get();
            return Optional.of(new TrainingPathRecommendation(path, current, nextModule(current, pathDecks).get(),
                    TrainingPathAction.MOVE_TO_NEXT_MODULE));
        }

        return Optional.empty();
    }

    private List<TrainingPathModuleProgress> getPathProgress(TrainingPath path, List<Deck> decks) {
        return decks.stream()
                .map(deck -> path.findModuleForDeck(deck)
                        .map(module -> toProgress(deck, module))
                        .orElse(null))
                .filter(progress -> progress != null)
                .sorted(Comparator.comparingInt(progress -> progress.module().getOrder()))
                .toList();
    }

    private TrainingPathModuleProgress toProgress(Deck deck, TrainingPath.TrainingPathModule module) {
        List<Flashcard> cards = flashcardService.getCardsForDeck(deck.getId());
        long dueCount = countDueCards(cards);
        int progressPercent = calculateProgressPercent(cards);
        return new TrainingPathModuleProgress(module, deck, cards.size(), dueCount, progressPercent);
    }

    private Optional<TrainingPathModuleProgress> nextModule(TrainingPathModuleProgress current,
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
        long reviewedCards = cards.stream().filter(card -> card.getReviewCount() > 0).count();
        return (int) Math.round((reviewedCards * 100.0) / cards.size());
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

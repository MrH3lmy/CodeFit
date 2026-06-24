package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.SyllabusModule;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.FlashcardRepository;

import java.util.Comparator;
import java.util.List;

public class SyllabusService {
    private static final List<SyllabusDefinition> JAVA_BE_SYLLABUS = List.of(
            new SyllabusDefinition(1, "Core Java & OOP", "Build a dependable foundation in Java syntax, classes, inheritance, polymorphism, exceptions, and JVM concepts.", "Java BE 01 - Core Java & OOP"),
            new SyllabusDefinition(2, "Collections, Streams & Generics", "Use collections, generics, lambdas, and stream pipelines to model and transform backend data safely.", "Java BE 02 - Collections, Streams & Generics"),
            new SyllabusDefinition(3, "JDBC & SQL", "Connect Java code to relational databases with SQL, prepared statements, transactions, and schema fundamentals.", "Java BE 03 - JDBC & SQL"),
            new SyllabusDefinition(4, "Spring Boot REST APIs", "Create Spring Boot controllers, request/response DTOs, validation rules, and RESTful endpoints.", "Java BE 04 - Spring Boot REST APIs"),
            new SyllabusDefinition(5, "Persistence with JPA/Hibernate", "Map domain data with entities, repositories, relationships, query methods, and transaction boundaries.", "Java BE 05 - Persistence with JPA/Hibernate"),
            new SyllabusDefinition(6, "Testing with JUnit/Mockito", "Verify service and controller behavior with unit tests, mocks, integration tests, and repeatable test slices.", "Java BE 06 - Testing with JUnit/Mockito"),
            new SyllabusDefinition(7, "Security & Auth", "Apply authentication, authorization, password handling, JWT/session tradeoffs, and Spring Security concepts.", "Java BE 07 - Security & Auth"),
            new SyllabusDefinition(8, "Build, Git & Deployment", "Package services, manage dependencies, use Git workflows, configure environments, and prepare apps for deployment.", "Java BE 08 - Build, Git & Deployment")
    );

    private final DeckRepository deckRepository = new DeckRepository();
    private final FlashcardRepository flashcardRepository = new FlashcardRepository();

    public List<SyllabusModule> getJavaBackendModules() {
        List<Deck> decks = deckRepository.findAll();
        return JAVA_BE_SYLLABUS.stream()
                .map(definition -> toModule(definition, decks))
                .sorted(Comparator.comparingInt(SyllabusModule::getModuleNumber))
                .toList();
    }

    private SyllabusModule toModule(SyllabusDefinition definition, List<Deck> decks) {
        Deck deck = decks.stream()
                .filter(candidate -> candidate.getName().equalsIgnoreCase(definition.deckName()))
                .findFirst()
                .orElse(null);
        if (deck == null) {
            return new SyllabusModule(definition.moduleNumber(), definition.title(), definition.learningObjective(),
                    0, definition.deckName(), 0, 0);
        }

        List<Flashcard> cards = flashcardRepository.findByDeckId(deck.getId());
        int reviewedCards = (int) cards.stream().filter(card -> card.getReviewCount() > 0).count();
        return new SyllabusModule(definition.moduleNumber(), definition.title(), definition.learningObjective(),
                deck.getId(), deck.getName(), cards.size(), reviewedCards);
    }

    private record SyllabusDefinition(int moduleNumber, String title, String learningObjective, String deckName) {
    }
}

package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Deck;
import com.codefit.model.ValidationMode;
import com.codefit.repository.DeckRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DeckService {
    private static final JavaBackendDeck[] JAVA_BACKEND_DECKS = {
            new JavaBackendDeck("Java BE 01 - Core Java & OOP", "The foundation of the backend roadmap: Java syntax, classes, inheritance, polymorphism, exceptions, and JVM concepts needed before building services."),
            new JavaBackendDeck("Java BE 02 - Collections, Streams & Generics", "Build fluency with the data structures, generic types, lambdas, and stream pipelines used to transform backend request and persistence data safely."),
            new JavaBackendDeck("Java BE 03 - JDBC & SQL", "Connect Java applications to relational databases with SQL, JDBC, prepared statements, transactions, and schema design fundamentals."),
            new JavaBackendDeck("Java BE 04 - Spring Boot REST APIs", "Move from core Java into service development by creating Spring Boot controllers, request/response DTOs, validation, and RESTful endpoints."),
            new JavaBackendDeck("Java BE 05 - Persistence with JPA/Hibernate", "Model backend domain data with entities, repositories, relationships, query methods, and transaction boundaries using JPA and Hibernate."),
            new JavaBackendDeck("Java BE 06 - Testing with JUnit/Mockito", "Protect backend behavior with unit tests, mocks, integration tests, test slices, and repeatable verification of service and controller logic."),
            new JavaBackendDeck("Java BE 07 - Security & Auth", "Add production-minded security concepts such as authentication, authorization, password handling, JWT/session tradeoffs, and Spring Security filters."),
            new JavaBackendDeck("Java BE 08 - Build, Git & Deployment", "Finish the roadmap by packaging services, managing dependencies, using Git workflows, configuring environments, and preparing backend apps for deployment.")
    };

    private static final JavaBackendCard[] JAVA_BACKEND_STARTER_CARDS = {
            new JavaBackendCard("Java BE 01 - Core Java & OOP", "What Java keyword creates a subclass relationship?", "extends", CardType.RECALL, "extends", ValidationMode.CASE_INSENSITIVE, null, null, "Java Syntax", null),
            new JavaBackendCard("Java BE 01 - Core Java & OOP", "What does JVM stand for?", "Java Virtual Machine", CardType.RECALL, "Java Virtual Machine", ValidationMode.CASE_INSENSITIVE, null, null, "Java Runtime", null),
            new JavaBackendCard("Java BE 02 - Collections, Streams & Generics", "Which collection keeps insertion order and allows indexed access?", "ArrayList", CardType.RECALL, "ArrayList", ValidationMode.CASE_INSENSITIVE, null, null, "Collections", null),
            new JavaBackendCard("Java BE 03 - JDBC & SQL", "What JDBC object executes parameterized SQL safely?", "PreparedStatement", CardType.RECALL, "PreparedStatement", ValidationMode.CASE_INSENSITIVE, null, null, "SQL", null),
            new JavaBackendCard("Java BE 03 - JDBC & SQL", "Why should backend code prefer PreparedStatement over string-concatenated SQL?", "PreparedStatement binds parameters separately from SQL text, which helps prevent SQL injection and lets the driver handle type conversion.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("PreparedStatement prevents SQL injection by binding parameters", "It uses bind parameters instead of concatenating user input")), ValidationMode.CASE_INSENSITIVE, null, "Mention parameter binding and SQL injection.", "SQL", null),
            new JavaBackendCard("Java BE 03 - JDBC & SQL", "users(id, email, created_at): write SQL to list the 5 newest user emails.", "SELECT email FROM users ORDER BY created_at DESC LIMIT 5;", CardType.SQL_QUERY, AcceptedAnswerCodec.encode(List.of("SELECT email FROM users ORDER BY created_at DESC LIMIT 5;", "SELECT email FROM users ORDER BY created_at DESC LIMIT 5")), ValidationMode.NORMALIZED_SPACING, null, "Order newest first, then cap the result size.", "SQL", null),
            new JavaBackendCard("Java BE 03 - JDBC & SQL", "What is a database transaction?", "A transaction is a unit of work that should commit completely or roll back completely so related changes stay consistent.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("unit of work that commits or rolls back", "all-or-nothing unit of work")), ValidationMode.CASE_INSENSITIVE, null, "Think ACID and all-or-nothing changes.", "SQL", null),
            new JavaBackendCard("Java BE 03 - JDBC & SQL", "Which transaction isolation issue occurs when one transaction reads uncommitted changes from another?", "Dirty read", CardType.RECALL, "Dirty read", ValidationMode.CASE_INSENSITIVE, null, null, "SQL", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "Which Spring annotation combines @Controller and @ResponseBody for JSON REST endpoints?", "@RestController", CardType.RECALL, "@RestController", ValidationMode.CASE_INSENSITIVE, null, null, "Spring REST", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "Which annotation maps an HTTP GET request to a controller method?", "@GetMapping", CardType.RECALL, "@GetMapping", ValidationMode.CASE_INSENSITIVE, null, null, "Spring REST", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "Which annotation maps an HTTP POST request to a controller method?", "@PostMapping", CardType.RECALL, "@PostMapping", ValidationMode.CASE_INSENSITIVE, null, null, "Spring REST", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "What HTTP status code is typically returned after successfully creating a resource?", "201 Created", CardType.RECALL, AcceptedAnswerCodec.encode(List.of("201 Created", "201")), ValidationMode.CASE_INSENSITIVE, null, null, "Spring REST", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "What is the boundary between a DTO and an entity in a REST API?", "DTOs shape external request/response data, while entities model persisted domain state and should not be exposed directly as the API contract.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("DTOs are API contracts and entities are persistence models", "DTO for request response, entity for database domain")), ValidationMode.CASE_INSENSITIVE, null, "Separate API shape from persistence shape.", "Spring REST", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "Predict the response body: @GetMapping(\"/ping\") public String ping() { return \"pong\"; }", "pong", CardType.CODE_OUTPUT, "pong", ValidationMode.NORMALIZED_SPACING, "pong", "A @RestController writes the returned String to the HTTP response body.", "Spring REST", 30),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "Which Spring Boot annotation marks the main application class and enables component scanning and auto-configuration?", "@SpringBootApplication", CardType.RECALL, "@SpringBootApplication", ValidationMode.CASE_INSENSITIVE, null, null, "Deployment", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "Why is constructor injection preferred for required Spring dependencies?", "Constructor injection makes dependencies explicit, supports final fields, and fails fast when a required bean is missing.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("explicit dependencies final fields fail fast", "required dependencies are explicit and immutable")), ValidationMode.CASE_INSENSITIVE, null, "Think testability, immutability, and required collaborators.", "Spring REST", null),
            new JavaBackendCard("Java BE 05 - Persistence with JPA/Hibernate", "Which JPA annotation identifies the primary key field of an entity?", "@Id", CardType.RECALL, "@Id", ValidationMode.CASE_INSENSITIVE, null, null, "JPA", null),
            new JavaBackendCard("Java BE 05 - Persistence with JPA/Hibernate", "Which JPA annotation models a parent entity with many child entities?", "@OneToMany", CardType.RECALL, "@OneToMany", ValidationMode.CASE_INSENSITIVE, null, null, "JPA", null),
            new JavaBackendCard("Java BE 05 - Persistence with JPA/Hibernate", "What does @Entity tell JPA?", "It marks a Java class as a persistent entity that JPA can map to a database table.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("persistent entity mapped to a database table", "class mapped to a database table")), ValidationMode.CASE_INSENSITIVE, null, "Mention persistence and table mapping.", "JPA", null),
            new JavaBackendCard("Java BE 06 - Testing with JUnit/Mockito", "What is the main difference between a unit test and an integration test?", "A unit test isolates a small piece of code, often with mocks; an integration test verifies multiple real components working together.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("unit isolates code, integration tests components together", "unit test mocks dependencies integration test uses real components")), ValidationMode.CASE_INSENSITIVE, null, "Contrast isolation with wiring multiple pieces together.", "Testing", null),
            new JavaBackendCard("Java BE 06 - Testing with JUnit/Mockito", "Which Mockito method defines a stubbed return value for a mock call?", "when", CardType.RECALL, AcceptedAnswerCodec.encode(List.of("when", "Mockito.when")), ValidationMode.CASE_INSENSITIVE, null, null, "Testing", null),
            new JavaBackendCard("Java BE 07 - Security & Auth", "What does JWT stand for in backend authentication?", "JSON Web Token", CardType.RECALL, "JSON Web Token", ValidationMode.CASE_INSENSITIVE, null, null, "Security", null),
            new JavaBackendCard("Java BE 07 - Security & Auth", "What is a key tradeoff between server-side sessions and JWTs?", "Sessions keep auth state on the server and are easy to revoke; JWTs are usually stateless for the server but need careful expiration and revocation design.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("sessions are server-side and revocable, JWTs are stateless but harder to revoke", "JWT stateless session server state")), ValidationMode.CASE_INSENSITIVE, null, "Compare where auth state lives and how revocation works.", "Security", null),
            new JavaBackendCard("Java BE 08 - Build, Git & Deployment", "Maven command: run the test phase for this project.", "mvn test", CardType.COMMAND, AcceptedAnswerCodec.encode(List.of("mvn test", "./mvnw test")), ValidationMode.COMMAND_NORMALIZED, "Runs unit tests and earlier lifecycle phases needed for test execution.", "Use the Maven Wrapper variant if the project includes mvnw.", "Deployment", 45),
            new JavaBackendCard("Java BE 08 - Build, Git & Deployment", "Maven command: clean previous build outputs and package the application artifact.", "mvn clean package", CardType.COMMAND, AcceptedAnswerCodec.encode(List.of("mvn clean package", "./mvnw clean package")), ValidationMode.COMMAND_NORMALIZED, "Deletes target/ and builds the packaged artifact after running lifecycle phases up to package.", "clean removes generated outputs; package creates the jar or war.", "Deployment", 60),
            new JavaBackendCard("Java BE 08 - Build, Git & Deployment", "Spring Boot command-line option: start the app with the prod profile active.", "java -jar app.jar --spring.profiles.active=prod", CardType.COMMAND, AcceptedAnswerCodec.encode(List.of("java -jar app.jar --spring.profiles.active=prod", "SPRING_PROFILES_ACTIVE=prod java -jar app.jar")), ValidationMode.COMMAND_NORMALIZED, "Application starts with prod profile-specific configuration enabled.", "Profiles select environment-specific beans and properties.", "Deployment", 75),
            new JavaBackendCard("Java BE 08 - Build, Git & Deployment", "Why should secrets and environment-specific settings live outside committed source code?", "External configuration lets each environment provide its own values and prevents committing credentials into version control.", CardType.CONCEPT, AcceptedAnswerCodec.encode(List.of("prevents committing secrets and supports per-environment config", "keeps credentials out of source control")), ValidationMode.CASE_INSENSITIVE, null, "Think profiles, environment variables, and source control safety.", "Deployment", null),
            new JavaBackendCard("Java BE 04 - Spring Boot REST APIs", "Which Spring annotation centralizes exception handling across controllers?", "@ControllerAdvice", CardType.RECALL, AcceptedAnswerCodec.encode(List.of("@ControllerAdvice", "@RestControllerAdvice")), ValidationMode.CASE_INSENSITIVE, null, null, "Spring REST", null)
    };

    private final DeckRepository deckRepository = new DeckRepository();
    private final FlashcardService flashcardService = new FlashcardService();

    public List<Deck> getDecks() {
        return deckRepository.findAll();
    }

    public Deck createDeck(String name, String description) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Deck name is required.");
        }
        return deckRepository.save(new Deck(name.trim(), description == null || description.isBlank() ? "Custom training deck." : description.trim()));
    }

    public void createJavaBackendPath() {
        Map<String, Deck> decksByName = getDecks().stream()
                .collect(Collectors.toMap(Deck::getName, Function.identity(), (first, ignored) -> first));

        for (JavaBackendDeck deckDefinition : JAVA_BACKEND_DECKS) {
            decksByName.computeIfAbsent(deckDefinition.name(), name -> createDeck(name, deckDefinition.description()));
        }

        for (JavaBackendCard cardDefinition : JAVA_BACKEND_STARTER_CARDS) {
            Optional.ofNullable(decksByName.get(cardDefinition.deckName()))
                    .filter(deck -> !flashcardService.cardExistsInDeck(deck.getId(), cardDefinition.front()))
                    .ifPresent(deck -> flashcardService.addCard(
                            deck.getId(),
                            cardDefinition.front(),
                            cardDefinition.back(),
                            cardDefinition.cardType(),
                            cardDefinition.acceptedAnswers(),
                            cardDefinition.validationMode(),
                            cardDefinition.simulatedOutput(),
                            cardDefinition.hint(),
                            cardDefinition.timeLimitSeconds(),
                            cardDefinition.skillCategory()));
        }
    }

    private record JavaBackendDeck(String name, String description) {
    }

    private record JavaBackendCard(String deckName, String front, String back, CardType cardType,
                                   String acceptedAnswers, ValidationMode validationMode, String simulatedOutput,
                                   String hint, String skillCategory, Integer timeLimitSeconds) {
    }
}

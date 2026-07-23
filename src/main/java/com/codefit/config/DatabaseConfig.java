package com.codefit.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

import com.codefit.model.CardType;
import com.codefit.model.ValidationMode;
import com.codefit.service.AcceptedAnswerCodec;

import java.util.List;

public final class DatabaseConfig {
    private static final String DATABASE_URL = "jdbc:sqlite:codefit.db";

    private DatabaseConfig() {
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DATABASE_URL);
    }

    public static void initialize() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS decks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        description TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS flashcards (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        deck_id INTEGER NOT NULL,
                        front TEXT NOT NULL,
                        back TEXT NOT NULL,
                        card_type TEXT NOT NULL DEFAULT 'RECALL',
                        accepted_answers TEXT,
                        validation_mode TEXT NOT NULL DEFAULT 'CASE_INSENSITIVE',
                        simulated_output TEXT,
                        hint TEXT,
                        skill_category TEXT NOT NULL DEFAULT 'General',
                        time_limit_seconds INTEGER,
                        due_date TEXT NOT NULL,
                        interval_days INTEGER NOT NULL DEFAULT 0,
                        ease_factor REAL NOT NULL DEFAULT 2.5,
                        review_count INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(deck_id) REFERENCES decks(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS review_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        flashcard_id INTEGER NOT NULL,
                        rating TEXT NOT NULL,
                        previous_interval_days INTEGER NOT NULL,
                        new_interval_days INTEGER NOT NULL,
                        reviewed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        submitted_in_time INTEGER NOT NULL DEFAULT 1,
                        boss_battle INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(flashcard_id) REFERENCES flashcards(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS user_progress (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        xp INTEGER NOT NULL DEFAULT 0,
                        level INTEGER NOT NULL DEFAULT 1,
                        streak_days INTEGER NOT NULL DEFAULT 0,
                        last_review_date TEXT,
                        total_reviews INTEGER NOT NULL DEFAULT 0,
                        missed_day_count INTEGER NOT NULL DEFAULT 0,
                        streak_freeze_count INTEGER NOT NULL DEFAULT 0,
                        recovery_quest_active INTEGER NOT NULL DEFAULT 0,
                        daily_workload_mode TEXT NOT NULL DEFAULT 'NORMAL'
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS daily_quests (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        quest_date TEXT NOT NULL UNIQUE,
                        objective_type TEXT NOT NULL,
                        skill_category TEXT,
                        target_count INTEGER NOT NULL,
                        current_count INTEGER NOT NULL DEFAULT 0,
                        completed INTEGER NOT NULL DEFAULT 0,
                        xp_awarded INTEGER NOT NULL DEFAULT 0,
                        xp_reward INTEGER NOT NULL DEFAULT 25
                    )
                    """);
            ensureFlashcardColumns(connection);
            ensureReviewHistoryColumns(connection);
            ensureUserProgressColumns(connection);
            statement.execute("INSERT OR IGNORE INTO user_progress (id, xp, level, streak_days, total_reviews) VALUES (1, 0, 1, 0, 0)");
            seedStarterContent(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize CodeFit database", exception);
        }
    }

    private static void ensureFlashcardColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "flashcards", "card_type", "TEXT NOT NULL DEFAULT 'RECALL'");
        addColumnIfMissing(connection, "flashcards", "accepted_answers", "TEXT");
        addColumnIfMissing(connection, "flashcards", "validation_mode", "TEXT NOT NULL DEFAULT 'CASE_INSENSITIVE'");
        addColumnIfMissing(connection, "flashcards", "simulated_output", "TEXT");
        addColumnIfMissing(connection, "flashcards", "hint", "TEXT");
        addColumnIfMissing(connection, "flashcards", "skill_category", "TEXT NOT NULL DEFAULT 'General'");
        addColumnIfMissing(connection, "flashcards", "time_limit_seconds", "INTEGER");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flashcards SET accepted_answers = back WHERE accepted_answers IS NULL OR trim(accepted_answers) = ''");
            statement.executeUpdate("UPDATE flashcards SET skill_category = 'General' WHERE skill_category IS NULL OR trim(skill_category) = ''");
        }
    }

    private static void ensureReviewHistoryColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "review_history", "submitted_in_time", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(connection, "review_history", "boss_battle", "INTEGER NOT NULL DEFAULT 0");
    }

    private static void ensureUserProgressColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "user_progress", "missed_day_count", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "user_progress", "streak_freeze_count", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "user_progress", "recovery_quest_active", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "user_progress", "daily_workload_mode", "TEXT NOT NULL DEFAULT 'NORMAL'");
    }

    private static void addColumnIfMissing(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        if (hasColumn(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private static boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void seedStarterContent(Connection connection) throws SQLException {
        String[][] decks = {
                {"Java BE 01 - Core Java & OOP", "The foundation of the backend roadmap: Java syntax, classes, inheritance, polymorphism, exceptions, and JVM concepts needed before building services."},
                {"Java BE 02 - Collections, Streams & Generics", "Build fluency with the data structures, generic types, lambdas, and stream pipelines used to transform backend request and persistence data safely."},
                {"Java BE 03 - JDBC & SQL", "Connect Java applications to relational databases with SQL, JDBC, prepared statements, transactions, and schema design fundamentals."},
                {"Java BE 04 - Spring Boot REST APIs", "Move from core Java into service development by creating Spring Boot controllers, request/response DTOs, validation, and RESTful endpoints."},
                {"Java BE 05 - Persistence with JPA/Hibernate", "Model backend domain data with entities, repositories, relationships, query methods, and transaction boundaries using JPA and Hibernate."},
                {"Java BE 06 - Testing with JUnit/Mockito", "Protect backend behavior with unit tests, mocks, integration tests, test slices, and repeatable verification of service and controller logic."},
                {"Java BE 07 - Security & Auth", "Add production-minded security concepts such as authentication, authorization, password handling, JWT/session tradeoffs, and Spring Security filters."},
                {"Java BE 08 - Build, Git & Deployment", "Finish the roadmap by packaging services, managing dependencies, using Git workflows, configuring environments, and preparing backend apps for deployment."}
        };
        String[][] flashcards = {
                {"Java BE 01 - Core Java & OOP", "What Java keyword creates a subclass relationship?", "extends", CardType.RECALL.name(), "extends", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Java Syntax", null},
                {"Java BE 01 - Core Java & OOP", "What does JVM stand for?", "Java Virtual Machine", CardType.RECALL.name(), "Java Virtual Machine", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Java Runtime", null},
                {"Java BE 02 - Collections, Streams & Generics", "Which collection keeps insertion order and allows indexed access?", "ArrayList", CardType.RECALL.name(), "ArrayList", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Collections", null},
                {"Java BE 03 - JDBC & SQL", "What JDBC object executes parameterized SQL safely?", "PreparedStatement", CardType.RECALL.name(), "PreparedStatement", ValidationMode.CASE_INSENSITIVE.name(), null, null, "SQL", null},
                {"Java BE 03 - JDBC & SQL", "Why should backend code prefer PreparedStatement over string-concatenated SQL?", "PreparedStatement binds parameters separately from SQL text, which helps prevent SQL injection and lets the driver handle type conversion.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("PreparedStatement prevents SQL injection by binding parameters", "It uses bind parameters instead of concatenating user input")), ValidationMode.CASE_INSENSITIVE.name(), null, "Mention parameter binding and SQL injection.", "SQL", null},
                {"Java BE 03 - JDBC & SQL", "users(id, email, created_at): write SQL to list the 5 newest user emails.", "SELECT email FROM users ORDER BY created_at DESC LIMIT 5;", CardType.SQL_QUERY.name(), AcceptedAnswerCodec.encode(List.of("SELECT email FROM users ORDER BY created_at DESC LIMIT 5;", "SELECT email FROM users ORDER BY created_at DESC LIMIT 5")), ValidationMode.NORMALIZED_SPACING.name(), null, "Order newest first, then cap the result size.", "SQL", null},
                {"Java BE 03 - JDBC & SQL", "What is a database transaction?", "A transaction is a unit of work that should commit completely or roll back completely so related changes stay consistent.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("unit of work that commits or rolls back", "all-or-nothing unit of work")), ValidationMode.CASE_INSENSITIVE.name(), null, "Think ACID and all-or-nothing changes.", "SQL", null},
                {"Java BE 03 - JDBC & SQL", "Which transaction isolation issue occurs when one transaction reads uncommitted changes from another?", "Dirty read", CardType.RECALL.name(), "Dirty read", ValidationMode.CASE_INSENSITIVE.name(), null, null, "SQL", null},
                {"Java BE 04 - Spring Boot REST APIs", "Which Spring annotation combines @Controller and @ResponseBody for JSON REST endpoints?", "@RestController", CardType.RECALL.name(), "@RestController", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Spring REST", null},
                {"Java BE 04 - Spring Boot REST APIs", "Which annotation maps an HTTP GET request to a controller method?", "@GetMapping", CardType.RECALL.name(), "@GetMapping", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Spring REST", null},
                {"Java BE 04 - Spring Boot REST APIs", "Which annotation maps an HTTP POST request to a controller method?", "@PostMapping", CardType.RECALL.name(), "@PostMapping", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Spring REST", null},
                {"Java BE 04 - Spring Boot REST APIs", "What HTTP status code is typically returned after successfully creating a resource?", "201 Created", CardType.RECALL.name(), AcceptedAnswerCodec.encode(List.of("201 Created", "201")), ValidationMode.CASE_INSENSITIVE.name(), null, null, "Spring REST", null},
                {"Java BE 04 - Spring Boot REST APIs", "What is the boundary between a DTO and an entity in a REST API?", "DTOs shape external request/response data, while entities model persisted domain state and should not be exposed directly as the API contract.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("DTOs are API contracts and entities are persistence models", "DTO for request response, entity for database domain")), ValidationMode.CASE_INSENSITIVE.name(), null, "Separate API shape from persistence shape.", "Spring REST", null},
                {"Java BE 04 - Spring Boot REST APIs", "Predict the response body: @GetMapping(\"/ping\") public String ping() { return \"pong\"; }", "pong", CardType.CODE_OUTPUT.name(), "pong", ValidationMode.NORMALIZED_SPACING.name(), "pong", "A @RestController writes the returned String to the HTTP response body.", "Spring REST", "30"},
                {"Java BE 04 - Spring Boot REST APIs", "Which Spring Boot annotation marks the main application class and enables component scanning and auto-configuration?", "@SpringBootApplication", CardType.RECALL.name(), "@SpringBootApplication", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Deployment", null},
                {"Java BE 04 - Spring Boot REST APIs", "Why is constructor injection preferred for required Spring dependencies?", "Constructor injection makes dependencies explicit, supports final fields, and fails fast when a required bean is missing.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("explicit dependencies final fields fail fast", "required dependencies are explicit and immutable")), ValidationMode.CASE_INSENSITIVE.name(), null, "Think testability, immutability, and required collaborators.", "Spring REST", null},
                {"Java BE 05 - Persistence with JPA/Hibernate", "Which JPA annotation identifies the primary key field of an entity?", "@Id", CardType.RECALL.name(), "@Id", ValidationMode.CASE_INSENSITIVE.name(), null, null, "JPA", null},
                {"Java BE 05 - Persistence with JPA/Hibernate", "Which JPA annotation models a parent entity with many child entities?", "@OneToMany", CardType.RECALL.name(), "@OneToMany", ValidationMode.CASE_INSENSITIVE.name(), null, null, "JPA", null},
                {"Java BE 05 - Persistence with JPA/Hibernate", "What does @Entity tell JPA?", "It marks a Java class as a persistent entity that JPA can map to a database table.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("persistent entity mapped to a database table", "class mapped to a database table")), ValidationMode.CASE_INSENSITIVE.name(), null, "Mention persistence and table mapping.", "JPA", null},
                {"Java BE 06 - Testing with JUnit/Mockito", "What is the main difference between a unit test and an integration test?", "A unit test isolates a small piece of code, often with mocks; an integration test verifies multiple real components working together.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("unit isolates code, integration tests components together", "unit test mocks dependencies integration test uses real components")), ValidationMode.CASE_INSENSITIVE.name(), null, "Contrast isolation with wiring multiple pieces together.", "Testing", null},
                {"Java BE 06 - Testing with JUnit/Mockito", "Which Mockito method defines a stubbed return value for a mock call?", "when", CardType.RECALL.name(), AcceptedAnswerCodec.encode(List.of("when", "Mockito.when")), ValidationMode.CASE_INSENSITIVE.name(), null, null, "Testing", null},
                {"Java BE 07 - Security & Auth", "What does JWT stand for in backend authentication?", "JSON Web Token", CardType.RECALL.name(), "JSON Web Token", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Security", null},
                {"Java BE 07 - Security & Auth", "What is a key tradeoff between server-side sessions and JWTs?", "Sessions keep auth state on the server and are easy to revoke; JWTs are usually stateless for the server but need careful expiration and revocation design.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("sessions are server-side and revocable, JWTs are stateless but harder to revoke", "JWT stateless session server state")), ValidationMode.CASE_INSENSITIVE.name(), null, "Compare where auth state lives and how revocation works.", "Security", null},
                {"Java BE 08 - Build, Git & Deployment", "Maven command: run the test phase for this project.", "mvn test", CardType.COMMAND.name(), AcceptedAnswerCodec.encode(List.of("mvn test", "./mvnw test")), ValidationMode.COMMAND_NORMALIZED.name(), "Runs unit tests and earlier lifecycle phases needed for test execution.", "Use the Maven Wrapper variant if the project includes mvnw.", "Deployment", "45"},
                {"Java BE 08 - Build, Git & Deployment", "Maven command: clean previous build outputs and package the application artifact.", "mvn clean package", CardType.COMMAND.name(), AcceptedAnswerCodec.encode(List.of("mvn clean package", "./mvnw clean package")), ValidationMode.COMMAND_NORMALIZED.name(), "Deletes target/ and builds the packaged artifact after running lifecycle phases up to package.", "clean removes generated outputs; package creates the jar or war.", "Deployment", "60"},
                {"Java BE 08 - Build, Git & Deployment", "Spring Boot command-line option: start the app with the prod profile active.", "java -jar app.jar --spring.profiles.active=prod", CardType.COMMAND.name(), AcceptedAnswerCodec.encode(List.of("java -jar app.jar --spring.profiles.active=prod", "SPRING_PROFILES_ACTIVE=prod java -jar app.jar")), ValidationMode.COMMAND_NORMALIZED.name(), "Application starts with prod profile-specific configuration enabled.", "Profiles select environment-specific beans and properties.", "Deployment", "75"},
                {"Java BE 08 - Build, Git & Deployment", "Why should secrets and environment-specific settings live outside committed source code?", "External configuration lets each environment provide its own values and prevents committing credentials into version control.", CardType.CONCEPT.name(), AcceptedAnswerCodec.encode(List.of("prevents committing secrets and supports per-environment config", "keeps credentials out of source control")), ValidationMode.CASE_INSENSITIVE.name(), null, "Think profiles, environment variables, and source control safety.", "Deployment", null},
                {"Java BE 04 - Spring Boot REST APIs", "Which Spring annotation centralizes exception handling across controllers?", "@ControllerAdvice", CardType.RECALL.name(), AcceptedAnswerCodec.encode(List.of("@ControllerAdvice", "@RestControllerAdvice")), ValidationMode.CASE_INSENSITIVE.name(), null, null, "Spring REST", null}
        };

        try (PreparedStatement insertDeck = connection.prepareStatement("INSERT OR IGNORE INTO decks (name, description) VALUES (?, ?)");
             PreparedStatement selectDeckId = connection.prepareStatement("SELECT id FROM decks WHERE name = ?");
             PreparedStatement insertFlashcard = connection.prepareStatement("""
                     INSERT INTO flashcards (
                         deck_id, front, back, card_type, accepted_answers, validation_mode,
                         simulated_output, hint, skill_category, time_limit_seconds, due_date
                     )
                     SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, date('now')
                     WHERE NOT EXISTS (
                         SELECT 1 FROM flashcards WHERE deck_id = ? AND front = ?
                     )
                     """)) {
            for (String[] deck : decks) {
                insertDeck.setString(1, deck[0]);
                insertDeck.setString(2, deck[1]);
                insertDeck.executeUpdate();
            }

            for (String[] flashcard : flashcards) {
                int deckId = findDeckId(selectDeckId, flashcard[0]);
                insertFlashcard.setInt(1, deckId);
                insertFlashcard.setString(2, flashcard[1]);
                insertFlashcard.setString(3, flashcard[2]);
                insertFlashcard.setString(4, flashcard[3]);
                insertFlashcard.setString(5, flashcard[4]);
                insertFlashcard.setString(6, flashcard[5]);
                insertFlashcard.setString(7, flashcard[6]);
                insertFlashcard.setString(8, flashcard[7]);
                insertFlashcard.setString(9, flashcard[8]);
                if (flashcard[9] == null) {
                    insertFlashcard.setNull(10, Types.INTEGER);
                } else {
                    insertFlashcard.setInt(10, Integer.parseInt(flashcard[9]));
                }
                insertFlashcard.setInt(11, deckId);
                insertFlashcard.setString(12, flashcard[1]);
                insertFlashcard.executeUpdate();
            }
        }
    }

    private static int findDeckId(PreparedStatement selectDeckId, String deckName) throws SQLException {
        selectDeckId.setString(1, deckName);
        try (ResultSet resultSet = selectDeckId.executeQuery()) {
            if (resultSet.next()) {
                return resultSet.getInt("id");
            }
        }
        throw new SQLException("Unable to find starter deck: " + deckName);
    }

    private static boolean hasRows(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT EXISTS(SELECT 1 FROM " + tableName + " LIMIT 1)")) {
            return resultSet.next() && resultSet.getInt(1) == 1;
        }
    }
}

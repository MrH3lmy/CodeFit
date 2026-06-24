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
                        total_reviews INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            ensureFlashcardColumns(connection);
            ensureReviewHistoryColumns(connection);
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
        String defaultPromptHint = "Start with the task, then specify format and constraints.";
        String[][] flashcards = {
                {"Java BE 01 - Core Java & OOP", "What Java keyword creates a subclass relationship?", "extends", CardType.RECALL.name(), "extends", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Java Syntax", null},
                {"Java BE 02 - Collections, Streams & Generics", "Which collection keeps insertion order and allows indexed access?", "ArrayList", CardType.RECALL.name(), "ArrayList", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Collections", null},
                {"Java BE 01 - Core Java & OOP", "What does JVM stand for?", "Java Virtual Machine", CardType.RECALL.name(), "Java Virtual Machine", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Java Runtime", null},
                {"Java BE 04 - Spring Boot REST APIs", "Which Spring annotation marks a class as a REST controller?", "@RestController", CardType.RECALL.name(), "@RestController", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Spring REST", null},
                {"Java BE 05 - Persistence with JPA/Hibernate", "Which JPA annotation marks a class as a database-backed entity?", "@Entity", CardType.RECALL.name(), "@Entity", ValidationMode.CASE_INSENSITIVE.name(), null, null, "JPA", null},
                {"Java BE 06 - Testing with JUnit/Mockito", "Which Mockito method defines a stubbed return value for a mock call?", "when", CardType.RECALL.name(), "when|Mockito.when", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Testing", null},
                {"Java BE 07 - Security & Auth", "What does JWT stand for in backend authentication?", "JSON Web Token", CardType.RECALL.name(), "JSON Web Token", ValidationMode.CASE_INSENSITIVE.name(), null, null, "Security", null},
                {"Java BE 03 - JDBC & SQL", "What SQL command creates a table?", "CREATE TABLE", CardType.RECALL.name(), "CREATE TABLE", ValidationMode.CASE_INSENSITIVE.name(), null, null, "SQL", null},
                {"Java BE 03 - JDBC & SQL", "What JDBC object executes parameterized SQL safely?", "PreparedStatement", CardType.RECALL.name(), "PreparedStatement", ValidationMode.CASE_INSENSITIVE.name(), null, null, "JDBC", null},
                {"Java BE 03 - JDBC & SQL", "What SQLite clause avoids duplicate seed rows?", "INSERT OR IGNORE", CardType.RECALL.name(), "INSERT OR IGNORE", ValidationMode.CASE_INSENSITIVE.name(), null, null, "SQLite", null},
                {"Java BE 01 - Core Java & OOP", "Prompt command: ask for a concise explanation of recursion.", "Explain recursion in 3 concise bullet points.", CardType.COMMAND.name(), "Explain recursion in 3 concise bullet points.|Explain recursion briefly in 3 bullets.", ValidationMode.COMMAND_NORMALIZED.name(), "- Recursion is when a function calls itself.\n- It needs a base case to stop.\n- Each call should move closer to that base case.", defaultPromptHint, "Prompt Commands", "60"},
                {"Java BE 08 - Build, Git & Deployment", "Prompt command: ask for step-by-step instructions to set up a Java project.", "Give me step-by-step instructions to set up a Java project.", CardType.COMMAND.name(), "Give me step-by-step instructions to set up a Java project.|Walk me through setting up a Java project step by step.", ValidationMode.COMMAND_NORMALIZED.name(), "1. Install a JDK.\n2. Create a project folder.\n3. Add source files and build configuration.\n4. Run tests from the terminal.", defaultPromptHint, "Prompt Commands", "75"},
                {"Java BE 03 - JDBC & SQL", "Prompt command: ask the assistant to generate examples of SQL joins.", "Generate 3 examples of SQL joins with short explanations.", CardType.COMMAND.name(), "Generate 3 examples of SQL joins with short explanations.|Show me three SQL join examples and explain each briefly.", ValidationMode.COMMAND_NORMALIZED.name(), "INNER JOIN returns matching rows, LEFT JOIN keeps all left-table rows, and CROSS JOIN pairs every row from both tables.", defaultPromptHint, "Prompt Commands", "75"},
                {"Java BE 06 - Testing with JUnit/Mockito", "Prompt command: ask for code review feedback on a method.", "Review this method and suggest specific improvements.", CardType.COMMAND.name(), "Review this method and suggest specific improvements.|Give me code review feedback on this method.", ValidationMode.COMMAND_NORMALIZED.name(), "Review notes: clarify naming, validate inputs, simplify branching, and add focused tests for edge cases.", defaultPromptHint, "Prompt Commands", "60"},
                {"Java BE 08 - Build, Git & Deployment", "Prompt command: ask for a command-line workflow for running tests.", "Give me a command-line workflow to build the project and run tests.", CardType.COMMAND.name(), "Give me a command-line workflow to build the project and run tests.|Show a terminal workflow for building and testing this project.", ValidationMode.COMMAND_NORMALIZED.name(), "Example workflow: git status, ./mvnw clean test, inspect failures, fix issues, then rerun ./mvnw test.", defaultPromptHint, "Prompt Commands", "75"},
                {"Java BE 06 - Testing with JUnit/Mockito", "Prompt command: ask for debugging help with an error message.", "Help me debug this error. Explain likely causes and next checks.", CardType.COMMAND.name(), "Help me debug this error. Explain likely causes and next checks.|Debug this error and list likely causes plus next steps.", ValidationMode.COMMAND_NORMALIZED.name(), "Likely causes: missing dependency, invalid configuration, or bad input. Next checks: read the stack trace and reproduce with minimal data.", defaultPromptHint, "Prompt Commands", "75"},
                {"Java BE 04 - Spring Boot REST APIs", "Prompt command: ask for a 7-day Spring Boot REST API study plan.", "Create a 7-day Spring Boot REST API study plan with daily practice tasks.", CardType.COMMAND.name(), "Create a 7-day Spring Boot REST API study plan with daily practice tasks.|Make me a one-week Spring Boot REST API study plan with practice tasks.", ValidationMode.COMMAND_NORMALIZED.name(), "Day 1: Controllers. Day 2: DTOs. Day 3: Validation. Day 4: Services. Day 5: Error handling. Day 6: Tests. Day 7: Mini API.", defaultPromptHint, "Prompt Commands", "90"},
                {"Java BE 03 - JDBC & SQL", "Prompt command: ask for flashcards from a topic.", "Create 10 flashcards about JDBC basics with answers.", CardType.COMMAND.name(), "Create 10 flashcards about JDBC basics with answers.|Make ten Q&A flashcards on JDBC basics.", ValidationMode.COMMAND_NORMALIZED.name(), "Example flashcard: Q: Which JDBC class runs parameterized queries? A: PreparedStatement.", defaultPromptHint, "Prompt Commands", "60"},
                {"Java BE 02 - Collections, Streams & Generics", "Prompt command: ask the assistant to compare alternatives.", "Compare ArrayList and LinkedList, including tradeoffs and when to use each.", CardType.COMMAND.name(), "Compare ArrayList and LinkedList, including tradeoffs and when to use each.|Explain the tradeoffs between ArrayList and LinkedList.", ValidationMode.COMMAND_NORMALIZED.name(), "ArrayList is usually better for indexed access and iteration; LinkedList can help with frequent deque operations.", defaultPromptHint, "Prompt Commands", "90"},
                {"Java BE 08 - Build, Git & Deployment", "Prompt command: ask for safe edits or refactors.", "Suggest a safe refactor for this code without changing behavior.", CardType.COMMAND.name(), "Suggest a safe refactor for this code without changing behavior.|Refactor this safely while preserving behavior.", ValidationMode.COMMAND_NORMALIZED.name(), "Safe refactor plan: add tests, make one small change, run tests, and avoid behavior-changing rewrites.", defaultPromptHint, "Prompt Commands", "75"}
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

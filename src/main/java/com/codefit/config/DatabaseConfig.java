package com.codefit.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

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
                {"Java Core", "Syntax, OOP, collections, and core Java interview reps."},
                {"JavaFX UI", "FXML, scenes, controllers, and desktop application patterns."},
                {"SQL & Persistence", "SQLite, JDBC, schema design, and repository fundamentals."},
                {"Prompt Commands Basics", "Practice reusable assistant prompts for explanations, examples, workflows, debugging, and safe code changes."}
        };
        String[][] flashcards = {
                {"Java Core", "What Java keyword creates a subclass relationship?", "extends", "extends", "Java Syntax"},
                {"Java Core", "Which collection keeps insertion order and allows indexed access?", "ArrayList", "ArrayList", "Collections"},
                {"Java Core", "What does JVM stand for?", "Java Virtual Machine", "Java Virtual Machine", "Java Runtime"},
                {"JavaFX UI", "Which JavaFX file format describes a scene graph declaratively?", "FXML", "FXML", "JavaFX UI"},
                {"JavaFX UI", "Which JavaFX class usually owns one application window?", "Stage", "Stage", "JavaFX UI"},
                {"JavaFX UI", "What method loads an FXML resource?", "FXMLLoader.load()", "FXMLLoader.load()", "JavaFX UI"},
                {"SQL & Persistence", "What SQL command creates a table?", "CREATE TABLE", "CREATE TABLE", "SQL"},
                {"SQL & Persistence", "What JDBC object executes parameterized SQL safely?", "PreparedStatement", "PreparedStatement", "JDBC"},
                {"SQL & Persistence", "What SQLite clause avoids duplicate seed rows?", "INSERT OR IGNORE", "INSERT OR IGNORE", "SQLite"},
                {"Prompt Commands Basics", "Prompt command: ask for a concise explanation of recursion.", "Explain recursion in 3 concise bullet points.", "Explain recursion in 3 concise bullet points.|Explain recursion briefly in 3 bullets.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask for step-by-step instructions to set up a Java project.", "Give me step-by-step instructions to set up a Java project.", "Give me step-by-step instructions to set up a Java project.|Walk me through setting up a Java project step by step.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask the assistant to generate examples of SQL joins.", "Generate 3 examples of SQL joins with short explanations.", "Generate 3 examples of SQL joins with short explanations.|Show me three SQL join examples and explain each briefly.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask for code review feedback on a method.", "Review this method and suggest specific improvements.", "Review this method and suggest specific improvements.|Give me code review feedback on this method.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask for a command-line workflow for running tests.", "Give me a command-line workflow to build the project and run tests.", "Give me a command-line workflow to build the project and run tests.|Show a terminal workflow for building and testing this project.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask for debugging help with an error message.", "Help me debug this error. Explain likely causes and next checks.", "Help me debug this error. Explain likely causes and next checks.|Debug this error and list likely causes plus next steps.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask for a 7-day study plan for JavaFX.", "Create a 7-day JavaFX study plan with daily practice tasks.", "Create a 7-day JavaFX study plan with daily practice tasks.|Make me a one-week JavaFX study plan with practice tasks.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask for flashcards from a topic.", "Create 10 flashcards about JDBC basics with answers.", "Create 10 flashcards about JDBC basics with answers.|Make ten Q&A flashcards on JDBC basics.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask the assistant to compare alternatives.", "Compare ArrayList and LinkedList, including tradeoffs and when to use each.", "Compare ArrayList and LinkedList, including tradeoffs and when to use each.|Explain the tradeoffs between ArrayList and LinkedList.", "Prompt Commands"},
                {"Prompt Commands Basics", "Prompt command: ask for safe edits or refactors.", "Suggest a safe refactor for this code without changing behavior.", "Suggest a safe refactor for this code without changing behavior.|Refactor this safely while preserving behavior.", "Prompt Commands"}
        };

        try (PreparedStatement insertDeck = connection.prepareStatement("INSERT OR IGNORE INTO decks (name, description) VALUES (?, ?)");
             PreparedStatement selectDeckId = connection.prepareStatement("SELECT id FROM decks WHERE name = ?");
             PreparedStatement insertFlashcard = connection.prepareStatement("""
                     INSERT INTO flashcards (deck_id, front, back, accepted_answers, skill_category, due_date)
                     SELECT ?, ?, ?, ?, ?, date('now')
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
                insertFlashcard.setInt(6, deckId);
                insertFlashcard.setString(7, flashcard[1]);
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

package com.codefit.config;

import java.sql.Connection;
import java.sql.DriverManager;
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
        if (hasRows(connection, "decks")) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO decks (name, description) VALUES
                    ('Java Core', 'Syntax, OOP, collections, and core Java interview reps.'),
                    ('JavaFX UI', 'FXML, scenes, controllers, and desktop application patterns.'),
                    ('SQL & Persistence', 'SQLite, JDBC, schema design, and repository fundamentals.')
                    """);
            statement.executeUpdate("""
                    INSERT INTO flashcards (deck_id, front, back, accepted_answers, skill_category, due_date) VALUES
                    (1, 'What Java keyword creates a subclass relationship?', 'extends', 'extends', 'Java Syntax', date('now')),
                    (1, 'Which collection keeps insertion order and allows indexed access?', 'ArrayList', 'ArrayList', 'Collections', date('now')),
                    (1, 'What does JVM stand for?', 'Java Virtual Machine', 'Java Virtual Machine', 'Java Runtime', date('now')),
                    (2, 'Which JavaFX file format describes a scene graph declaratively?', 'FXML', 'FXML', 'JavaFX UI', date('now')),
                    (2, 'Which JavaFX class usually owns one application window?', 'Stage', 'Stage', 'JavaFX UI', date('now')),
                    (2, 'What method loads an FXML resource?', 'FXMLLoader.load()', 'FXMLLoader.load()', 'JavaFX UI', date('now')),
                    (3, 'What SQL command creates a table?', 'CREATE TABLE', 'CREATE TABLE', 'SQL', date('now')),
                    (3, 'What JDBC object executes parameterized SQL safely?', 'PreparedStatement', 'PreparedStatement', 'JDBC', date('now')),
                    (3, 'What SQLite clause avoids duplicate seed rows?', 'INSERT OR IGNORE', 'INSERT OR IGNORE', 'SQLite', date('now'))
                    """);
        }
    }

    private static boolean hasRows(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT EXISTS(SELECT 1 FROM " + tableName + " LIMIT 1)")) {
            return resultSet.next() && resultSet.getInt(1) == 1;
        }
    }
}

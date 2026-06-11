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
            statement.execute("INSERT OR IGNORE INTO user_progress (id, xp, level, streak_days, total_reviews) VALUES (1, 0, 1, 0, 0)");
            seedStarterContent(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize CodeFit database", exception);
        }
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
                    INSERT INTO flashcards (deck_id, front, back, due_date) VALUES
                    (1, 'What Java keyword creates a subclass relationship?', 'extends', date('now')),
                    (1, 'Which collection keeps insertion order and allows indexed access?', 'ArrayList', date('now')),
                    (1, 'What does JVM stand for?', 'Java Virtual Machine', date('now')),
                    (2, 'Which JavaFX file format describes a scene graph declaratively?', 'FXML', date('now')),
                    (2, 'Which JavaFX class usually owns one application window?', 'Stage', date('now')),
                    (2, 'What method loads an FXML resource?', 'FXMLLoader.load()', date('now')),
                    (3, 'What SQL command creates a table?', 'CREATE TABLE', date('now')),
                    (3, 'What JDBC object executes parameterized SQL safely?', 'PreparedStatement', date('now')),
                    (3, 'What SQLite clause avoids duplicate seed rows?', 'INSERT OR IGNORE', date('now'))
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

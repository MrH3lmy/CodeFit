package com.codefit.desktop;

import com.codefit.config.DatabaseConfig;

import java.nio.file.*;
import java.sql.*;

/** Test-only synthetic profile builder. Never bundled with the application. */
public final class LearnerFixture {
    public static void create(Path file) throws Exception {
        if (Files.exists(file)) throw new IllegalArgumentException("Fixture must be new");
        Files.createDirectories(file.toAbsolutePath().getParent());
        String prior = DatabaseConfig.currentDatabaseUrl();
        try {
            DatabaseConfig.useDatabaseFile(file);
            DatabaseConfig.initialize();
            try (var c = DatabaseConfig.getConnection();
                    var s = c.createStatement()) {
                s.executeUpdate("UPDATE user_progress SET xp=750,level=8,total_reviews=999");
                s.executeUpdate(
                        "INSERT INTO social_profile(id,display_name,bio) VALUES(1,'Ada"
                            + " Learner','PRIVATE-PROFILE-SECRET')");
                s.executeUpdate(
                        "UPDATE flashcards SET"
                            + " card_state='SUSPENDED',back='PRIVATE-ANSWER-SECRET'");
                s.executeUpdate(
                        "UPDATE flashcards SET card_state='REVIEW',due_date='2000-01-01' WHERE id"
                            + " IN (SELECT id FROM flashcards ORDER BY id LIMIT 3)");
                for (int i = 0; i < 4; i++)
                    s.executeUpdate(
                            "INSERT INTO"
                                + " review_history(flashcard_id,rating,previous_interval_days,new_interval_days,reviewed_at)"
                                + " SELECT min(id),'GOOD',1,2,'2025-01-02 12:00:00' FROM"
                                + " flashcards");
            }
        } finally {
            DatabaseConfig.useDatabaseUrl(prior);
        }
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        create(file);
        String mode = args.length > 1 ? args[1] : "normal";
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var s = c.createStatement()) {
            switch (mode) {
                case "future" ->
                        s.executeUpdate(
                                "INSERT INTO schema_migrations(version,description)"
                                    + " VALUES(26,'future')");
                case "older" -> s.executeUpdate("DELETE FROM schema_migrations WHERE version=25");
                case "missing" -> {
                    s.executeUpdate("DELETE FROM user_progress");
                    s.executeUpdate("DELETE FROM social_profile");
                }
                case "wal" -> {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.executeUpdate("UPDATE user_progress SET xp=751");
                    System.out.println("ready");
                    System.out.flush();
                    System.in.read();
                }
                case "hot" -> {
                    s.execute("PRAGMA cache_size=1");
                    c.setAutoCommit(false);
                    s.executeUpdate("UPDATE flashcards SET back=hex(zeroblob(4096))");
                    System.out.println("staged");
                    System.out.flush();
                    System.in.read();
                }
                case "normal" -> {}
                default -> throw new IllegalArgumentException();
            }
        }
    }
}

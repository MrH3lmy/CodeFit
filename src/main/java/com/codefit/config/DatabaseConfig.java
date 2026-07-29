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
import com.codefit.service.SqlCardSpec;
import com.codefit.service.SqlCardSpecCodec;

import java.util.List;

public final class DatabaseConfig {
    private static final String DATABASE_URL = "jdbc:sqlite:codefit.db";

    /**
     * Fixture for the seeded "5 newest user emails" SQL_QUERY starter card: an isolated
     * users(id, email, created_at) table the learner's query is executed against, graded by
     * comparing its result to the reference query's result (see {@link SqlCardSpecCodec}).
     */
    private static final SqlCardSpec NEWEST_USER_EMAILS_SQL_SPEC = new SqlCardSpec(
            "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL, created_at TEXT NOT NULL);",
            "INSERT INTO users (id, email, created_at) VALUES "
                    + "(1,'ada@example.com','2024-01-01'),(2,'ben@example.com','2024-01-02'),"
                    + "(3,'cleo@example.com','2024-01-03'),(4,'drew@example.com','2024-01-04'),"
                    + "(5,'eva@example.com','2024-01-05'),(6,'finn@example.com','2024-01-06'),"
                    + "(7,'grace@example.com','2024-01-07');",
            "SELECT email FROM users ORDER BY created_at DESC LIMIT 5;",
            null, true, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

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
            // Separate from flashcards/review_history by design (#104): a weekly transfer assessment
            // must draw on scenarios the learner has never seen in normal review, and its results must
            // never silently alter a flashcard's schedule, so the assessment bank gets its own tables.
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS assessment_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        skill_category TEXT NOT NULL DEFAULT 'General',
                        module_name TEXT NOT NULL,
                        card_type TEXT NOT NULL DEFAULT 'CONCEPT',
                        validation_mode TEXT NOT NULL DEFAULT 'CASE_INSENSITIVE',
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS assessment_variants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        assessment_item_id INTEGER NOT NULL,
                        variant_index INTEGER NOT NULL,
                        scenario TEXT NOT NULL,
                        accepted_answers TEXT,
                        reference_answer TEXT NOT NULL,
                        simulated_output TEXT,
                        hint TEXT,
                        FOREIGN KEY(assessment_item_id) REFERENCES assessment_items(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS assessment_attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        assessment_item_id INTEGER NOT NULL,
                        variant_index INTEGER NOT NULL,
                        skill_category TEXT NOT NULL,
                        module_name TEXT NOT NULL,
                        correct INTEGER NOT NULL,
                        submitted_answer TEXT,
                        response_time_ms INTEGER,
                        attempted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        run_id TEXT,
                        FOREIGN KEY(assessment_item_id) REFERENCES assessment_items(id) ON DELETE CASCADE
                    )
                    """);
            ensureFlashcardColumns(connection);
            ensureReviewHistoryColumns(connection);
            ensureUserProgressColumns(connection);
            statement.execute("INSERT OR IGNORE INTO user_progress (id, xp, level, streak_days, total_reviews) VALUES (1, 0, 1, 0, 0)");
            createProblemSolvingTables(connection);
            ensureProblemSolvingWorkspaceColumns(connection);
            ensureProblemProgressReflectionColumns(connection);
            ensureImportAttributionSchema(connection);
            ensureProblemGuidanceSchema(connection);
            ensureJavaSolutionDraftSchema(connection);
            SchemaMigrator.migrate(connection);
            seedStarterContent(connection);
            seedAssessmentBank(connection);
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
        addColumnIfMissing(connection, "flashcards", "card_state", "TEXT NOT NULL DEFAULT 'NEW'");
        addColumnIfMissing(connection, "flashcards", "introduced_at", "TEXT");
        // Deliberately no FOREIGN KEY / ON DELETE clause on source_problem_id (#148): deleting the
        // source problem must never cascade-delete a flashcard already created from it.
        addColumnIfMissing(connection, "flashcards", "source_problem_id", "INTEGER");
        addColumnIfMissing(connection, "flashcards", "source_reflection_field", "TEXT");
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE flashcards SET accepted_answers = back WHERE accepted_answers IS NULL OR trim(accepted_answers) = ''");
            statement.executeUpdate("UPDATE flashcards SET skill_category = 'General' WHERE skill_category IS NULL OR trim(skill_category) = ''");
        }
    }

    private static void ensureReviewHistoryColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "review_history", "submitted_in_time", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(connection, "review_history", "boss_battle", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "review_history", "validation_result", "TEXT");
        addColumnIfMissing(connection, "review_history", "submitted_answer", "TEXT");
        addColumnIfMissing(connection, "review_history", "response_time_ms", "INTEGER");
        addColumnIfMissing(connection, "review_history", "hint_used", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "review_history", "session_id", "TEXT");
        addColumnIfMissing(connection, "review_history", "confidence", "TEXT");
    }

    private static void ensureUserProgressColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "user_progress", "missed_day_count", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "user_progress", "streak_freeze_count", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "user_progress", "recovery_quest_active", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "user_progress", "daily_workload_mode", "TEXT NOT NULL DEFAULT 'NORMAL'");
        // active_training_path/focus_module_order are a pure preference pointer (#110): switching
        // focus only ever updates these two columns, never flashcards or review_history, so
        // schedules and review history survive a focus change untouched.
        addColumnIfMissing(connection, "user_progress", "active_training_path", "TEXT");
        addColumnIfMissing(connection, "user_progress", "focus_module_order", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "user_progress", "mature_interleave_percent", "INTEGER NOT NULL DEFAULT 15");
        // Preferences the guided daily routine (#111) reads instead of hardcoding: how many new
        // cards may be introduced per day, and how long a standard guided session runs.
        addColumnIfMissing(connection, "user_progress", "daily_new_card_limit", "INTEGER NOT NULL DEFAULT 2");
        addColumnIfMissing(connection, "user_progress", "guided_session_minutes", "INTEGER NOT NULL DEFAULT 15");
        // Solving-workspace coaching checkpoints (#145): reminders only, never enforced, and fully
        // learner-configurable (disable, or change the thresholds) the same way every other
        // preference here is a plain column rather than a separate settings table.
        addColumnIfMissing(connection, "user_progress", "solving_checkpoints_enabled", "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(connection, "user_progress", "solving_checkpoint_minutes", "TEXT NOT NULL DEFAULT '20,60,120'");
        // The guided curriculum practice loop's (#161) daily target, in problems.
        addColumnIfMissing(connection, "user_progress", "daily_target_problems", "INTEGER NOT NULL DEFAULT 3");
    }

    /**
     * Problem-solving training (#141/#142) is a workflow entirely separate from flashcard review:
     * these five tables only ever reference each other via {@code problem_id}, never
     * {@code flashcards} or {@code decks}, so deleting or editing flashcard data can never affect a
     * problem-solving record and vice versa.
     *
     * <p>{@code problems} is pure identity, deduplicated across repeated imports by
     * {@code UNIQUE(platform, external_code)}. {@code roadmap_entries} is a separate membership
     * table so the same problem can occupy positions in more than one {@link com.codefit.model.RoadmapStage}
     * without duplicating its identity; {@code UNIQUE(stage, sequence_order)} keeps one problem per
     * roadmap slot and {@code UNIQUE(problem_id, stage)} keeps a problem from being registered twice
     * within the same stage. {@code problem_progress} is capped at one row per problem
     * ({@code UNIQUE(problem_id)}) to hold the single current state, distinct from
     * {@code problem_attempts} which holds many immutable per-submission rows
     * ({@code UNIQUE(problem_id, attempt_number)}). {@code problem_solving_sessions} holds the
     * persistent, resumable in-progress timer state and is likewise capped at one row per problem,
     * kept separate from both progress (aggregate current state) and attempts (finalized submissions).
     */
    static void createProblemSolvingTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS problems (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        external_code TEXT NOT NULL,
                        platform TEXT NOT NULL,
                        title TEXT NOT NULL,
                        url TEXT,
                        topic TEXT NOT NULL DEFAULT 'General',
                        quality_rating INTEGER CHECK (quality_rating IS NULL OR quality_rating BETWEEN 1 AND 5),
                        learning_resources TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE(platform, external_code)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS roadmap_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        problem_id INTEGER NOT NULL,
                        stage TEXT NOT NULL,
                        sequence_order INTEGER NOT NULL,
                        set_number INTEGER,
                        mandatory INTEGER NOT NULL DEFAULT 1,
                        suggested_level TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE,
                        UNIQUE(stage, sequence_order),
                        UNIQUE(problem_id, stage)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS problem_progress (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        problem_id INTEGER NOT NULL UNIQUE,
                        state TEXT NOT NULL DEFAULT 'NOT_STARTED',
                        perceived_difficulty TEXT,
                        solved_with TEXT,
                        final_category TEXT,
                        approach_notes TEXT,
                        mistake_notes TEXT,
                        completed_at TEXT,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS problem_attempts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        problem_id INTEGER NOT NULL,
                        attempt_number INTEGER NOT NULL,
                        submission_result TEXT NOT NULL,
                        reading_time_seconds INTEGER,
                        thinking_time_seconds INTEGER,
                        coding_time_seconds INTEGER,
                        debugging_time_seconds INTEGER,
                        submitted_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        notes TEXT,
                        FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE,
                        UNIQUE(problem_id, attempt_number)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS problem_solving_sessions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        problem_id INTEGER NOT NULL UNIQUE,
                        phase TEXT NOT NULL DEFAULT 'READING',
                        reading_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
                        thinking_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
                        coding_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
                        debugging_seconds_elapsed INTEGER NOT NULL DEFAULT 0,
                        notes TEXT,
                        active INTEGER NOT NULL DEFAULT 1,
                        started_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        last_active_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE
                    )
                    """);
        }
    }

    /**
     * Additive columns for the solving workspace (#145), added the same way every other
     * post-launch column in this file is: {@code addColumnIfMissing} against tables
     * {@link #createProblemSolvingTables} already created, so an existing #142/#143 database upgrades
     * in place without ever recreating those tables.
     */
    private static void ensureProblemSolvingWorkspaceColumns(Connection connection) throws SQLException {
        // Lets a restart resume in the same paused/running state instead of silently accumulating
        // time while the app was closed (see ProblemSolvingSession's class-level docs).
        addColumnIfMissing(connection, "problem_solving_sessions", "paused", "INTEGER NOT NULL DEFAULT 0");
        // Set only for attempts created by finishing a workspace session; null for attempts recorded
        // any other way (e.g. the workbook importer never sets this).
        addColumnIfMissing(connection, "problem_attempts", "session_outcome", "TEXT");
        // The highest hint ladder level opened so far *this attempt* (#162): null until the learner
        // opens the first hint. Living on the session row (not problem_progress) means it resets for
        // free the moment a new attempt starts, since finishing a session deletes this row (see
        // ProblemSolvingSessionService#reset) rather than needing separate reset bookkeeping.
        addColumnIfMissing(connection, "problem_solving_sessions", "highest_hint_level_opened", "TEXT");
    }

    /**
     * Post-solve reflection fields (#146), additive on {@code problem_progress}. The original
     * {@code perceived_difficulty} column (a 3-point {@code DifficultyLevel}, #142) is superseded by
     * {@code perceived_difficulty_rating} (a 1-10 self-rated scale, matching this issue's spec) and is
     * left in place unused rather than dropped, per this file's additive-only migration convention;
     * no shipped code path ever wrote a value into it.
     */
    private static void ensureProblemProgressReflectionColumns(Connection connection) throws SQLException {
        addColumnIfMissing(connection, "problem_progress", "perceived_difficulty_rating",
                "INTEGER CHECK (perceived_difficulty_rating IS NULL OR perceived_difficulty_rating BETWEEN 1 AND 10)");
        addColumnIfMissing(connection, "problem_progress", "important_observation", "TEXT");
        addColumnIfMissing(connection, "problem_progress", "time_complexity", "TEXT");
        addColumnIfMissing(connection, "problem_progress", "space_complexity", "TEXT");
        addColumnIfMissing(connection, "problem_progress", "lesson_learned", "TEXT");
        addColumnIfMissing(connection, "problem_progress", "actual_topic", "TEXT");
        // AC-only optional checks; meaningful only once state is SOLVED, but not enforced at the
        // schema level since they're harmless metadata otherwise.
        addColumnIfMissing(connection, "problem_progress", "editorial_understood", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "problem_progress", "other_solutions_reviewed", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "problem_progress", "simpler_implementation_considered", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(connection, "problem_progress", "better_complexity_considered", "INTEGER NOT NULL DEFAULT 0");
    }

    /**
     * Source attribution for imported roadmaps (#149): one {@code import_batches} row per workbook
     * import run, recording who/where it came from and when, plus which {@code roadmap_entries} row
     * that batch created or last touched. Deliberately no {@code FOREIGN KEY} on
     * {@code roadmap_entries.import_batch_id} — deleting an import batch (see
     * {@code TrainingSheetImportService#deleteImportBatch}) explicitly deletes its roadmap entries
     * first and then the batch row itself, rather than relying on an implicit DB-level cascade, the
     * same explicit-over-implicit choice already made for {@code flashcards.source_problem_id} (#148).
     * Never touches {@code problem_progress}, {@code problem_attempts}, or {@code flashcards} — none
     * of those tables reference {@code roadmap_entries} at all, only {@code problems} directly, so
     * deleting a roadmap's memberships can never cascade into a learner's progress or flashcards.
     */
    private static void ensureImportAttributionSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS import_batches (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        source_name TEXT NOT NULL,
                        source_url TEXT,
                        author TEXT,
                        version TEXT,
                        imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
        addColumnIfMissing(connection, "roadmap_entries", "import_batch_id", "INTEGER");
    }

    /**
     * Guidance content behind the progressive hint ladder (#162): one row per {@link com.codefit.model.Problem}
     * ({@code UNIQUE(problem_id)}, mirroring {@code problem_progress}), holding up to four
     * increasingly explicit levels (Clarify/Observation/Approach/Explanation), optional prerequisite
     * topics, and reference links — entirely separate from problem identity and learner progress, so
     * editing guidance never touches either. {@code source} records provenance (learner-authored,
     * CodeFit-authored, imported, or a future provider integration) without ever storing bundled
     * third-party editorial text.
     */
    private static void ensureProblemGuidanceSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS problem_guidance (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        problem_id INTEGER NOT NULL UNIQUE,
                        source TEXT NOT NULL DEFAULT 'LEARNER',
                        clarify_text TEXT,
                        observation_text TEXT,
                        approach_text TEXT,
                        explanation_text TEXT,
                        pseudocode_text TEXT,
                        complexity_notes TEXT,
                        common_mistakes_text TEXT,
                        prerequisites TEXT,
                        reference_links TEXT,
                        created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE
                    )
                    """);
        }
        // #162: the full Explanation level must cover idea/reasoning (explanation_text), pseudocode,
        // complexity, and common mistakes as distinct parts rather than one opaque blob — added as
        // their own nullable columns so an existing database upgrades in place without losing content.
        addColumnIfMissing(connection, "problem_guidance", "pseudocode_text", "TEXT");
        addColumnIfMissing(connection, "problem_guidance", "complexity_notes", "TEXT");
        addColumnIfMissing(connection, "problem_guidance", "common_mistakes_text", "TEXT");
    }

    /**
     * A learner's saved Java solution-in-progress per problem (#163): one row per {@code problem_id}
     * ({@code UNIQUE}), so autosaving on every edit is just an upsert against this one row — surviving
     * an application restart is the entire point of this table.
     */
    private static void ensureJavaSolutionDraftSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS java_solution_drafts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        problem_id INTEGER NOT NULL UNIQUE,
                        main_class_name TEXT NOT NULL DEFAULT 'Solution',
                        source_code TEXT,
                        stdin TEXT,
                        expected_output TEXT,
                        updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        FOREIGN KEY(problem_id) REFERENCES problems(id) ON DELETE CASCADE
                    )
                    """);
        }
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
                {"Java BE 03 - JDBC & SQL", "users(id, email, created_at): write SQL to list the 5 newest user emails.", "SELECT email FROM users ORDER BY created_at DESC LIMIT 5;", CardType.SQL_QUERY.name(), SqlCardSpecCodec.encode(NEWEST_USER_EMAILS_SQL_SPEC), ValidationMode.NORMALIZED_SPACING.name(), null, "Order newest first, then cap the result size.", "SQL", null},
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

    private record AssessmentVariantSeed(String scenario, String acceptedAnswers, String referenceAnswer, String hint) {
    }

    private record AssessmentItemSeed(String skillCategory, String moduleName, CardType cardType,
                                      ValidationMode validationMode, List<AssessmentVariantSeed> variants) {
    }

    /**
     * Two transfer scenarios per item so a repeated weekly assessment rotates wording instead of
     * showing the exact prompt again (#104). Modules match {@code TrainingPathService}'s Advanced
     * Backend Engineering path so assessment coverage lines up with the syllabus a learner is
     * actually progressing through, but the two tables are otherwise unrelated to any flashcard.
     */
    private static final SqlCardSpec TOP_SPENDERS_SQL_SPEC = new SqlCardSpec(
            "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, amount REAL NOT NULL, status TEXT NOT NULL);",
            "INSERT INTO orders (id, customer_id, amount, status) VALUES "
                    + "(1,100,50.0,'PAID'),(2,100,30.0,'PAID'),(3,101,20.0,'PAID'),(4,101,5.0,'CANCELLED'),(5,102,75.0,'PAID');",
            "SELECT customer_id, SUM(amount) AS total FROM orders WHERE status = 'PAID' GROUP BY customer_id ORDER BY total DESC;",
            null, true, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

    private static final SqlCardSpec REPEAT_USD_PAYERS_SQL_SPEC = new SqlCardSpec(
            "CREATE TABLE payments (id INTEGER PRIMARY KEY, account_id INTEGER NOT NULL, amount REAL NOT NULL, currency TEXT NOT NULL);",
            "INSERT INTO payments (id, account_id, amount, currency) VALUES "
                    + "(1,1,10.0,'USD'),(2,1,10.0,'USD'),(3,1,10.0,'USD'),(4,1,10.0,'USD'),"
                    + "(5,2,10.0,'USD'),(6,2,10.0,'EUR'),(7,3,10.0,'USD');",
            "SELECT account_id, COUNT(*) AS payment_count FROM payments WHERE currency = 'USD' GROUP BY account_id HAVING COUNT(*) > 3;",
            null, false, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

    private static final List<AssessmentItemSeed> ASSESSMENT_BANK_SEED = List.of(
            new AssessmentItemSeed("Concurrency", "Java Concurrency & Thread Safety", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE,
                    List.of(
                            new AssessmentVariantSeed(
                                    "Two threads increment a shared `int balance` field without synchronization inside a bank transfer service. Under load, some deposits silently vanish. Diagnose the concurrency defect and name the fix.",
                                    null,
                                    "This is a lost-update race from a non-atomic read-modify-write on shared mutable state; fix with a lock/synchronized block, an AtomicInteger, or optimistic locking (a version column) so concurrent increments cannot interleave and overwrite each other.",
                                    "Think about what happens when two threads read the same value before either writes it back."),
                            new AssessmentVariantSeed(
                                    "A checkout service caches an inventory count in a plain field, and two requests reserve the last unit at the same time; both succeed and the warehouse oversells by one unit. Diagnose the concurrency defect and name the fix.",
                                    null,
                                    "Also a lost-update / check-then-act race on shared mutable state without synchronization; fix with an atomic compare-and-swap (AtomicInteger.compareAndSet), a database-level optimistic lock (version column), or a pessimistic row lock so the check and decrement happen atomically.",
                                    "The bug is the same shape as a double-spend: check, then act, with no atomicity between the two."))),
            new AssessmentItemSeed("Transactions", "Database Transactions, Locking & Isolation", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE,
                    List.of(
                            new AssessmentVariantSeed(
                                    "An outer @Transactional method calls an inner @Transactional(propagation = REQUIRES_NEW) method. The inner method throws an unchecked RuntimeException, which propagates out of the inner method but is caught immediately in the outer method (not rethrown). Predict what happens to the inner transaction's changes and the outer transaction's changes.",
                                    null,
                                    "REQUIRES_NEW runs the inner method in a brand-new, independent transaction, so the exception marks only the inner transaction for rollback and its changes are undone; the outer transaction never sees the exception (it was caught), so it is unaffected and commits its own changes normally.",
                                    "Ask which physical transaction each propagation setting actually runs in."),
                            new AssessmentVariantSeed(
                                    "An outer @Transactional method calls an inner @Transactional(propagation = Propagation.NESTED) method that throws an unchecked RuntimeException, caught immediately in the outer method. Predict what happens to each transaction's changes, assuming NESTED is supported by the underlying driver.",
                                    null,
                                    "NESTED runs the inner method inside a savepoint of the same physical transaction; the exception rolls back only to that savepoint, undoing just the inner method's changes, while the outer transaction (which caught the exception and was never itself marked rollback-only) can still commit its own changes.",
                                    "NESTED shares one physical transaction with a savepoint; REQUIRES_NEW starts a second, fully independent one."))),
            new AssessmentItemSeed("Idempotency", "Idempotency & Race-Condition Prevention", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE,
                    List.of(
                            new AssessmentVariantSeed(
                                    "A payment API endpoint charges a customer's card, and the mobile client retries the request after a network timeout, potentially charging twice. Design an idempotency approach for this endpoint and explain why it prevents a double charge.",
                                    null,
                                    "Require the client to send a unique idempotency key per logical payment attempt; the server stores (key -> result) the first time it processes a charge and, on a retried request with the same key, returns the stored result instead of re-executing the charge. A database unique constraint on the key makes the check-and-store atomic and repeat-safe regardless of how many times the same request arrives.",
                                    "The client can retry any number of times; the design must make repetition a no-op, not just unlikely to double-charge."),
                            new AssessmentVariantSeed(
                                    "A background worker consumes 'send welcome email' jobs from a queue with at-least-once delivery, so the same job message can be redelivered and processed twice. Design an idempotency approach so the welcome email is never sent twice.",
                                    null,
                                    "Track processed job/message ids in a dedupe table with a unique constraint (or an idempotency key derived from the job) and check-and-insert before sending; if the id is already recorded, skip sending, which turns redelivery into a no-op instead of a duplicate side effect.",
                                    "At-least-once delivery means redelivery is expected and normal, not an edge case."))),
            new AssessmentItemSeed("SQL", "JDBC & SQL", CardType.SQL_QUERY, ValidationMode.NORMALIZED_SPACING,
                    List.of(
                            new AssessmentVariantSeed(
                                    "orders(id, customer_id, amount, status): write a query that finds the total PAID amount spent by each customer, showing customer_id and total, highest spender first.",
                                    SqlCardSpecCodec.encode(TOP_SPENDERS_SQL_SPEC),
                                    "SELECT customer_id, SUM(amount) AS total FROM orders WHERE status = 'PAID' GROUP BY customer_id ORDER BY total DESC;",
                                    "Filter to PAID rows, aggregate per customer, then order the result."),
                            new AssessmentVariantSeed(
                                    "payments(id, account_id, amount, currency): write a query that finds accounts with more than 3 USD payments, showing account_id and payment_count.",
                                    SqlCardSpecCodec.encode(REPEAT_USD_PAYERS_SQL_SPEC),
                                    "SELECT account_id, COUNT(*) AS payment_count FROM payments WHERE currency = 'USD' GROUP BY account_id HAVING COUNT(*) > 3;",
                                    "Filter to USD rows, group per account, then keep only groups above the threshold with HAVING."))),
            new AssessmentItemSeed("Security", "Security & Auth", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE,
                    List.of(
                            new AssessmentVariantSeed(
                                    "A REST API stores JWTs in localStorage and validates them without ever checking the expiration claim server-side, so a token stolen via XSS can be reused indefinitely. Diagnose the vulnerability and the fix.",
                                    null,
                                    "The vulnerability is trusting a token forever because expiration isn't enforced server-side (plus storing it in localStorage is exposed to XSS); the fix is to always validate the exp claim on every request, keep access-token lifetimes short, and prefer an HttpOnly cookie (or a revocable refresh token) so a stolen token has a small, forced window.",
                                    "A stolen token is only as dangerous as how long it stays valid and how it was exposed."),
                            new AssessmentVariantSeed(
                                    "A login endpoint returns \"Invalid password\" when the password is wrong but \"User not found\" when the email doesn't exist. Diagnose the vulnerability and the fix.",
                                    null,
                                    "This is a user-enumeration vulnerability: the differing error messages let an attacker discover which emails are registered; the fix is to return an identical generic message (e.g. \"Invalid email or password\") and take a similar amount of time in both cases so the two situations can't be distinguished.",
                                    "Ask what an attacker learns from the response that they shouldn't be able to learn."))),
            new AssessmentItemSeed("Kafka", "Kafka Delivery Semantics, Outbox & DLQs", CardType.CONCEPT, ValidationMode.CASE_INSENSITIVE,
                    List.of(
                            new AssessmentVariantSeed(
                                    "A Kafka consumer commits its offset before finishing processing a message, then crashes mid-processing. Diagnose the delivery-semantics failure this causes and how to fix it.",
                                    null,
                                    "Committing the offset before processing completes risks silently losing the message if the consumer crashes afterward (an at-most-once loss); fix by only committing the offset after the message is fully processed (disable auto-commit and commit manually post-processing), accepting at-least-once delivery with idempotent handling instead.",
                                    "Ask what happens if the process dies in between the two steps."),
                            new AssessmentVariantSeed(
                                    "A service publishes a 'payment confirmed' event to Kafka only after committing the local database transaction that records the payment, but the process crashes between the commit and the publish. Diagnose the failure and how to fix it.",
                                    null,
                                    "The database write and the Kafka publish are not atomic, so a crash between them permanently loses the event even though the payment was recorded; fix with the transactional outbox pattern: write the event to an outbox table in the same local transaction, then have a separate relay process publish outbox rows to Kafka and mark them sent.",
                                    "Two separate systems (the database and Kafka) can never be updated atomically without a pattern like the outbox."))));

    private static void seedAssessmentBank(Connection connection) throws SQLException {
        try (PreparedStatement existsCheck = connection.prepareStatement(
                "SELECT 1 FROM assessment_items WHERE skill_category = ? LIMIT 1");
             PreparedStatement insertItem = connection.prepareStatement(
                     "INSERT INTO assessment_items (skill_category, module_name, card_type, validation_mode) VALUES (?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS);
             PreparedStatement insertVariant = connection.prepareStatement(
                     "INSERT INTO assessment_variants (assessment_item_id, variant_index, scenario, accepted_answers, reference_answer, hint) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (AssessmentItemSeed itemSeed : ASSESSMENT_BANK_SEED) {
                existsCheck.setString(1, itemSeed.skillCategory());
                try (ResultSet existing = existsCheck.executeQuery()) {
                    if (existing.next()) {
                        continue;
                    }
                }

                insertItem.setString(1, itemSeed.skillCategory());
                insertItem.setString(2, itemSeed.moduleName());
                insertItem.setString(3, itemSeed.cardType().name());
                insertItem.setString(4, itemSeed.validationMode().name());
                insertItem.executeUpdate();
                long itemId;
                try (ResultSet keys = insertItem.getGeneratedKeys()) {
                    keys.next();
                    itemId = keys.getLong(1);
                }

                List<AssessmentVariantSeed> variants = itemSeed.variants();
                for (int index = 0; index < variants.size(); index++) {
                    AssessmentVariantSeed variantSeed = variants.get(index);
                    insertVariant.setLong(1, itemId);
                    insertVariant.setInt(2, index);
                    insertVariant.setString(3, variantSeed.scenario());
                    insertVariant.setString(4, variantSeed.acceptedAnswers());
                    insertVariant.setString(5, variantSeed.referenceAnswer());
                    insertVariant.setString(6, variantSeed.hint());
                    insertVariant.executeUpdate();
                }
            }
        }
    }
}

package com.codefit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCardValidatorTest {

    private static final String USERS_SCHEMA =
            "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER NOT NULL);";
    private static final String USERS_SEED =
            "INSERT INTO users (id, name, age) VALUES "
                    + "(1, 'Ada', 30), (2, 'Ben', 17), (3, 'Cleo', 42), (4, 'Drew', 19), (5, 'Eva', 25);";
    private static final String REFERENCE_ADULTS_ORDERED = "SELECT id, name FROM users WHERE age >= 18 ORDER BY id;";

    private SqlCardSpec adultsSpec(boolean orderMatters) {
        return new SqlCardSpec(USERS_SCHEMA, USERS_SEED, REFERENCE_ADULTS_ORDERED, null, orderMatters, false,
                SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);
    }

    @Test
    void gradesExactWordingAsCorrect() {
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(REFERENCE_ADULTS_ORDERED, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.CORRECT, result.outcome());
    }

    @Test
    void gradesDifferentCasingAsCorrect() {
        String attempt = "select ID, NAME from USERS where AGE >= 18 order by ID;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.CORRECT, result.outcome());
    }

    @Test
    void gradesDifferentButEquivalentWordingAsCorrect() {
        String attempt = "SELECT id, name FROM users WHERE NOT (age < 18) ORDER BY id;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.CORRECT, result.outcome());
    }

    @Test
    void gradesReorderedSelectedColumnsAsCorrectWhenRequiredColumnsMatch() {
        String attempt = "SELECT name, id FROM users WHERE age >= 18 ORDER BY id;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.CORRECT, result.outcome());
    }

    @Test
    void gradesWrongResultSetAsIncorrectWithoutRevealingExpectedRows() {
        String attempt = "SELECT id, name FROM users WHERE age >= 40 ORDER BY id;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.WRONG_RESULT, result.outcome());
        assertTrue(result.feedback().length() < 120, "feedback should be concise: " + result.feedback());
        assertTrue(!result.feedback().contains("Ada") && !result.feedback().contains("Cleo"),
                "feedback must not leak expected rows: " + result.feedback());
    }

    @Test
    void gradesMissingColumnAsIncorrect() {
        String attempt = "SELECT id FROM users WHERE age >= 18 ORDER BY id;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.WRONG_RESULT, result.outcome());
    }

    @Test
    void rowOrderMattersRejectsReorderedRowsWhenConfigured() {
        String attempt = "SELECT id, name FROM users WHERE age >= 18 ORDER BY id DESC;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.WRONG_RESULT, result.outcome());
    }

    @Test
    void rowOrderIgnoredWhenNotConfiguredToMatter() {
        String attempt = "SELECT id, name FROM users WHERE age >= 18 ORDER BY id DESC;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(false));
        assertEquals(SqlCardValidator.Outcome.CORRECT, result.outcome());
    }

    @Test
    void syntaxErrorIsReportedAsExecutionError() {
        String attempt = "SELECT * FROMM users;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.EXECUTION_ERROR, result.outcome());
    }

    @Test
    void referencingAnUnknownColumnIsReportedAsExecutionError() {
        String attempt = "SELECT id, nickname FROM users ORDER BY id;";
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(attempt, adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.EXECUTION_ERROR, result.outcome());
    }

    @Test
    void insertAttemptIsBlockedAndDoesNotCorruptTheFixture() {
        SqlCardSpec spec = adultsSpec(true);
        String maliciousAttempt = "INSERT INTO users (id, name, age) VALUES (99, 'Intruder', 99);";

        SqlCardValidator.GradingResult blocked = SqlCardValidator.grade(maliciousAttempt, spec);
        assertEquals(SqlCardValidator.Outcome.BLOCKED_STATEMENT, blocked.outcome());

        SqlCardValidator.GradingResult afterward = SqlCardValidator.grade(REFERENCE_ADULTS_ORDERED, spec);
        assertEquals(SqlCardValidator.Outcome.CORRECT, afterward.outcome());
    }

    @Test
    void updateAttemptIsBlocked() {
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(
                "UPDATE users SET age = 0 WHERE id = 1;", adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.BLOCKED_STATEMENT, result.outcome());
    }

    @Test
    void deleteAttemptIsBlocked() {
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(
                "DELETE FROM users WHERE id = 1;", adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.BLOCKED_STATEMENT, result.outcome());
    }

    @Test
    void dropTableAttemptIsBlockedAndFixtureSurvivesForTheNextAttempt() {
        SqlCardSpec spec = adultsSpec(true);
        SqlCardValidator.GradingResult dropAttempt = SqlCardValidator.grade("DROP TABLE users;", spec);
        assertEquals(SqlCardValidator.Outcome.BLOCKED_STATEMENT, dropAttempt.outcome());

        SqlCardValidator.GradingResult afterward = SqlCardValidator.grade(REFERENCE_ADULTS_ORDERED, spec);
        assertEquals(SqlCardValidator.Outcome.CORRECT, afterward.outcome());
    }

    @Test
    void stackedStatementsAreBlockedEvenWhenTheFirstStatementIsHarmless() {
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(
                "SELECT id, name FROM users WHERE age >= 18 ORDER BY id; DROP TABLE users;", adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.BLOCKED_STATEMENT, result.outcome());
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void runawayRecursiveQueryIsStoppedByTheTimeout() {
        SqlCardSpec spec = new SqlCardSpec(USERS_SCHEMA, USERS_SEED, REFERENCE_ADULTS_ORDERED, null, true, false, 300);
        String runaway = "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM cnt WHERE x < 100000000) "
                + "SELECT count(*) AS total FROM cnt;";

        SqlCardValidator.GradingResult result = SqlCardValidator.grade(runaway, spec);
        assertEquals(SqlCardValidator.Outcome.TIMEOUT, result.outcome());
    }

    @Test
    void exceedingTheRowLimitIsReportedAsWrongResult() {
        SqlCardSpec spec = new SqlCardSpec(USERS_SCHEMA, USERS_SEED, REFERENCE_ADULTS_ORDERED, null, true, false,
                SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);
        String hugeResult = "WITH RECURSIVE cnt(x) AS (SELECT 1 UNION ALL SELECT x + 1 FROM cnt WHERE x < 5000) "
                + "SELECT x FROM cnt;";

        SqlCardValidator.GradingResult result = SqlCardValidator.grade(hugeResult, spec);
        assertEquals(SqlCardValidator.Outcome.WRONG_RESULT, result.outcome());
    }

    @Test
    void expectedErrorCardAcceptsAQueryThatRaisesTheExpectedError() {
        SqlCardSpec spec = new SqlCardSpec(USERS_SCHEMA, USERS_SEED, null, "no such column",
                false, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        SqlCardValidator.GradingResult result = SqlCardValidator.grade("SELECT nickname FROM users;", spec);
        assertEquals(SqlCardValidator.Outcome.CORRECT, result.outcome());
    }

    @Test
    void expectedErrorCardRejectsAQueryThatRaisesADifferentError() {
        SqlCardSpec spec = new SqlCardSpec(USERS_SCHEMA, USERS_SEED, null, "no such column",
                false, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        SqlCardValidator.GradingResult result = SqlCardValidator.grade("SELECT * FROMM users;", spec);
        assertEquals(SqlCardValidator.Outcome.WRONG_RESULT, result.outcome());
    }

    @Test
    void expectedErrorCardRejectsAQueryThatSucceeds() {
        SqlCardSpec spec = new SqlCardSpec(USERS_SCHEMA, USERS_SEED, null, "no such column",
                false, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        SqlCardValidator.GradingResult result = SqlCardValidator.grade("SELECT id FROM users;", spec);
        assertEquals(SqlCardValidator.Outcome.WRONG_RESULT, result.outcome());
    }

    @Test
    void emptyAttemptIsReportedAsEmpty() {
        SqlCardValidator.GradingResult result = SqlCardValidator.grade("   ", adultsSpec(true));
        assertEquals(SqlCardValidator.Outcome.EMPTY, result.outcome());
    }

    @Test
    void malformedFixtureConfigurationIsReportedAsMisconfigured() {
        SqlCardValidator.GradingResult result = SqlCardValidator.grade(REFERENCE_ADULTS_ORDERED, "not a valid config");
        assertEquals(SqlCardValidator.Outcome.MISCONFIGURED, result.outcome());
    }

    @Test
    void controlledDdlCardGradesEquivalentCreateTableAsCorrect() {
        SqlCardSpec spec = new SqlCardSpec("", "",
                "CREATE TABLE archive (id INTEGER PRIMARY KEY, label TEXT NOT NULL);",
                null, false, true, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        SqlCardValidator.GradingResult result = SqlCardValidator.grade(
                "CREATE TABLE archive (id INTEGER PRIMARY KEY, label TEXT NOT NULL);", spec);
        assertEquals(SqlCardValidator.Outcome.CORRECT, result.outcome());
    }

    @Test
    void controlledDdlCardRejectsADifferentSchema() {
        SqlCardSpec spec = new SqlCardSpec("", "",
                "CREATE TABLE archive (id INTEGER PRIMARY KEY, label TEXT NOT NULL);",
                null, false, true, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        SqlCardValidator.GradingResult result = SqlCardValidator.grade(
                "CREATE TABLE archive (id INTEGER PRIMARY KEY);", spec);
        assertEquals(SqlCardValidator.Outcome.WRONG_RESULT, result.outcome());
    }

    @Test
    void controlledDdlCardStillBlocksAttach() {
        SqlCardSpec spec = new SqlCardSpec("", "",
                "CREATE TABLE archive (id INTEGER PRIMARY KEY);",
                null, false, true, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        SqlCardValidator.GradingResult result = SqlCardValidator.grade(
                "ATTACH DATABASE 'other.db' AS other;", spec);
        assertEquals(SqlCardValidator.Outcome.BLOCKED_STATEMENT, result.outcome());
    }
}

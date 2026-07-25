package com.codefit.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Grades a {@link com.codefit.model.CardType#SQL_QUERY} attempt by executing it against a fresh,
 * isolated SQLite fixture (built from the card's {@link SqlCardSpec}) instead of comparing
 * submitted text to a saved answer string. This lets semantically equivalent queries pass
 * regardless of wording, while a textually similar but wrong query is still rejected.
 *
 * <p>Every fixture is an anonymous {@code jdbc:sqlite::memory:} connection, which SQLite gives its
 * own private in-memory database per connection, so attempts never touch the app's persistent
 * database ({@code codefit.db}) or leak state to one another. The measured statement always runs
 * inside an explicit transaction that is rolled back before the connection closes.</p>
 */
public final class SqlCardValidator {

    private static final String FIXTURE_URL = "jdbc:sqlite::memory:";
    private static final int ROW_LIMIT = 2000;
    private static final int TIMEOUT_GRACE_MILLIS = 300;
    private static final String SCHEMA_SNAPSHOT_QUERY =
            "SELECT type, name, sql FROM sqlite_master WHERE type IN ('table', 'index') ORDER BY type, name";

    private static final Set<String> ALWAYS_BLOCKED_KEYWORDS = Set.of(
            "ATTACH", "DETACH", "PRAGMA", "VACUUM", "REINDEX", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT");
    private static final Set<String> DDL_DML_KEYWORDS = Set.of(
            "CREATE", "DROP", "ALTER", "INSERT", "UPDATE", "DELETE", "REPLACE");

    private static final ExecutorService QUERY_EXECUTOR =
            Executors.newCachedThreadPool(runnable -> daemonThread(runnable, "sql-card-grading"));
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> daemonThread(runnable, "sql-card-timeout"));

    public enum Outcome { EMPTY, CORRECT, WRONG_RESULT, BLOCKED_STATEMENT, EXECUTION_ERROR, TIMEOUT, MISCONFIGURED }

    public record GradingResult(Outcome outcome, String feedback) {
        public boolean isCorrect() {
            return outcome == Outcome.CORRECT;
        }
    }

    private SqlCardValidator() {
    }

    /** Convenience overload for callers holding the raw encoded spec (a card's accepted-answers column). */
    public static GradingResult grade(String attempt, String encodedSpec) {
        String trimmedAttempt = attempt == null ? "" : attempt.strip();
        if (trimmedAttempt.isEmpty()) {
            return new GradingResult(Outcome.EMPTY, "");
        }
        SqlCardSpec spec;
        try {
            spec = SqlCardSpecCodec.decode(encodedSpec);
        } catch (IllegalArgumentException exception) {
            return new GradingResult(Outcome.MISCONFIGURED, "This card's SQL fixture is not configured correctly.");
        }
        return grade(trimmedAttempt, spec);
    }

    public static GradingResult grade(String attempt, SqlCardSpec spec) {
        String trimmedAttempt = attempt == null ? "" : attempt.strip();
        if (trimmedAttempt.isEmpty()) {
            return new GradingResult(Outcome.EMPTY, "");
        }

        String blockedKeyword = findBlockedKeyword(trimmedAttempt, spec.allowControlledDdl());
        if (blockedKeyword != null) {
            return new GradingResult(Outcome.BLOCKED_STATEMENT, "\"" + blockedKeyword
                    + "\" is not allowed for this card; only read-only queries are graded here.");
        }
        List<String> attemptStatements = splitStatements(trimmedAttempt);
        if (attemptStatements.isEmpty()) {
            return new GradingResult(Outcome.EXECUTION_ERROR, "No SQL statement found in your attempt.");
        }
        if (!spec.allowControlledDdl() && attemptStatements.size() > 1) {
            return new GradingResult(Outcome.BLOCKED_STATEMENT, "Only a single statement is graded for this card.");
        }

        QueryOutcome actual;
        try {
            actual = runAgainstFreshFixture(spec, trimmedAttempt);
        } catch (TimeoutGradingException exception) {
            return new GradingResult(Outcome.TIMEOUT, "Query took too long to run (limit " + spec.timeoutMillis()
                    + "ms). Check for missing filters/joins or runaway recursion.");
        } catch (FixtureSetupException exception) {
            return new GradingResult(Outcome.MISCONFIGURED, "This card's SQL fixture is not configured correctly.");
        }

        if (spec.expectsError()) {
            if (actual.error() == null) {
                return new GradingResult(Outcome.WRONG_RESULT,
                        "Expected this query to raise an error, but it completed successfully.");
            }
            if (containsIgnoreCase(actual.error(), spec.expectedError())) {
                return new GradingResult(Outcome.CORRECT, "Query raised the expected error.");
            }
            return new GradingResult(Outcome.WRONG_RESULT, "The query raised an error, but not the one this card expects.");
        }

        if (actual.error() != null) {
            return new GradingResult(Outcome.EXECUTION_ERROR, "Query failed to execute: " + actual.error());
        }
        if (actual.rowLimitExceeded()) {
            return new GradingResult(Outcome.WRONG_RESULT, "Query returned too many rows (limit is " + ROW_LIMIT + ").");
        }

        QueryOutcome expected;
        try {
            expected = runAgainstFreshFixture(spec, spec.referenceQuery());
        } catch (TimeoutGradingException | FixtureSetupException exception) {
            return new GradingResult(Outcome.MISCONFIGURED, "This card's reference query could not be evaluated.");
        }
        if (expected.error() != null) {
            return new GradingResult(Outcome.MISCONFIGURED, "This card's reference query is not configured correctly.");
        }

        if (!sameColumns(actual.columns(), expected.columns())) {
            return new GradingResult(Outcome.WRONG_RESULT, "Result columns don't match what this card expects.");
        }
        if (!sameRows(actual, expected, spec.orderMatters())) {
            return new GradingResult(Outcome.WRONG_RESULT, spec.orderMatters()
                    ? "Result rows don't match the expected rows and/or their order."
                    : "Result rows don't match the expected rows.");
        }
        return new GradingResult(Outcome.CORRECT, "Query result matches the expected output.");
    }

    // ---- fixture execution, bounded by a statement timeout plus a wall-clock guard ----

    private static QueryOutcome runAgainstFreshFixture(SqlCardSpec spec, String sql) {
        AtomicReference<Statement> statementRef = new AtomicReference<>();
        AtomicBoolean timedOut = new AtomicBoolean(false);
        Future<QueryOutcome> future = QUERY_EXECUTOR.submit(
                () -> executeOnFreshConnection(spec, sql, statementRef));
        ScheduledFuture<?> canceller = TIMEOUT_EXECUTOR.schedule(() -> {
            timedOut.set(true);
            cancelQuietly(statementRef.get());
        }, spec.timeoutMillis(), TimeUnit.MILLISECONDS);
        try {
            QueryOutcome outcome = future.get(spec.timeoutMillis() + TIMEOUT_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            // The wall-clock guard may have fired and cancelled the statement just before the
            // query thread noticed and returned an error outcome, rather than us hitting the
            // Future.get timeout below; either way that is a timeout, not a query execution error.
            if (timedOut.get()) {
                throw new TimeoutGradingException();
            }
            return outcome;
        } catch (TimeoutException exception) {
            future.cancel(true);
            cancelQuietly(statementRef.get());
            throw new TimeoutGradingException();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof FixtureSetupException fixtureSetupException) {
                throw fixtureSetupException;
            }
            throw new FixtureSetupException(cause);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new FixtureSetupException(exception);
        } finally {
            canceller.cancel(false);
        }
    }

    /**
     * Sets up the fixture and runs {@code sql} on a single reused {@link Statement} so the
     * wall-clock guard in {@link #runAgainstFreshFixture} can interrupt whichever phase (setup or
     * the measured statement) happens to be running when the timeout fires.
     */
    private static QueryOutcome executeOnFreshConnection(SqlCardSpec spec, String sql,
                                                          AtomicReference<Statement> statementRef) {
        try (Connection connection = DriverManager.getConnection(FIXTURE_URL)) {
            Statement statement = connection.createStatement();
            statementRef.set(statement);
            try {
                statement.setQueryTimeout(secondsFromMillis(spec.timeoutMillis()));
                try {
                    for (String setupStatement : splitStatements(spec.schemaSql())) {
                        statement.execute(setupStatement);
                    }
                    for (String setupStatement : splitStatements(spec.seedSql())) {
                        statement.execute(setupStatement);
                    }
                    connection.setAutoCommit(false);
                } catch (SQLException exception) {
                    throw new FixtureSetupException(exception);
                }
                return runMeasuredStatement(statement, sql, spec.allowControlledDdl());
            } finally {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Best-effort: the fixture connection is discarded either way once we return.
                }
                statement.close();
                statementRef.set(null);
            }
        } catch (SQLException exception) {
            throw new FixtureSetupException(exception);
        }
    }

    /** Never throws: execution failures of the measured statement become {@link QueryOutcome#error()}. */
    private static QueryOutcome runMeasuredStatement(Statement statement, String sql, boolean allowControlledDdl) {
        try {
            statement.setMaxRows(ROW_LIMIT + 1);
            List<String> statements = splitStatements(sql);
            if (statements.isEmpty()) {
                return errorOutcome("No SQL statement found.");
            }
            if (allowControlledDdl) {
                for (String ddlStatement : statements) {
                    statement.execute(ddlStatement);
                }
                try (ResultSet resultSet = statement.executeQuery(SCHEMA_SNAPSHOT_QUERY)) {
                    return captureResultSet(resultSet);
                }
            }
            try (ResultSet resultSet = statement.executeQuery(statements.get(0))) {
                return captureResultSet(resultSet);
            }
        } catch (SQLException exception) {
            return errorOutcome(exception.getMessage());
        }
    }

    private static void cancelQuietly(Statement statement) {
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // Best-effort interrupt; the surrounding Future.get bound still applies either way.
            }
        }
    }

    private static int secondsFromMillis(int millis) {
        return Math.max(1, (millis + 999) / 1000);
    }

    // ---- result comparison ----

    private static QueryOutcome captureResultSet(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
            columns.add(metaData.getColumnLabel(i));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        boolean rowLimitExceeded = false;
        while (resultSet.next()) {
            if (rows.size() >= ROW_LIMIT) {
                rowLimitExceeded = true;
                break;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1).toUpperCase(Locale.ROOT), resultSet.getString(i));
            }
            rows.add(row);
        }
        return new QueryOutcome(columns, rows, rowLimitExceeded, null);
    }

    private static QueryOutcome errorOutcome(String message) {
        return new QueryOutcome(List.of(), List.of(), false, message);
    }

    /**
     * Columns are compared as a case-insensitive set rather than a positional list, so selecting
     * the same required columns in a different order is accepted. A computed column (e.g.
     * {@code COUNT(*)}) is labeled with its raw expression text by SQLite, so reference queries
     * should alias computed columns to compare reliably against differently worded attempts.
     */
    private static boolean sameColumns(List<String> actual, List<String> expected) {
        return normalizedColumnSet(actual).equals(normalizedColumnSet(expected));
    }

    private static Set<String> normalizedColumnSet(List<String> columns) {
        Set<String> set = new HashSet<>();
        for (String column : columns) {
            set.add(column == null ? "" : column.strip().toUpperCase(Locale.ROOT));
        }
        return set;
    }

    private static boolean sameRows(QueryOutcome actual, QueryOutcome expected, boolean orderMatters) {
        if (actual.rows().size() != expected.rows().size()) {
            return false;
        }
        if (orderMatters) {
            return actual.rows().equals(expected.rows());
        }
        List<String> actualCanonical = canonicalRows(actual.rows());
        List<String> expectedCanonical = canonicalRows(expected.rows());
        Collections.sort(actualCanonical);
        Collections.sort(expectedCanonical);
        return actualCanonical.equals(expectedCanonical);
    }

    /** A sortable, order-independent-within-row representation so row multisets can be compared. */
    private static List<String> canonicalRows(List<Map<String, String>> rows) {
        List<String> canonical = new ArrayList<>();
        for (Map<String, String> row : rows) {
            List<String> keys = new ArrayList<>(row.keySet());
            Collections.sort(keys);
            StringBuilder builder = new StringBuilder();
            for (String key : keys) {
                builder.append(key).append('=').append(row.get(key)).append('\u0001');
            }
            canonical.add(builder.toString());
        }
        return canonical;
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && needle != null
                && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // ---- statement safety guard ----

    private static String findBlockedKeyword(String sql, boolean allowControlledDdl) {
        String scanText = stripLiteralsAndComments(sql).toUpperCase(Locale.ROOT);
        for (String keyword : ALWAYS_BLOCKED_KEYWORDS) {
            if (containsWord(scanText, keyword)) {
                return keyword;
            }
        }
        if (!allowControlledDdl) {
            for (String keyword : DDL_DML_KEYWORDS) {
                if (containsWord(scanText, keyword)) {
                    return keyword;
                }
            }
        }
        return null;
    }

    private static boolean containsWord(String text, String word) {
        return Pattern.compile("\\b" + word + "\\b").matcher(text).find();
    }

    /**
     * Splits {@code sql} into individual statements on top-level semicolons, ignoring semicolons
     * inside string literals or comments so a multi-line schema/seed script or a query containing
     * a literal semicolon-like character is not split incorrectly.
     */
    private static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        Character quote = null;
        boolean lineComment = false;
        boolean blockComment = false;
        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != null) {
                current.append(c);
                if (c == quote) {
                    quote = null;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == ';') {
                addStatement(statements, current);
                continue;
            }
            current.append(c);
        }
        addStatement(statements, current);
        return statements;
    }

    private static void addStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        current.setLength(0);
    }

    /** Same scanning rules as {@link #splitStatements}, but replaces literal/comment content with spaces. */
    private static String stripLiteralsAndComments(String sql) {
        StringBuilder result = new StringBuilder();
        Character quote = null;
        boolean lineComment = false;
        boolean blockComment = false;
        int length = sql.length();
        for (int i = 0; i < length; i++) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : '\0';
            if (lineComment) {
                result.append(c == '\n' ? c : ' ');
                if (c == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    result.append("  ");
                    i++;
                } else {
                    result.append(' ');
                }
                continue;
            }
            if (quote != null) {
                result.append(' ');
                if (c == quote) {
                    quote = null;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                lineComment = true;
                result.append("  ");
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                blockComment = true;
                result.append("  ");
                i++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                result.append(' ');
                continue;
            }
            result.append(c);
        }
        return result.toString();
    }

    private record QueryOutcome(List<String> columns, List<Map<String, String>> rows, boolean rowLimitExceeded,
                                String error) {
    }

    private static Thread daemonThread(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    private static final class FixtureSetupException extends RuntimeException {
        FixtureSetupException(Throwable cause) {
            super(cause);
        }
    }

    private static final class TimeoutGradingException extends RuntimeException {
    }
}

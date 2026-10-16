package com.codefit.repository;

import com.codefit.config.DatabaseConfig;
import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.GraphRef;
import com.codefit.evolution.SkillDefinition;
import com.codefit.evolution.SkillGraph;
import com.codefit.evolution.SkillLifecycle;
import com.codefit.evolution.SkillPrerequisite;
import com.codefit.evolution.SkillRef;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SkillCatalogRepositoryTest {
    private final SkillCatalogRepository repository = new SkillCatalogRepository();

    private static SkillDefinition skill(String id) {
        return new SkillDefinition(new SkillRef(id, 1), "example", "Name " + id, "Measured capability " + id,
                List.of("Produces a valid result", "Preserves the invariant\nfor changed inputs"), SkillLifecycle.ACTIVE);
    }

    private static SkillDefinition revision(SkillDefinition original, int version, SkillLifecycle lifecycle) {
        return new SkillDefinition(new SkillRef(original.ref().skillId(), version), original.domainId(), original.name(),
                original.description(), original.observableBehaviors(), lifecycle);
    }

    @Test
    void freshInstallSeedsTheExactFourSkillsAndThreePrerequisitesWithoutLearnerState() {
        SkillGraph expected = JavaDebuggingSkillSeed.graph();
        assertEquals(expected, repository.findGraph(JavaDebuggingSkillSeed.GRAPH).orElseThrow());
        assertEquals(4, repository.listSkillsInDomain(JavaDebuggingSkillSeed.DOMAIN_ID).size());
        assertEquals(List.of("java.debugging.reproduce", "java.concurrency.atomic-update",
                        "java.concurrency.invariant", "java.concurrency.liveness"),
                expected.topologicalOrder().stream().map(definition -> definition.ref().skillId()).toList());
        assertEquals(3, expected.prerequisites().size());
        assertTrue(repository.findSkill(new SkillRef("unknown.skill", 1)).isEmpty());
        assertTrue(repository.findGraph(new GraphRef("missing.graph", 1)).isEmpty());
        assertEquals(List.of(), repository.listSkillsInDomain("unknown-domain"));
    }

    @Test
    void bulkReadsUseBoundedQueriesAndPreserveExactDefinitionsAtCatalogScale() throws Exception {
        List<List<SkillDefinition>> catalogs = List.of(4, 100, 1_000).stream().map(size ->
                java.util.stream.IntStream.range(0, size).mapToObj(index -> new SkillDefinition(
                        new SkillRef("bulk.%04d.skill.%04d".formatted(size, index), 1), "bulk." + size,
                        "Bulk skill " + index, "Bulk capability " + index,
                        List.of("First behavior", "Second behavior"), SkillLifecycle.ACTIVE)).toList()).toList();
        List<SkillDefinition> definitions = catalogs.stream().flatMap(List::stream).toList();
        List<SkillGraph> graphs = catalogs.stream().map(catalog -> new SkillGraph(
                new GraphRef("bulk.graph", catalog.size()), catalog,
                java.util.stream.IntStream.range(1, catalog.size()).mapToObj(index ->
                        new SkillPrerequisite(catalog.get(index - 1).ref(), catalog.get(index).ref())).toList())).toList();
        try (Connection connection = foreignKeyConnection()) {
            connection.setAutoCommit(false);
            for (SkillDefinition definition : definitions) repository.publishDefinition(connection, definition);
            for (SkillGraph graph : graphs) repository.publishGraph(connection, graph);
            connection.commit();
        }

        for (SkillGraph expected : graphs) {
            ReadMeasurement<SkillGraph> previousGraph = measure(connection -> readSnapshot(connection,
                    snapshot -> legacyFindGraph(snapshot, expected.ref()).orElseThrow()));
            ReadMeasurement<SkillGraph> bulkGraph = measure(connection -> readSnapshot(connection,
                    snapshot -> repository.findGraph(snapshot, expected.ref()).orElseThrow()));
            assertEquals(expected, previousGraph.value());
            assertEquals(previousGraph.value(), bulkGraph.value());
            assertEquals(3 + 2 * expected.definitions().size(), previousGraph.queries());
            assertEquals(2, bulkGraph.queries());
            assertEquals(expected, repository.findGraph(expected.ref()).orElseThrow());

            ReadMeasurement<List<SkillDefinition>> previousDomain = measure(connection ->
                    legacyListSkillsInDomain(connection, expected.definitions().getFirst().domainId()));
            ReadMeasurement<List<SkillDefinition>> bulkDomain = measure(connection ->
                    repository.listSkillsInDomain(connection, expected.definitions().getFirst().domainId()));
            assertEquals(expected.definitions(), previousDomain.value());
            assertEquals(previousDomain.value(), bulkDomain.value());
            assertEquals(1 + 2 * expected.definitions().size(), previousDomain.queries());
            assertEquals(1, bulkDomain.queries());
            assertEquals(expected.definitions(), repository.listSkillsInDomain(
                    expected.definitions().getFirst().domainId()));

            if (expected.definitions().size() == 1_000) {
                runPair(() -> assertEquals(expected, repository.findGraph(expected.ref()).orElseThrow()),
                        () -> assertEquals(expected, repository.findGraph(expected.ref()).orElseThrow()));
                runPair(() -> assertEquals(expected.definitions(), repository.listSkillsInDomain("bulk.1000")),
                        () -> assertEquals(expected.definitions(), repository.listSkillsInDomain("bulk.1000")));
            }
            System.out.printf("bulk-read size=%d graph queries=%d->%d time-us=%d->%d domain queries=%d->%d time-us=%d->%d%n",
                    expected.definitions().size(), previousGraph.queries(), bulkGraph.queries(),
                    previousGraph.elapsedNanos() / 1_000, bulkGraph.elapsedNanos() / 1_000,
                    previousDomain.queries(), bulkDomain.queries(),
                    previousDomain.elapsedNanos() / 1_000, bulkDomain.elapsedNanos() / 1_000);
        }
    }

    @Test
    void bulkReadsRejectIncompleteBehaviorsAndMissingPublishedGraphMembers() throws SQLException {
        SkillDefinition definition = skill("malformed.bulk");
        repository.publishDefinition(definition);
        SkillGraph graph = new SkillGraph(new GraphRef("malformed.bulk", 1), List.of(definition), List.of());
        repository.publishGraph(graph);
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP TRIGGER evolution_skill_behaviors_immutable_DELETE");
            sql.execute("DELETE FROM evolution_skill_behaviors WHERE skill_id = 'malformed.bulk' AND position = 0");
            assertThrows(IllegalStateException.class, () -> repository.findGraph(graph.ref()));
            assertThrows(IllegalStateException.class, () -> repository.listSkillsInDomain("example"));

            sql.execute("DROP TRIGGER evolution_skill_definitions_immutable_UPDATE");
            sql.execute("UPDATE evolution_skill_definitions SET published = 0 WHERE skill_id = 'malformed.bulk'");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> repository.findGraph(graph.ref()))
                    .getMessage().contains("Missing published skill definition"));
        }
    }

    @Test
    void publicationIsIdempotentAndPreservesMultilineBehaviorOrder() {
        SkillDefinition definition = skill("test.roundtrip");
        repository.publishDefinition(definition);
        repository.publishDefinition(definition);
        SkillCatalogRepository reopened = new SkillCatalogRepository();
        assertEquals(definition, reopened.findSkill(definition.ref()).orElseThrow());
        assertEquals(1, count("evolution_skill_definitions", "skill_id = 'test.roundtrip'"));
        assertEquals(2, count("evolution_skill_behaviors", "skill_id = 'test.roundtrip'"));
    }

    @Test
    void sameVersionCannotChangeAnyPublishedContentButNewVersionsCanEvolve() {
        var original = skill("test.immutable");
        repository.publishDefinition(original);
        for (var changed : List.of(
                new SkillDefinition(original.ref(), original.domainId(), "Different name", original.description(), original.observableBehaviors(), original.lifecycle()),
                new SkillDefinition(original.ref(), original.domainId(), original.name(), "Different description", original.observableBehaviors(), original.lifecycle()),
                new SkillDefinition(original.ref(), original.domainId(), original.name(), original.description(), List.of("Different behavior"), original.lifecycle()),
                revision(original, 1, SkillLifecycle.RETIRED),
                new SkillDefinition(original.ref(), "another-domain", original.name(), original.description(), original.observableBehaviors(), original.lifecycle()))) {
            assertThrows(IllegalArgumentException.class, () -> repository.publishDefinition(changed));
        }
        var reclassified = new SkillDefinition(new SkillRef(original.ref().skillId(), 2), "another-domain", original.name(),
                original.description(), original.observableBehaviors(), original.lifecycle());
        repository.publishDefinition(reclassified);
        assertEquals(original, repository.findSkill(original.ref()).orElseThrow());
        assertEquals(reclassified, repository.findSkill(reclassified.ref()).orElseThrow());
        assertEquals(List.of(reclassified), repository.listSkillsInDomain("another-domain"));
    }

    @Test
    void laterRetiredVersionDoesNotRewriteOldGraphOrDefinition() {
        var a = skill("history.a"); var b = skill("history.b");
        repository.publishDefinition(a); repository.publishDefinition(b);
        var first = new SkillGraph(new GraphRef("history.graph", 1), List.of(a, b), List.of(new SkillPrerequisite(a.ref(), b.ref())));
        repository.publishGraph(first);
        var retired = revision(a, 2, SkillLifecycle.RETIRED);
        repository.publishDefinition(retired);
        var second = new SkillGraph(new GraphRef("history.graph", 2), List.of(retired, b), List.of(new SkillPrerequisite(retired.ref(), b.ref())));
        repository.publishGraph(second);
        assertEquals(first, repository.findGraph(first.ref()).orElseThrow());
        assertEquals(second, repository.findGraph(second.ref()).orElseThrow());
        assertEquals(a, repository.findSkill(a.ref()).orElseThrow());
        assertEquals(SkillLifecycle.RETIRED, repository.findSkill(retired.ref()).orElseThrow().lifecycle());
        var versions = repository.listSkillsInDomain("example").stream().filter(d -> d.ref().skillId().equals("history.a")).toList();
        assertEquals(List.of(a, retired), versions);
        assertEquals(List.of(retired), second.prerequisiteTraversal(b.ref()));
    }

    @Test
    void graphReplayIsCanonicalButConflictingVersionCannotBeReused() {
        var a = skill("graph.a"); var b = skill("graph.b");
        repository.publishDefinition(a); repository.publishDefinition(b);
        var ref = new GraphRef("test.canonical", 3);
        var graph = new SkillGraph(ref, List.of(b, a), List.of(new SkillPrerequisite(a.ref(), b.ref())));
        repository.publishGraph(graph);
        repository.publishGraph(new SkillGraph(ref, List.of(a, b), graph.prerequisites()));
        assertEquals(graph, new SkillCatalogRepository().findGraph(ref).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> repository.publishGraph(new SkillGraph(ref, List.of(a, b), List.of())));
        assertEquals(1, count("evolution_skill_graphs", "graph_id = 'test.canonical'"));
        assertEquals(graph, repository.findGraph(ref).orElseThrow());
    }

    @Test
    void graphCannotPublishMissingOrConflictingDefinitionAndLeavesNoDraftRows() {
        var a = skill("test.member");
        var ref = new GraphRef("test.missing-definition", 1);
        assertThrows(IllegalArgumentException.class, () -> repository.publishGraph(new SkillGraph(ref, List.of(a), List.of())));
        assertEquals(0, count("evolution_skill_graphs", "graph_id = 'test.missing-definition'"));
        repository.publishDefinition(a);
        var changed = new SkillDefinition(a.ref(), a.domainId(), "Wrong payload", a.description(), a.observableBehaviors(), a.lifecycle());
        assertThrows(IllegalArgumentException.class, () -> repository.publishGraph(new SkillGraph(ref, List.of(changed), List.of())));
        assertEquals(0, count("evolution_graph_skills", "graph_id = 'test.missing-definition'"));
        assertTrue(repository.findGraph(ref).isEmpty());
    }

    @Test
    void partialDefinitionAndGraphWritesRollbackOnStorageFailure() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("""
                    CREATE TRIGGER test_behavior_failure BEFORE INSERT ON evolution_skill_behaviors
                    WHEN NEW.skill_id = 'test.rollback' AND NEW.position = 1
                    BEGIN SELECT RAISE(ABORT, 'injected behavior failure'); END
                    """);
            try {
                assertThrows(IllegalStateException.class, () -> repository.publishDefinition(skill("test.rollback")));
                assertEquals(0, count("evolution_skill_definitions", "skill_id = 'test.rollback'"));
                assertEquals(0, count("evolution_skill_behaviors", "skill_id = 'test.rollback'"));
            } finally { sql.execute("DROP TRIGGER test_behavior_failure"); }
            var a = skill("rollback.a"); var b = skill("rollback.b"); var c = skill("rollback.c");
            for (var definition : List.of(a, b, c)) repository.publishDefinition(definition);
            sql.execute("""
                    CREATE TRIGGER test_edge_failure BEFORE INSERT ON evolution_skill_prerequisites
                    WHEN NEW.graph_id = 'test.rollback' AND NEW.prerequisite_skill_id = 'rollback.b'
                    BEGIN SELECT RAISE(ABORT, 'injected edge failure'); END
                    """);
            var graph = new SkillGraph(new GraphRef("test.rollback", 1), List.of(a, b, c),
                    List.of(new SkillPrerequisite(a.ref(), b.ref()), new SkillPrerequisite(b.ref(), c.ref())));
            try {
                assertThrows(IllegalStateException.class, () -> repository.publishGraph(graph));
                for (String table : List.of("evolution_skill_graphs", "evolution_graph_skills", "evolution_skill_prerequisites")) {
                    assertEquals(0, count(table, "graph_id = 'test.rollback'"));
                }
            } finally { sql.execute("DROP TRIGGER test_edge_failure"); }
        }
    }

    @Test
    void databaseGuardsPreventChangesToPublishedDefinitionsAndGraphs() throws SQLException {
        try (Connection connection = foreignKeyConnection(); Statement sql = connection.createStatement()) {
            for (String statement : List.of(
                    "INSERT INTO evolution_skill_definitions VALUES ('bypass.skill', 1, 'example', 'Name', 'Description', 'ACTIVE', 1)",
                    "INSERT INTO evolution_skill_graphs VALUES ('bypass.graph', 1, 1)",
                    "UPDATE evolution_skill_definitions SET name = 'Changed' WHERE skill_id = 'java.debugging.reproduce'",
                    "DELETE FROM evolution_skill_definitions WHERE skill_id = 'java.debugging.reproduce'",
                    "UPDATE evolution_skill_behaviors SET behavior = 'Changed' WHERE skill_id = 'java.debugging.reproduce'",
                    "DELETE FROM evolution_skill_behaviors WHERE skill_id = 'java.debugging.reproduce'",
                    "INSERT INTO evolution_skill_behaviors VALUES ('java.debugging.reproduce', 1, 9, 'Appended')",
                    "UPDATE evolution_skill_graphs SET published = 0 WHERE graph_id = 'java-debugging-concurrency'",
                    "DELETE FROM evolution_skill_graphs WHERE graph_id = 'java-debugging-concurrency'",
                    "DELETE FROM evolution_graph_skills WHERE graph_id = 'java-debugging-concurrency'",
                    "DELETE FROM evolution_skill_prerequisites WHERE graph_id = 'java-debugging-concurrency'",
                    "INSERT INTO evolution_skill_prerequisites VALUES ('java-debugging-concurrency', 1, 'java.debugging.reproduce', 1, 'java.concurrency.liveness', 1)")) {
                assertThrows(SQLException.class, () -> sql.execute(statement), statement);
            }
        }
        assertEquals(JavaDebuggingSkillSeed.graph(), repository.findGraph(JavaDebuggingSkillSeed.GRAPH).orElseThrow());
    }

    @Test
    void databaseRejectsWrongMemberVersionsDuplicateEdgesAndCyclesOnPublication() throws SQLException {
        var a = skill("raw.a"); var b = skill("raw.b");
        repository.publishDefinition(a); repository.publishDefinition(b);
        try (Connection connection = foreignKeyConnection(); Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.execute("INSERT INTO evolution_skill_definitions VALUES ('raw.behavior', 1, 'example', 'Name', 'Description', 'ACTIVE', 0)");
                assertThrows(SQLException.class, () -> sql.execute("INSERT INTO evolution_skill_behaviors VALUES ('raw.behavior', 1, 0.5, 'Not an integer position')"));
                assertThrows(SQLException.class, () -> sql.execute("UPDATE evolution_skill_definitions SET published = 1 WHERE skill_id = 'raw.behavior'"));
                sql.execute("INSERT INTO evolution_skill_graphs VALUES ('test.raw', 1, 0)");
                assertThrows(SQLException.class, () -> sql.execute("UPDATE evolution_skill_graphs SET published = 1 WHERE graph_id = 'test.raw'"));
                assertThrows(SQLException.class, () -> sql.execute("INSERT INTO evolution_graph_skills VALUES ('test.raw', 1, 'unknown.skill', 1)"));
                sql.execute("INSERT INTO evolution_graph_skills VALUES ('test.raw', 1, 'raw.a', 1)");
                sql.execute("INSERT INTO evolution_graph_skills VALUES ('test.raw', 1, 'raw.b', 1)");
                assertThrows(SQLException.class, () -> sql.execute("INSERT INTO evolution_graph_skills VALUES ('test.raw', 1, 'raw.a', 2)"));
                assertThrows(SQLException.class, () -> sql.execute("INSERT INTO evolution_skill_prerequisites VALUES ('test.raw', 1, 'raw.a', 2, 'raw.b', 1)"));
                assertThrows(SQLException.class, () -> sql.execute("INSERT INTO evolution_skill_prerequisites VALUES ('test.raw', 1, 'raw.a', 1, 'raw.a', 1)"));
                sql.execute("INSERT INTO evolution_skill_prerequisites VALUES ('test.raw', 1, 'raw.a', 1, 'raw.b', 1)");
                assertThrows(SQLException.class, () -> sql.execute("INSERT INTO evolution_skill_prerequisites VALUES ('test.raw', 1, 'raw.a', 1, 'raw.b', 1)"));
                sql.execute("INSERT INTO evolution_skill_prerequisites VALUES ('test.raw', 1, 'raw.b', 1, 'raw.a', 1)");
                var cycle = assertThrows(SQLException.class, () -> sql.execute("UPDATE evolution_skill_graphs SET published = 1 WHERE graph_id = 'test.raw'"));
                assertTrue(cycle.getMessage().contains("cycle"));
            } finally { connection.rollback(); }
        }
        assertEquals(0, count("evolution_skill_graphs", "graph_id = 'test.raw'"));
    }

    @Test
    void callerOwnedPublicationRequiresTransactionAndForeignKeysAndHonorsRollback() throws SQLException {
        try (Connection connection = DatabaseConfig.getConnection()) {
            assertThrows(IllegalArgumentException.class, () -> repository.publishDefinition(connection, skill("test.caller")));
            connection.setAutoCommit(false);
            assertThrows(IllegalArgumentException.class, () -> repository.publishDefinition(connection, skill("test.caller")));
            connection.rollback();
        }
        try (Connection connection = foreignKeyConnection()) {
            connection.setAutoCommit(false);
            repository.publishDefinition(connection, skill("test.caller"));
            connection.rollback();
        }
        assertTrue(repository.findSkill(new SkillRef("test.caller", 1)).isEmpty());
    }

    @Test
    void concurrentIdenticalPublishersAreIdempotentAndConflictingPublishersHaveOneWinner() throws Exception {
        var definition = skill("concurrent.same");
        runPair(() -> repository.publishDefinition(definition), () -> new SkillCatalogRepository().publishDefinition(definition));
        assertEquals(1, count("evolution_skill_definitions", "skill_id = 'concurrent.same'"));
        var graph = new SkillGraph(new GraphRef("concurrent.graph", 1), List.of(definition), List.of());
        runPair(() -> repository.publishGraph(graph), () -> new SkillCatalogRepository().publishGraph(graph));
        assertEquals(1, count("evolution_skill_graphs", "graph_id = 'concurrent.graph'"));
        var first = skill("concurrent.conflict");
        var second = new SkillDefinition(first.ref(), first.domainId(), "Different name", first.description(), first.observableBehaviors(), first.lifecycle());
        var successes = new java.util.concurrent.atomic.AtomicInteger();
        var conflicts = new java.util.concurrent.atomic.AtomicInteger();
        runPair(() -> publishCandidate(first, successes, conflicts), () -> publishCandidate(second, successes, conflicts));
        assertEquals(1, successes.get());
        assertEquals(1, conflicts.get());
        assertTrue(List.of(first, second).contains(repository.findSkill(first.ref()).orElseThrow()));
    }

    private void publishCandidate(SkillDefinition definition, java.util.concurrent.atomic.AtomicInteger success,
                                  java.util.concurrent.atomic.AtomicInteger conflict) {
        try { repository.publishDefinition(definition); success.incrementAndGet(); }
        catch (IllegalArgumentException expected) { conflict.incrementAndGet(); }
    }

    private static void runPair(Runnable first, Runnable second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); first.run(); return null; });
            var b = executor.submit(() -> { start.await(); second.run(); return null; });
            start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
    }

    private static Connection foreignKeyConnection() throws SQLException {
        Connection connection = DatabaseConfig.getConnection();
        try (Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys = ON"); }
        return connection;
    }

    private static Connection countQueries(Connection connection, AtomicInteger count) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(connection, arguments);
                        if (result instanceof java.sql.PreparedStatement statement) {
                            return Proxy.newProxyInstance(java.sql.PreparedStatement.class.getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class}, (statementProxy, statementMethod, statementArguments) -> {
                                        if (statementMethod.getName().equals("executeQuery")) count.incrementAndGet();
                                        try {
                                            return statementMethod.invoke(statement, statementArguments);
                                        } catch (InvocationTargetException exception) {
                                            throw exception.getCause();
                                        }
                                    });
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    @FunctionalInterface
    private interface SqlRead<T> {
        T read(Connection connection) throws Exception;
    }

    private record ReadMeasurement<T>(T value, int queries, long elapsedNanos) { }

    private static <T> ReadMeasurement<T> measure(SqlRead<T> read) throws Exception {
        AtomicInteger queries = new AtomicInteger();
        try (Connection connection = DatabaseConfig.getConnection()) {
            long started = System.nanoTime();
            T value = read.read(countQueries(connection, queries));
            return new ReadMeasurement<>(value, queries.get(), System.nanoTime() - started);
        }
    }

    private static <T> T readSnapshot(Connection connection, SqlRead<T> read) throws Exception {
        connection.setAutoCommit(false);
        try {
            T result = read.read(connection);
            connection.commit();
            return result;
        } catch (Exception failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private static Optional<SkillGraph> legacyFindGraph(Connection connection, GraphRef ref) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM evolution_skill_graphs WHERE graph_id = ? AND graph_version = ? AND published = 1
                """)) {
            statement.setString(1, ref.graphId());
            statement.setInt(2, ref.graphVersion());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
            }
        }
        List<SkillRef> members = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT skill_id, definition_version FROM evolution_graph_skills WHERE graph_id = ? AND graph_version = ?
                """)) {
            statement.setString(1, ref.graphId());
            statement.setInt(2, ref.graphVersion());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) members.add(new SkillRef(rows.getString(1), rows.getInt(2)));
            }
        }
        List<SkillDefinition> definitions = new ArrayList<>();
        for (SkillRef member : members) definitions.add(legacyFindSkill(connection, member));
        List<SkillPrerequisite> edges = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT prerequisite_skill_id, prerequisite_version, dependent_skill_id, dependent_version
                FROM evolution_skill_prerequisites WHERE graph_id = ? AND graph_version = ?
                """)) {
            statement.setString(1, ref.graphId());
            statement.setInt(2, ref.graphVersion());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) edges.add(new SkillPrerequisite(new SkillRef(rows.getString(1), rows.getInt(2)),
                        new SkillRef(rows.getString(3), rows.getInt(4))));
            }
        }
        return Optional.of(new SkillGraph(ref, definitions, edges));
    }

    private static List<SkillDefinition> legacyListSkillsInDomain(Connection connection, String domainId) throws SQLException {
        List<SkillRef> refs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT skill_id, definition_version FROM evolution_skill_definitions
                WHERE domain_id = ? AND published = 1 ORDER BY skill_id, definition_version
                """)) {
            statement.setString(1, domainId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) refs.add(new SkillRef(rows.getString(1), rows.getInt(2)));
            }
        }
        List<SkillDefinition> definitions = new ArrayList<>();
        for (SkillRef ref : refs) definitions.add(legacyFindSkill(connection, ref));
        return List.copyOf(definitions);
    }

    private static SkillDefinition legacyFindSkill(Connection connection, SkillRef ref) throws SQLException {
        String domainId;
        String name;
        String description;
        SkillLifecycle lifecycle;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT domain_id, name, description, lifecycle FROM evolution_skill_definitions
                WHERE skill_id = ? AND definition_version = ? AND published = 1
                """)) {
            statement.setString(1, ref.skillId());
            statement.setInt(2, ref.definitionVersion());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("Missing published skill definition " + ref);
                domainId = row.getString(1);
                name = row.getString(2);
                description = row.getString(3);
                lifecycle = SkillLifecycle.valueOf(row.getString(4));
            }
        }
        List<String> behaviors = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT position, behavior FROM evolution_skill_behaviors
                WHERE skill_id = ? AND definition_version = ? ORDER BY position
                """)) {
            statement.setString(1, ref.skillId());
            statement.setInt(2, ref.definitionVersion());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt(1) != behaviors.size()) throw new IllegalStateException("Incomplete behaviors for " + ref);
                    behaviors.add(rows.getString(2));
                }
            }
        }
        return new SkillDefinition(ref, domainId, name, description, behaviors, lifecycle);
    }

    private static int count(String table, String where) {
        try (Connection connection = DatabaseConfig.getConnection(); Statement sql = connection.createStatement();
             ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            rows.next(); return rows.getInt(1);
        } catch (SQLException failure) { throw new AssertionError(failure); }
    }
}

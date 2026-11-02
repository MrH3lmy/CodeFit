package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.evidence.*;
import com.codefit.repository.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.fasterxml.jackson.core.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;

/**
 * Author-packet verification only. No loader or candidate class enters the application classpath.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SqlCandidateTest {
    static final Path ROOT = Path.of("docs/curriculum/sql-transactions/v1");
    static final String PIN = "62977983830e54bcfc33d267099316053e6494a136c2c3c69c2a71a5e349776c";
    @TempDir Path dir;

    static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Object value(JsonParser p, int depth) throws Exception {
        if (depth > 16) throw new IllegalArgumentException("Depth");
        return switch (p.currentToken()) {
            case START_OBJECT -> {
                var m = new LinkedHashMap<String, Object>();
                while (p.nextToken() != JsonToken.END_OBJECT) {
                    String name = p.currentName();
                    p.nextToken();
                    if (m.containsKey(name)) throw new IllegalArgumentException("Duplicate field");
                    m.put(name, value(p, depth + 1));
                }
                yield m;
            }
            case START_ARRAY -> {
                var a = new ArrayList<>();
                while (p.nextToken() != JsonToken.END_ARRAY) a.add(value(p, depth + 1));
                yield a;
            }
            case VALUE_STRING -> p.getText();
            case VALUE_NUMBER_INT -> p.getLongValue();
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("Unsupported JSON");
        };
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object x) {
        return (Map<String, Object>) x;
    }

    @SuppressWarnings("unchecked")
    static List<Object> array(Object x) {
        return (List<Object>) x;
    }

    static Map<String, Object> json(String file) throws Exception {
        var bytes = Files.readAllBytes(ROOT.resolve(file));
        assertTrue(bytes.length < 131072);
        try (var p = new JsonFactory().createParser(bytes)) {
            p.nextToken();
            var result = object(value(p, 0));
            assertNull(p.nextToken());
            return result;
        }
    }

    static int number(Map<String, Object> m, String k) {
        return Math.toIntExact((Long) m.get(k));
    }

    static SkillGraph graph() throws Exception {
        var root = json("graph.json");
        assertEquals(
                Set.of(
                        "schemaVersion",
                        "graphId",
                        "graphVersion",
                        "status",
                        "independentApprovals",
                        "productionPublication",
                        "assessmentAvailable",
                        "skills",
                        "prerequisites"),
                root.keySet());
        assertEquals("AUTHOR_DRAFT_PENDING_EXTERNAL_REVIEW", root.get("status"));
        assertEquals(0, number(root, "independentApprovals"));
        assertEquals(false, root.get("productionPublication"));
        assertEquals(false, root.get("assessmentAvailable"));
        var definitions = new ArrayList<SkillDefinition>();
        for (var x : array(root.get("skills"))) {
            var s = object(x);
            assertEquals(
                    Set.of(
                            "id",
                            "version",
                            "domain",
                            "name",
                            "description",
                            "lifecycle",
                            "behaviors"),
                    s.keySet());
            definitions.add(
                    new SkillDefinition(
                            new SkillRef((String) s.get("id"), number(s, "version")),
                            (String) s.get("domain"),
                            (String) s.get("name"),
                            (String) s.get("description"),
                            array(s.get("behaviors")).stream().map(String.class::cast).toList(),
                            SkillLifecycle.valueOf((String) s.get("lifecycle"))));
        }
        var edges = new ArrayList<SkillPrerequisite>();
        for (var x : array(root.get("prerequisites"))) {
            var e = object(x);
            assertEquals(Set.of("from", "fromVersion", "to", "toVersion"), e.keySet());
            edges.add(
                    new SkillPrerequisite(
                            new SkillRef((String) e.get("from"), number(e, "fromVersion")),
                            new SkillRef((String) e.get("to"), number(e, "toVersion"))));
        }
        return new SkillGraph(
                new GraphRef((String) root.get("graphId"), number(root, "graphVersion")),
                definitions,
                edges);
    }

    static void publish(java.sql.Connection c, SkillGraph g) throws Exception {
        var r = new SkillCatalogRepository();
        for (var d : g.definitions()) r.publishDefinition(c, d);
        r.publishGraph(c, g);
    }

    @Test
    void pinnedManifestCoversEveryPacketFileAndPendingReview() throws Exception {
        assertEquals(PIN, digest(Files.readAllBytes(ROOT.resolve("manifest.json"))));
        assertEquals(PIN + "  manifest.json\n", Files.readString(ROOT.resolve("manifest.sha256")));
        var manifest = json("manifest.json");
        assertEquals(false, manifest.get("productionPublication"));
        assertEquals(false, manifest.get("assessmentAvailable"));
        assertEquals(0, number(manifest, "independentApprovals"));
        assertEquals("PENDING_OWNER_AUTHORIZATION", manifest.get("licenseDecision"));
        var seen = new HashSet<String>();
        for (var x : array(manifest.get("files"))) {
            var f = object(x);
            String name = (String) f.get("path");
            assertTrue(name.matches("[A-Za-z-]+[.](json|md)"));
            assertTrue(seen.add(name));
            var b = Files.readAllBytes(ROOT.resolve(name));
            assertEquals((long) b.length, f.get("bytes"));
            assertEquals(digest(b), f.get("sha256"));
        }
        try (var files = Files.list(ROOT)) {
            assertEquals(
                    files.map(p -> p.getFileName().toString())
                            .filter(n -> !n.startsWith("manifest"))
                            .collect(java.util.stream.Collectors.toSet()),
                    seen);
        }
        for (String kind : List.of("technical", "pedagogical")) {
            var review = json(kind + "-review.json");
            assertEquals("PENDING", review.get("status"));
            for (String k :
                    List.of(
                            "reviewerIdentity",
                            "credentials",
                            "conflicts",
                            "reviewedManifestSha256",
                            "decisionAt",
                            "ownerAdjudication")) assertNull(review.get(k));
            assertTrue(array(review.get("findings")).isEmpty());
        }
        var changed = Files.readAllBytes(ROOT.resolve("graph.json"));
        changed[changed.length - 1] = ' ';
        assertNotEquals(digest(Files.readAllBytes(ROOT.resolve("graph.json"))), digest(changed));
    }

    @Test
    void exactSixSkillDagIsImmutableAndBehaviorProposalIsUnavailable() throws Exception {
        var g = graph();
        assertEquals(new GraphRef("sql-transactional-consistency", 1), g.ref());
        assertEquals(6, g.definitions().size());
        assertEquals(6, g.prerequisites().size());
        assertEquals(6, g.topologicalOrder().size());
        assertTrue(
                g.definitions().stream()
                        .allMatch(
                                d ->
                                        d.ref().definitionVersion() == 1
                                                && d.observableBehaviors().size() == 3));
        assertThrows(UnsupportedOperationException.class, () -> g.definitions().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> g.definitions().getFirst().observableBehaviors().clear());
        var proposal = json("mapping-proposal.json");
        assertEquals(false, proposal.get("published"));
        assertNull(proposal.get("runtimeMappingRef"));
        assertEquals(g.ref().graphId(), proposal.get("graphId"));
        assertEquals(1, number(proposal, "graphVersion"));
        var targets = new HashSet<SkillRef>();
        for (var x : array(proposal.get("targets"))) {
            var t = object(x);
            assertEquals("UNAVAILABLE", t.get("mechanism"));
            assertEquals("UNVERIFIED", t.get("verification"));
            var ref = new SkillRef((String) t.get("skillId"), number(t, "skillVersion"));
            assertTrue(targets.add(ref));
            assertTrue(g.definitions().stream().anyMatch(d -> d.ref().equals(ref)));
            assertEquals(List.of(1L, 2L, 3L), t.get("behaviors"));
        }
        assertEquals(6, targets.size());
    }

    @Test
    void cyclesMissingRefsDuplicatesAndInvalidBehaviorsAreRejected() throws Exception {
        var g = graph();
        var defs = new ArrayList<>(g.definitions());
        defs.add(defs.getFirst());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillGraph(g.ref(), defs, g.prerequisites()));
        var conflicting = new ArrayList<>(g.definitions());
        var d = conflicting.getFirst();
        conflicting.add(
                new SkillDefinition(
                        new SkillRef(d.ref().skillId(), 2),
                        d.domainId(),
                        d.name(),
                        d.description(),
                        d.observableBehaviors(),
                        d.lifecycle()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillGraph(g.ref(), conflicting, g.prerequisites()));
        var missing = new ArrayList<>(g.prerequisites());
        missing.add(new SkillPrerequisite(new SkillRef("sql.missing", 1), d.ref()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillGraph(g.ref(), g.definitions(), missing));
        var cyclic = new ArrayList<>(g.prerequisites());
        var edge = cyclic.getFirst();
        cyclic.add(new SkillPrerequisite(edge.dependent(), edge.prerequisite()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SkillGraph(g.ref(), g.definitions(), cyclic));
        for (var behaviors : List.of(List.<String>of(), List.of(" "), List.of("same", "same")))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new SkillDefinition(
                                    d.ref(),
                                    d.domainId(),
                                    d.name(),
                                    d.description(),
                                    behaviors,
                                    SkillLifecycle.ACTIVE));
    }

    @Test
    void authorReadNeverPublishesOrChangesHistoryAndPilotRemainsExact() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            com.codefit.config.DatabaseConfig.useDatabaseFile(p.database());
            AnalyticsFixture.seed(
                    p); // Real persisted pilot history in this isolated disposable DB.
            var snapshots = new PracticeSkillsIntegrationTest();
            var before = snapshots.database(p, false);
            assertNull(
                    getClass()
                            .getClassLoader()
                            .getResource("curriculum/sql-transactions/v1/graph.json"));
            assertEquals(
                    JavaDebuggingSkillSeed.graph(),
                    new SkillCatalogRepository()
                            .findGraph(JavaDebuggingSkillSeed.GRAPH)
                            .orElseThrow());
            var g = graph();
            var service = new PracticeGraphService(p, Clock.systemUTC());
            assertEquals(1, service.read(null).graphs().size());
            assertNull(service.read(g.ref()).detail());
            assertEquals(JavaDebuggingSkillSeed.GRAPH, service.read(null).selection().graph());
            assertEquals(before, snapshots.database(p, false));
        }
    }

    @Test
    void isolatedPublicationRejectsVersionReuseAndWrongMappingTargetThenRollsBack()
            throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var snapshots = new PracticeSkillsIntegrationTest();
            var before = snapshots.database(p, false);
            var g = graph();
            try (var c = p.connection(false)) {
                c.setAutoCommit(false);
                publish(c, g);
                publish(c, g);
                var d = g.definitions().getFirst();
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SkillCatalogRepository()
                                        .publishDefinition(
                                                c,
                                                new SkillDefinition(
                                                        d.ref(),
                                                        d.domainId(),
                                                        d.name(),
                                                        "Conflicting definition",
                                                        d.observableBehaviors(),
                                                        d.lifecycle())));
                var changedEdges = new ArrayList<>(g.prerequisites());
                changedEdges.removeLast();
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new SkillCatalogRepository()
                                        .publishGraph(
                                                c,
                                                new SkillGraph(
                                                        g.ref(), g.definitions(), changedEdges)));
                var wrong =
                        new EvidenceMapping(
                                new EvidenceMappingRef("sql.test-only", 1),
                                g.ref(),
                                List.of(
                                        new EvidenceMapping.Rule(
                                                EvidenceTypes.SourceType.CHALLENGE_EVALUATION,
                                                "not-an-installed-check",
                                                JavaDebuggingSkillSeed.graph()
                                                        .definitions()
                                                        .getFirst()
                                                        .ref())));
                assertThrows(
                        SQLException.class,
                        () -> new EvidenceMappingRepository().publish(c, wrong));
                c.rollback();
            }
            assertEquals(before, snapshots.database(p, false));
        }
    }

    @Test
    void testOnlyPublishedCandidateHasNoSqlAssessmentAdapterAndRetiredDefinitionsStayUnavailable()
            throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("p"))) {
            var g = graph();
            try (var c = p.connection(false)) {
                c.setAutoCommit(false);
                publish(c, g);
                var d = g.definitions().getFirst();
                var retired =
                        new SkillDefinition(
                                new SkillRef(d.ref().skillId(), 2),
                                d.domainId(),
                                d.name(),
                                d.description(),
                                d.observableBehaviors(),
                                SkillLifecycle.RETIRED);
                new SkillCatalogRepository().publishDefinition(c, retired);
                new SkillCatalogRepository()
                        .publishGraph(
                                c,
                                new SkillGraph(
                                        new GraphRef(g.ref().graphId(), 2),
                                        List.of(retired),
                                        List.of()));
                c.commit();
            }
            var snapshots = new PracticeSkillsIntegrationTest();
            var before = snapshots.database(p, false);
            var view = new PracticeGraphService(p, Clock.systemUTC()).read(g.ref());
            var summary =
                    view.graphs().stream()
                            .filter(s -> s.ref().equals(g.ref()))
                            .findFirst()
                            .orElseThrow();
            assertEquals("UNSUPPORTED_GRAPH", summary.compatibility().estimator());
            assertEquals("NONE", summary.compatibility().mapping());
            assertEquals(JavaDebuggingSkillSeed.GRAPH, view.selection().graph());
            assertEquals(
                    "RETIRED_SKILL",
                    new PracticeGraphService(p, Clock.systemUTC())
                            .read(null).graphs().stream()
                                    .filter(s -> s.ref().graphVersion() == 2)
                                    .findFirst()
                                    .orElseThrow()
                                    .selectable());
            assertEquals(before, snapshots.database(p, false));
        }
    }

    @Test
    void testOnlyRetiredGraphRetainsExactDefinitionsWithoutChangingSelection() throws Exception {
        try (var p = ExperimentalProfile.openLearning(dir.resolve("retired"))) {
            var g = graph();
            try (var c = p.connection(false)) {
                c.setAutoCommit(false);
                publish(c, g);
                new GraphSelectionRepository().lifecycle(c, g.ref(), 0, "RETIRED", null);
                c.commit();
            }
            var snapshots = new PracticeSkillsIntegrationTest();
            var before = snapshots.database(p, false);
            var view = new PracticeGraphService(p, Clock.systemUTC()).read(g.ref());
            assertEquals(g, view.detail());
            assertEquals(JavaDebuggingSkillSeed.GRAPH, view.selection().graph());
            assertEquals(
                    "GRAPH_RETIRED",
                    view.graphs().stream()
                            .filter(x -> x.ref().equals(g.ref()))
                            .findFirst()
                            .orElseThrow()
                            .selectable());
            assertEquals(before, snapshots.database(p, false));
        }
    }
}

package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.*;
import com.codefit.repository.*;
import com.codefit.service.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class QuestCatalogIntegrationTest {
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();

    @Test
    void repeatedPublicationAndStartupKeepBindingsAndExistingHistory() {
        var bindings = bindings();
        var rows = sourceRows();
        catalog.publish(JavaTrainingPack.quests(), JavaTrainingPack.challenges());
        catalog.publish(JavaTrainingPack.quests(), JavaTrainingPack.challenges());
        DatabaseConfig.initialize();
        assertEquals(bindings, bindings());
        assertEquals(rows, sourceRows());
        assertEquals(12, bindings.size());
        assertEquals(7, catalog.quests().size());
        catalog.transaction(
                c -> {
                    try (var s = c.createStatement();
                            var rs = s.executeQuery("PRAGMA foreign_key_check")) {
                        assertFalse(rs.next());
                    }
                    return null;
                });
    }

    @Test
    void publishedChallengeAndRubricVersionsCannotBeOverwritten() {
        var cs = JavaTrainingPack.challenges();
        var c = cs.getFirst();
        String text = CatalogCodec.encode(c);
        var changed =
                CatalogCodec.challenge(
                        text.replace("objective=" + c.objective(), "objective=Changed objective"));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.publish(JavaTrainingPack.quests(), replace(cs, changed)));
        var changedRubric =
                CatalogCodec.challenge(
                        text.replace("rubric.limitations=", "rubric.limitations=Changed. "));
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.publish(JavaTrainingPack.quests(), replace(cs, changedRubric)));
        assertEquals(c, catalog.findChallenge(c.ref()).orElseThrow());
    }

    @Test
    void exactGraphAndFamilyCohortAndVariantIdentitiesReject() {
        var cs = JavaTrainingPack.challenges();
        var c = cs.getFirst();
        String text = CatalogCodec.encode(c);
        for (String changed :
                List.of(
                        text.replace("graph.version=1", "graph.version=99"),
                        text.replace("cohort=PRACTICE", "cohort=TRANSFER"))) {
            if (changed.equals(text)) continue;
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            catalog.publish(
                                    JavaTrainingPack.quests(),
                                    replace(cs, CatalogCodec.challenge(changed))));
        }
        var counter = get("counter-repair");
        String wrong =
                CatalogCodec.encode(counter)
                        .replace("challenge.id=counter-repair", "challenge.id=counter-copy");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        catalog.publish(
                                JavaTrainingPack.quests(), List.of(CatalogCodec.challenge(wrong))));
    }

    @Test
    void publicationRollsBackAllEarlierInsertsOnLateConflict() {
        var cs = JavaTrainingPack.challenges();
        var c = get("counter-repair");
        var newVersion =
                CatalogCodec.challenge(
                        CatalogCodec.encode(c)
                                .replace("challenge.version=1", "challenge.version=2"));
        var bad =
                CatalogCodec.quest(
                        CatalogCodec.encode(JavaTrainingPack.quests().getFirst())
                                .replace("narrative=", "narrative=Conflict. "));
        int before = new ProblemRepository().countAll();
        assertThrows(
                IllegalArgumentException.class,
                () -> catalog.publish(List.of(bad), List.of(newVersion)));
        assertTrue(catalog.findChallenge(newVersion.ref()).isEmpty());
        assertEquals(before, new ProblemRepository().countAll());
    }

    @Test
    void newVersionHasSeparateBindingAndRetainsHistoricalManifest() {
        var c = get("counter-repair");
        var newer =
                CatalogCodec.challenge(
                        CatalogCodec.encode(c)
                                .replace("challenge.version=1", "challenge.version=2"));
        var q =
                JavaTrainingPack.quests().stream()
                        .filter(x -> x.ref().id().equals("broken-counter"))
                        .findFirst()
                        .orElseThrow();
        var next =
                CatalogCodec.quest(
                        CatalogCodec.encode(q)
                                .replace("quest.version=1", "quest.version=2")
                                .replace("challenges.1.version=1", "challenges.1.version=2"));
        catalog.publish(List.of(next), List.of(newer));
        assertEquals(c, catalog.findChallenge(c.ref()).orElseThrow());
        assertEquals(newer, catalog.findChallenge(newer.ref()).orElseThrow());
        assertNotEquals(
                (long) catalog.transaction(db -> catalog.problemId(db, c.ref())),
                (long) catalog.transaction(db -> catalog.problemId(db, newer.ref())));
    }

    @Test
    void sqliteGuardsRejectMetadataMutationAndWorkbookInsertion() {
        var c = get("baseline-counter");
        long id = catalog.transaction(db -> catalog.problemId(db, c.ref()));
        assertThrows(
                IllegalStateException.class,
                () ->
                        catalog.transaction(
                                db -> {
                                    try (var s = db.createStatement()) {
                                        s.executeUpdate(
                                                "UPDATE problems SET title='leak' WHERE id=" + id);
                                    }
                                    return null;
                                }));
        assertThrows(
                IllegalStateException.class,
                () ->
                        catalog.transaction(
                                db -> {
                                    try (var s = db.createStatement()) {
                                        s.executeUpdate(
                                                "DELETE FROM evolution_challenge_definitions");
                                    }
                                    return null;
                                }));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProblemService()
                                .findOrCreateProblem(
                                        "CODEFIT_EVOLUTION",
                                        "x",
                                        "leak",
                                        null,
                                        null,
                                        null,
                                        List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ProblemService()
                                .addToRoadmap(
                                        id,
                                        com.codefit.model.RoadmapStage.A,
                                        99000,
                                        null,
                                        true,
                                        null));
    }

    @Test
    void challengeCannotChangeFamilyOrVariantAcrossVersions() {
        var c = get("remaining-capacity");
        String text = CatalogCodec.encode(c).replace("challenge.version=1", "challenge.version=3");
        for (String changed :
                List.of(
                        text.replace("family=bounded-capacity", "family=relabelled-capacity"),
                        text.replace("variant=remaining-capacity", "variant=newly-unseen"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            catalog.publish(
                                    JavaTrainingPack.quests(),
                                    List.of(CatalogCodec.challenge(changed))));
        }
    }

    @Test
    void concurrentIdenticalPublishersAreIdempotent() throws Exception {
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Void> work =
                    () -> {
                        start.await();
                        catalog.publish(JavaTrainingPack.quests(), JavaTrainingPack.challenges());
                        return null;
                    };
            var a = pool.submit(work);
            var b = pool.submit(work);
            start.countDown();
            a.get(15, java.util.concurrent.TimeUnit.SECONDS);
            b.get(15, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertEquals(12, bindings().size());
    }

    private Map<String, Long> bindings() {
        return catalog.transaction(
                db -> {
                    Map<String, Long> r = new TreeMap<>();
                    try (var s = db.createStatement();
                            var rs =
                                    s.executeQuery(
                                            "SELECT id,version,problem_id FROM"
                                                + " evolution_challenge_definitions WHERE version=1"
                                                + " AND problem_id IS NOT NULL")) {
                        while (rs.next())
                            r.put(rs.getString(1) + "@" + rs.getInt(2), rs.getLong(3));
                    }
                    return r;
                });
    }

    private List<String> sourceRows() {
        return catalog.transaction(
                db -> {
                    List<String> r = new ArrayList<>();
                    for (String table :
                            List.of(
                                    "problem_attempts",
                                    "problem_progress",
                                    "roadmap_entries",
                                    "assessment_attempts",
                                    "user_progress",
                                    "evolution_evidence_observations",
                                    "publication_outbox",
                                    "peer_progress_summaries"))
                        try (var s = db.createStatement();
                                var rs =
                                        s.executeQuery(
                                                "SELECT * FROM " + table + " ORDER BY rowid")) {
                            while (rs.next()) {
                                StringBuilder row = new StringBuilder(table);
                                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
                                    row.append('|').append(rs.getString(i));
                                r.add(row.toString());
                            }
                        }
                    return r;
                });
    }

    private ChallengeSpec get(String id) {
        return catalog.findChallenge(new CatalogRef(id, 1)).orElseThrow();
    }

    private List<ChallengeSpec> replace(List<ChallengeSpec> cs, ChallengeSpec replacement) {
        return cs.stream().map(x -> x.ref().equals(replacement.ref()) ? replacement : x).toList();
    }
}

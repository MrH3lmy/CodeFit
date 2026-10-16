package com.codefit.evolution.player;

import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.quest.*;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.service.ChallengePresentationService;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class EvolutionJournalTest {
    private ChallengePresentationService at(String instant) {
        return new ChallengePresentationService(
                new QuestCatalogRepository(),
                new BundledAssets(),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private String present(ChallengePresentationService p, String id, String challenge) {
        var spec = new CatalogRef(challenge, 1);
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(spec))
                        .findFirst()
                        .orElseThrow()
                        .ref();
        return p.present(
                        new ChallengePresentation.Request(
                                id, quest, spec, Cohort.BASELINE, Familiarity.UNKNOWN))
                .receipt()
                .id();
    }

    @Test
    void stableChronologicalNanosecondAndTiePaginationExcludesLaterBackdatedAppends()
            throws Exception {
        var first = at("2026-06-10T12:00:00.1Z");
        var second = at("2026-06-10T12:00:00.100000001Z");
        var presentation = present(first, "journal-a", "baseline-trace");
        first.revealHint(presentation, "hint-one", 1);
        first.revealHint(presentation, "hint-two", 2);
        present(second, "journal-b", "baseline-localize");
        var journal = new EvolutionJournal();
        var all = journal.page(null, 64).entries();
        assertEquals(4, all.size());
        assertEquals("journal-b", all.getFirst().ref());
        var page = journal.page(null, 1);
        var collected = new ArrayList<>(page.entries());
        // A concurrent event with an older event time must not enter this journal traversal.
        first.declareFamiliar(presentation, "backdated-after-first-page");
        var cursor = page.next();
        while (cursor != null) {
            page = journal.page(cursor, 1);
            collected.addAll(page.entries());
            cursor = page.next();
        }
        assertEquals(all, collected);
        assertEquals(4, new HashSet<>(collected).size());
        assertFalse(collected.toString().contains("final=1;serial=2"));
        assertFalse(collected.toString().contains("public class Main"));
        assertEquals(5, journal.page(null, 64).entries().size());
    }

    @Test
    void journalReadsArePureAndBounded() throws Exception {
        var journal = new EvolutionJournal();
        assertThrows(IllegalArgumentException.class, () -> journal.page(null, 0));
        assertThrows(IllegalArgumentException.class, () -> journal.page(null, 65));
        var before = rows();
        journal.page(null, 10);
        journal.recent();
        assertEquals(before, rows());
    }

    private List<Long> rows() throws Exception {
        var out = new ArrayList<Long>();
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement()) {
            for (String table :
                    List.of(
                            "evolution_submissions",
                            "evolution_evidence_observations",
                            "evolution_estimate_revisions",
                            "evolution_adaptation_decisions"))
                try (var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    r.next();
                    out.add(r.getLong(1));
                }
        }
        return out;
    }
}

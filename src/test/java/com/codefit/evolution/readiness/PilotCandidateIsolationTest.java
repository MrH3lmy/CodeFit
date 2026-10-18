package com.codefit.evolution.readiness;

import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PilotCandidateIsolationTest {
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();

    @Test
    void inspectionAndRepeatedStartupDoNotPublishCandidateOrChangeExistingDraftAndHistory() {
        var presentations = new ChallengePresentationService();
        presentations.present(
                new ChallengePresentation.Request(
                        "readiness-existing-draft",
                        new CatalogRef("broken-counter", 1),
                        new CatalogRef("counter-repair", 1),
                        Cohort.PRACTICE,
                        Familiarity.UNKNOWN));
        presentations.saveDraft("readiness-existing-draft", "// preserved local learner draft");
        var before = rows();
        for (int i = 0; i < 2; i++) {
            new AssessmentFormReadiness().installedReport(JavaDebuggingSkillSeed.graph());
            DatabaseConfig.initialize();
            assertTrue(
                    new QuestCatalogRepository()
                            .findChallenge(PilotAssessmentForms.baselineCapacity().ref())
                            .isEmpty());
        }
        assertEquals(
                before,
                rows(),
                "Inspection/startup must not mutate learner, evidence, reward or peer rows");
        assertEquals(14, JavaTrainingPack.challenges().size());
        assertEquals(7, catalog.quests().size());
        assertEquals(
                25,
                (int)
                        catalog.transaction(
                                c -> {
                                    try (var s = c.createStatement();
                                            var r =
                                                    s.executeQuery(
                                                            "SELECT MAX(version) FROM"
                                                                + " schema_migrations")) {
                                        r.next();
                                        return r.getInt(1);
                                    }
                                }));
    }

    @Test
    void uninstalledCandidateCannotPresentResumeRevealPracticeOrPrepareEvenAfterRestart() {
        var before = rows();
        var candidate = PilotAssessmentForms.baselineCapacity();
        for (int i = 0; i < 2; i++) {
            var service = new ChallengePresentationService();
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            service.present(
                                    new ChallengePresentation.Request(
                                            "unpublished-candidate",
                                            new CatalogRef("initial-diagnostic", 1),
                                            candidate.ref(),
                                            Cohort.BASELINE,
                                            Familiarity.UNKNOWN)));
            assertThrows(
                    IllegalArgumentException.class, () -> service.resume("unpublished-candidate"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.revealHint("unpublished-candidate", "candidate-hint", 1));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.revealSolution("unpublished-candidate", "candidate-solution"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> service.beginPractice("unpublished-candidate", "candidate-practice"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new ChallengeEvaluationCoordinator()
                                    .prepare(
                                            new SubmissionRequest(
                                                    "candidate-submission",
                                                    "unpublished-candidate",
                                                    "private artifact",
                                                    EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                                                    "")));
            assertTrue(service.history(candidate.familyId()).isEmpty());
            DatabaseConfig.initialize();
        }
        assertEquals(
                before,
                rows(),
                "Rejected routes must leave no exposure, attempt, submission, reward or peer"
                        + " effects");
        assertTrue(
                new ProblemService()
                        .getAllProblems().stream()
                                .noneMatch(p -> p.getTitle().contains("baseline-capacity")));
        assertFalse(new ProblemLibraryService().getDistinctTopics().contains("Evolution"));
    }

    /** All local owners, including rewards/P2P, rather than a hand-picked counter subset. */
    private List<String> rows() {
        return catalog.transaction(
                c -> {
                    var tables = new ArrayList<String>();
                    try (var s = c.createStatement();
                            var r =
                                    s.executeQuery(
                                            "SELECT name FROM sqlite_master WHERE type='table' AND"
                                                    + " name NOT LIKE 'sqlite_%' ORDER BY name")) {
                        while (r.next()) tables.add(r.getString(1));
                    }
                    var result = new ArrayList<String>();
                    for (String table : tables) {
                        if (!table.matches("[A-Za-z0-9_]+"))
                            throw new AssertionError("Unexpected table identity");
                        try (var s = c.createStatement();
                                var r = s.executeQuery("SELECT * FROM " + table)) {
                            while (r.next()) {
                                var row = new ArrayList<String>();
                                row.add(table);
                                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                                    row.add(r.getString(i));
                                result.add(row.toString());
                            }
                        }
                    }
                    Collections.sort(result);
                    return result;
                });
    }
}

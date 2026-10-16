package com.codefit.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance;
import com.codefit.evolution.player.PlayerSystemService;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.Familiarity;
import com.codefit.service.ChallengePresentationService;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.ui.*;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class EvolutionControllerTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC);
    @TempDir Path dir;
    String previous;
    EvolutionController controller;
    FXMLLoader loader;
    Parent root;
    Stage stage;

    @BeforeAll
    static void toolkit() {
        Assumptions.assumeTrue(
                FxToolkitSupport.isAvailable(),
                "Real JavaFX display unavailable; run under Xvfb or a desktop");
        Platform.setImplicitExit(false);
    }

    @BeforeEach
    void load() throws Exception {
        previous = DatabaseConfig.currentDatabaseUrl();
        DatabaseConfig.useDatabaseFile(dir.resolve("ui.db"));
        DatabaseConfig.initialize();
        fx(
                () -> {
                    loader = new FXMLLoader(getClass().getResource("/fxml/evolution.fxml"));
                    loader.setControllerFactory(
                            type -> {
                                assertEquals(EvolutionController.class, type);
                                return new EvolutionController(
                                        () -> {
                                            assertFalse(
                                                    Platform.isFxApplicationThread(),
                                                    "Service construction / local runner discovery"
                                                            + " must be off FX");
                                            return new PlayerSystemService(CLOCK);
                                        });
                            });
                    root = loader.load();
                    controller = loader.getController();
                    stage = new Stage();
                    var scene = new Scene(root, 1220, 1000);
                    for (String css :
                            List.of("tokens", "base", "controls", "solving-workspace", "evolution"))
                        scene.getStylesheets()
                                .add(
                                        getClass()
                                                .getResource("/css/" + css + ".css")
                                                .toExternalForm());
                    root.getStyleClass().add("theme-light");
                    stage.setScene(scene);
                    stage.show();
                    return null;
                });
        awaitIdle();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (controller != null) {
            var disposed = EvolutionController.class.getDeclaredField("disposed");
            disposed.setAccessible(true);
            if (!disposed.getBoolean(controller)) awaitIdle();
            fx(
                    () -> {
                        controller.dispose();
                        stage.hide();
                        return null;
                    });
        }
        DatabaseConfig.useDatabaseUrl(previous);
    }

    @Test
    void actualFxmlLoadsFourUnknownSkillsAndBothThemesAtDesktopAndNarrowSizes() throws Exception {
        String text = fx(() -> allText((Parent) loader.getNamespace().get("skillsBox")));
        assertEquals(4, countOccurrences(text, "UNKNOWN · INSUFFICIENT"));
        assertFalse(text.contains("%"));
        assertTrue(text.contains("0 independent families"));
        fx(
                () -> {
                    snapshot("evolution-overview-light.png");
                    root.getStyleClass().remove("theme-light");
                    root.getStyleClass().add("theme-dark");
                    stage.setWidth(800);
                    stage.setHeight(800);
                    return null;
                });
        Thread.sleep(150);
        fx(
                () -> {
                    assertTrue(root.getLayoutBounds().getWidth() < 900);
                    snapshot("evolution-overview-dark-narrow.png");
                    return null;
                });
        assertFalse(
                fx(() -> ((ProgressIndicator) loader.getNamespace().get("loading")).isVisible()));
    }

    @Test
    void baselinePromptFollowsExposureDraftSurvivesControllerRestartAndSubmissionClosesLoop()
            throws Exception {
        openCounter();
        assertEquals(1, scalar("evolution_challenge_presentations"));
        var prompt = (TextArea) loader.getNamespace().get("promptArea");
        assertFalse(fx(prompt::getText).isBlank());
        assertTrue(
                fx(() -> ((Label) loader.getNamespace().get("runnerNotice")).getText())
                        .contains("not a sandbox"));
        String reference =
                Files.readString(
                        Path.of(
                                "src/main/resources/evolution/packs/java-debugging-concurrency/v1/baseline-counter/reference.java.txt"));
        fx(
                () -> {
                    ((TextArea) loader.getNamespace().get("sourceArea")).setText(reference);
                    ((ComboBox<OutsideAssistance>) loader.getNamespace().get("assistanceBox"))
                            .setValue(OutsideAssistance.NONE_DECLARED);
                    findButton((Parent) loader.getNamespace().get("challengeActions"), "Save draft")
                            .fire();
                    return null;
                });
        awaitIdle();
        var old = controller;
        fx(
                () -> {
                    old.dispose();
                    stage.hide();
                    return null;
                });
        fx(
                () -> {
                    loader = new FXMLLoader(getClass().getResource("/fxml/evolution.fxml"));
                    loader.setControllerFactory(
                            t -> new EvolutionController(() -> new PlayerSystemService(CLOCK)));
                    root = loader.load();
                    controller = loader.getController();
                    stage = new Stage();
                    stage.setScene(new Scene(root, 1200, 950));
                    stage.show();
                    return null;
                });
        awaitIdle();
        fx(
                () -> {
                    var box = diagnosticCard("baseline-counter");
                    findButton(box, "Resume exposed diagnostic").fire();
                    return null;
                });
        awaitIdle();
        assertEquals(
                reference,
                fx(() -> ((TextArea) loader.getNamespace().get("sourceArea")).getText()));
        assertEquals(1, scalar("evolution_challenge_presentations"));
        fx(
                () -> {
                    findButton(
                                    (Parent) loader.getNamespace().get("challengeActions"),
                                    "Submit deliberate evaluation")
                            .fire();
                    return null;
                });
        awaitIdle();
        String result = fx(() -> allText((Parent) loader.getNamespace().get("submissionBox")));
        assertTrue(result.contains("Saved evaluation · PASSED"), result);
        assertTrue(result.contains("Canonical evidence:"));
        assertFalse(result.contains("public class Main"));
        assertTrue(
                fx(() -> allText((Parent) loader.getNamespace().get("skillsBox")))
                        .contains("DEVELOPING"));
        assertEquals(1, scalar("problem_attempts"));
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertTrue(scalar("evolution_adaptation_decisions") >= 2);
    }

    @Test
    void doubleClickCannotSubmitTwiceAndNavigationWaitsForUnsavedDraft() throws Exception {
        openCounter();
        AtomicBoolean left = new AtomicBoolean();
        fx(
                () -> {
                    ((TextArea) loader.getNamespace().get("sourceArea"))
                            .setText("my local unsaved work");
                    controller.requestLeave(() -> left.set(true));
                    return null;
                });
        awaitIdle();
        assertTrue(left.get());
        assertEquals(
                "my local unsaved work",
                new com.codefit.repository.JavaSolutionDraftRepository()
                        .findByProblemId(
                                new com.codefit.repository.QuestCatalogRepository()
                                        .transaction(
                                                c ->
                                                        new com.codefit.repository
                                                                        .QuestCatalogRepository()
                                                                .problemId(
                                                                        c,
                                                                        new CatalogRef(
                                                                                "baseline-counter",
                                                                                1))))
                        .orElseThrow()
                        .getSourceCode());
        fx(
                () -> {
                    var submit =
                            findButton(
                                    (Parent) loader.getNamespace().get("challengeActions"),
                                    "Submit deliberate evaluation");
                    submit.fire();
                    submit.fire();
                    return null;
                });
        awaitIdle();
        assertEquals(1, scalar("evolution_submissions"));
        assertTrue(
                fx(() -> allText((Parent) loader.getNamespace().get("submissionBox")))
                        .contains("COMPILE_ERROR"));
    }

    @Test
    void committedResultSurvivesRecommendationFailureAndRefreshNeverResubmits() throws Exception {
        var failed = new AtomicBoolean();
        var adaptive =
                new com.codefit.evolution.adaptation.AdaptiveQuestService(
                        CLOCK,
                        new BundledAssets(),
                        () -> true,
                        new com.codefit.service.ProblemLibraryService());
        reload(
                () ->
                        new PlayerSystemService(
                                CLOCK,
                                new com.codefit.service.ChallengePresentationService(
                                        new com.codefit.repository.QuestCatalogRepository(),
                                        new BundledAssets(),
                                        CLOCK),
                                adaptive,
                                new com.codefit.evolution.evaluation.ChallengeEvaluationCoordinator(
                                        CLOCK),
                                () -> {
                                    if (failed.get())
                                        throw new IllegalStateException(
                                                "private hidden evaluator details");
                                    return adaptive.recommend(CLOCK.instant());
                                }));
        openCounter();
        String reference =
                Files.readString(
                        Path.of(
                                "src/main/resources/evolution/packs/java-debugging-concurrency/v1/baseline-counter/reference.java.txt"));
        failed.set(true);
        fx(
                () -> {
                    ((TextArea) loader.getNamespace().get("sourceArea")).setText(reference);
                    findButton(
                                    (Parent) loader.getNamespace().get("challengeActions"),
                                    "Submit deliberate evaluation")
                            .fire();
                    return null;
                });
        awaitIdle();
        assertEquals(1, scalar("problem_attempts"));
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertTrue(
                fx(() -> ((Label) loader.getNamespace().get("statusLabel")).getText())
                        .contains("Result saved — recommendation pending"));
        assertTrue(
                fx(() -> allText((Parent) loader.getNamespace().get("submissionBox")))
                        .contains("Saved evaluation · PASSED"));
        assertFalse(fx(() -> allText(root)).contains("private hidden evaluator details"));
        failed.set(false);
        fx(
                () -> {
                    ((Button) loader.getNamespace().get("retryButton")).fire();
                    return null;
                });
        awaitIdle();
        assertEquals(1, scalar("evolution_submissions"));
        assertEquals(1, scalar("evolution_evidence_observations"));
    }

    @Test
    void acceptanceIsSeparateFromPresentationAndJournalUsesSavedDecisions() throws Exception {
        fx(
                () -> {
                    findButton((Parent) loader.getNamespace().get("overviewBox"), "Accept quest")
                            .fire();
                    return null;
                });
        awaitIdle();
        assertEquals(0, scalar("evolution_challenge_presentations"));
        assertEquals(1, scalar("evolution_adaptation_choices"));
        fx(
                () -> {
                    confirmFamiliarityLater();
                    findButton(
                                    (Parent) loader.getNamespace().get("overviewBox"),
                                    "Start baseline-localize")
                            .fire();
                    return null;
                });
        awaitIdle();
        assertEquals(1, scalar("evolution_challenge_presentations"));
        fx(
                () -> {
                    ((TabPane) loader.getNamespace().get("tabs"))
                            .getSelectionModel()
                            .select((Tab) loader.getNamespace().get("journalTab"));
                    return null;
                });
        awaitIdle();
        assertTrue(
                fx(() -> allText((Parent) loader.getNamespace().get("journalBox")))
                        .contains("Next action decided"));
        assertFalse(
                fx(() -> allText((Parent) loader.getNamespace().get("journalBox")))
                        .contains("public class Main"));
    }

    @Test
    void disposedRouteRejectsStaleWorkerResponse() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fx(
                () -> {
                    controller.dispose();
                    stage.hide();
                    loader = new FXMLLoader(getClass().getResource("/fxml/evolution.fxml"));
                    loader.setControllerFactory(
                            t ->
                                    new EvolutionController(
                                            () -> {
                                                entered.countDown();
                                                try {
                                                    if (!release.await(5, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "test release missing");
                                                } catch (InterruptedException e) {
                                                    throw new RuntimeException(e);
                                                }
                                                return new PlayerSystemService(CLOCK);
                                            }));
                    root = loader.load();
                    controller = loader.getController();
                    return null;
                });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        fx(
                () -> {
                    controller.dispose();
                    return null;
                });
        release.countDown();
        var field = EvolutionController.class.getDeclaredField("activeTask");
        field.setAccessible(true);
        ((javafx.concurrent.Task<?>) field.get(controller)).get(10, TimeUnit.SECONDS);
        fx(() -> null);
        assertTrue(
                fx(
                        () ->
                                ((VBox) loader.getNamespace().get("overviewBox"))
                                        .getChildren()
                                        .isEmpty()));
        // The disposed route's old loading UI is intentionally not updated by the stale response.
    }

    @Test
    void cancellationAtPreparedBoundaryIsHonouredBeforeExecution() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var engine =
                com.codefit.evolution.evaluation.EvaluationTestFixtures.pauseAfterPrepared(
                        CLOCK, entered, release);
        var adaptive =
                new com.codefit.evolution.adaptation.AdaptiveQuestService(
                        CLOCK,
                        new BundledAssets(),
                        () -> true,
                        new com.codefit.service.ProblemLibraryService());
        reload(
                () ->
                        new PlayerSystemService(
                                CLOCK,
                                new com.codefit.service.ChallengePresentationService(
                                        new com.codefit.repository.QuestCatalogRepository(),
                                        new BundledAssets(),
                                        CLOCK),
                                adaptive,
                                engine,
                                null));
        openCounter();
        String reference =
                Files.readString(
                        Path.of(
                                "src/main/resources/evolution/packs/java-debugging-concurrency/v1/baseline-counter/reference.java.txt"));
        fx(
                () -> {
                    ((TextArea) loader.getNamespace().get("sourceArea")).setText(reference);
                    findButton(
                                    (Parent) loader.getNamespace().get("challengeActions"),
                                    "Submit deliberate evaluation")
                            .fire();
                    return null;
                });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            fx(
                    () -> {
                        ((Button) loader.getNamespace().get("cancelButton")).fire();
                        return null;
                    });
        } finally {
            release.countDown();
        }
        awaitIdle();
        assertTrue(
                fx(() -> allText((Parent) loader.getNamespace().get("submissionBox")))
                        .contains("Saved evaluation · CANCELLED"));
        assertEquals(1, scalar("evolution_submissions"));
        assertEquals(1, scalar("evolution_evidence_observations"));
    }

    @Test
    void lostResponseRetryUsesOriginalFrozenArtifactDespiteNewEditorChanges() throws Exception {
        var engine =
                com.codefit.evolution.evaluation.EvaluationTestFixtures.loseFirstResponse(CLOCK);
        var adaptive =
                new com.codefit.evolution.adaptation.AdaptiveQuestService(
                        CLOCK,
                        new BundledAssets(),
                        () -> true,
                        new com.codefit.service.ProblemLibraryService());
        reload(
                () ->
                        new PlayerSystemService(
                                CLOCK,
                                new com.codefit.service.ChallengePresentationService(
                                        new com.codefit.repository.QuestCatalogRepository(),
                                        new BundledAssets(),
                                        CLOCK),
                                adaptive,
                                engine,
                                null));
        openCounter();
        String reference =
                Files.readString(
                        Path.of(
                                "src/main/resources/evolution/packs/java-debugging-concurrency/v1/baseline-counter/reference.java.txt"));
        fx(
                () -> {
                    ((TextArea) loader.getNamespace().get("sourceArea")).setText(reference);
                    findButton(
                                    (Parent) loader.getNamespace().get("challengeActions"),
                                    "Submit deliberate evaluation")
                            .fire();
                    return null;
                });
        awaitIdle();
        assertEquals(1, scalar("evolution_submissions"));
        assertTrue(fx(() -> ((Button) loader.getNamespace().get("retryButton")).isVisible()));
        fx(
                () -> {
                    ((TextArea) loader.getNamespace().get("sourceArea"))
                            .setText("new unsent draft after response loss");
                    findButton(
                                    (Parent) loader.getNamespace().get("challengeActions"),
                                    "Submit deliberate evaluation")
                            .fire();
                    return null;
                });
        awaitIdle();
        assertEquals(1, scalar("evolution_submissions"));
        assertEquals(1, scalar("problem_attempts"));
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertTrue(
                fx(() -> allText((Parent) loader.getNamespace().get("submissionBox")))
                        .contains("Saved evaluation · PASSED"));
        assertEquals(
                "new unsent draft after response loss",
                fx(() -> ((TextArea) loader.getNamespace().get("sourceArea")).getText()));
        String command;
        try (var c = DatabaseConfig.getConnection();
                var q = c.createStatement();
                var r = q.executeQuery("SELECT command_id FROM evolution_submissions")) {
            r.next();
            command = r.getString(1);
        }
        assertEquals(
                reference,
                new com.codefit.evolution.evaluation.DurableEvaluationRepository()
                        .find(command)
                        .orElseThrow()
                        .snapshot()
                        .request()
                        .artifact());
    }

    @Test
    void coldStartSavedPreparedSubmissionHasEnabledRecoveryWithoutOpeningAnEditor()
            throws Exception {
        var request = preparedSubmission("baseline-trace");
        long exposureEvents = scalar("evolution_challenge_exposure_events");
        reload(() -> new PlayerSystemService(CLOCK));
        inspectLatest();
        fx(
                () -> {
                    assertFalse(
                            findButton(root, "Resume frozen submission").isDisabled(),
                            "Recovery must work without an authorized editor after restart");
                    assertFalse(findButton(root, "Cancel frozen submission").isDisabled());
                    assertTrue(((VBox) loader.getNamespace().get("challengeBox")).isDisabled());
                    assertEquals(
                            "", ((TextArea) loader.getNamespace().get("promptArea")).getText());
                    assertEquals(
                            "", ((TextArea) loader.getNamespace().get("sourceArea")).getText());
                    return null;
                });
        assertSavedIdentity(request, "baseline-trace", "PREPARED", "Not evaluated");
        fx(
                () -> {
                    findButton(root, "Resume frozen submission").fire();
                    return null;
                });
        awaitIdle();
        assertSavedIdentity(request, "baseline-trace", "FINALIZED", "PASSED");
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertEquals(
                0,
                scalar("problem_attempts"),
                "Keyed diagnostics must not fabricate Java attempts");
        // A finalized result must also be readable after another cold controller restart.
        reload(() -> new PlayerSystemService(CLOCK));
        inspectLatest();
        assertSavedIdentity(request, "baseline-trace", "FINALIZED", "PASSED");
        assertClosedEditor();
        assertNull(fx(() -> maybeButton(root, "Resume frozen submission")));
        assertEquals(1, scalar("evolution_submissions"));
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertEquals(1, scalar("evolution_challenge_presentations"));
        assertEquals(exposureEvents, scalar("evolution_challenge_exposure_events"));
    }

    @Test
    void historicalSubmissionIsSeparateFromAnotherAuthorizedChallengeAndPreservesDraft()
            throws Exception {
        var request = preparedSubmission("baseline-trace");
        new com.codefit.evolution.evaluation.ChallengeEvaluationCoordinator(CLOCK)
                .resume(request.id());
        reload(() -> new PlayerSystemService(CLOCK));
        openCounter();
        String draft = "// my unsaved counter investigation";
        fx(
                () -> {
                    ((TextArea) loader.getNamespace().get("sourceArea")).setText(draft);
                    return null;
                });
        inspectLatest();
        fx(
                () -> {
                    assertNotEquals(
                            loader.getNamespace().get("challengeTab"),
                            ((TabPane) loader.getNamespace().get("tabs"))
                                    .getSelectionModel()
                                    .getSelectedItem(),
                            "An old result must not appear under a different challenge editor");
                    assertEquals(
                            draft, ((TextArea) loader.getNamespace().get("sourceArea")).getText());
                    return null;
                });
        assertSavedIdentity(request, "baseline-trace", "FINALIZED", "PASSED");
        assertFalse(
                fx(() -> allText((Parent) loader.getNamespace().get("feedbackBox")))
                        .contains("Saved evaluation"));
        assertFalse(
                fx(() -> allText((Parent) loader.getNamespace().get("submissionBox")))
                        .contains(draft));
        assertEquals(
                draft,
                new com.codefit.repository.JavaSolutionDraftRepository()
                        .findByProblemId(
                                new com.codefit.repository.QuestCatalogRepository()
                                        .transaction(
                                                c ->
                                                        new com.codefit.repository
                                                                        .QuestCatalogRepository()
                                                                .problemId(
                                                                        c,
                                                                        new CatalogRef(
                                                                                "baseline-counter",
                                                                                1))))
                        .orElseThrow()
                        .getSourceCode());
        fx(
                () -> {
                    ((TabPane) loader.getNamespace().get("tabs"))
                            .getSelectionModel()
                            .select((Tab) loader.getNamespace().get("challengeTab"));
                    assertEquals(
                            "baseline-counter/v1",
                            ((Label) loader.getNamespace().get("challengeTitle")).getText());
                    assertEquals(
                            draft, ((TextArea) loader.getNamespace().get("sourceArea")).getText());
                    return null;
                });
        assertEquals(2, scalar("evolution_challenge_presentations"));
        assertEquals(1, scalar("evolution_submissions"));
    }

    @Test
    void recoverySavesNewEditsInAnotherChallengeButExecutesOnlyTheOldFrozenArtifact()
            throws Exception {
        var request = preparedSubmission("baseline-trace");
        reload(() -> new PlayerSystemService(CLOCK));
        openCounter();
        inspectLatest();
        String newer = "// unrelated counter draft written after inspecting recovery";
        fx(
                () -> {
                    var tabs = (TabPane) loader.getNamespace().get("tabs");
                    tabs.getSelectionModel()
                            .select((Tab) loader.getNamespace().get("challengeTab"));
                    ((TextArea) loader.getNamespace().get("sourceArea")).setText(newer);
                    tabs.getSelectionModel()
                            .select((Tab) loader.getNamespace().get("submissionTab"));
                    findButton(root, "Resume frozen submission").fire();
                    return null;
                });
        awaitIdle();
        assertSavedIdentity(request, "baseline-trace", "FINALIZED", "PASSED");
        assertEquals(
                request.artifact(),
                new DurableEvaluationRepository()
                        .find(request.id())
                        .orElseThrow()
                        .snapshot()
                        .request()
                        .artifact());
        assertEquals(
                newer,
                new com.codefit.repository.JavaSolutionDraftRepository()
                        .findByProblemId(
                                new com.codefit.repository.QuestCatalogRepository()
                                        .transaction(
                                                c ->
                                                        new com.codefit.repository
                                                                        .QuestCatalogRepository()
                                                                .problemId(
                                                                        c,
                                                                        new CatalogRef(
                                                                                "baseline-counter",
                                                                                1))))
                        .orElseThrow()
                        .getSourceCode());
        assertEquals(
                newer, fx(() -> ((TextArea) loader.getNamespace().get("sourceArea")).getText()));
        assertEquals(1, scalar("evolution_submissions"));
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertEquals(0, scalar("problem_attempts"));
        assertEquals(2, scalar("evolution_challenge_presentations"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "EVALUATED", "CANCELLED", "TOOL_UNAVAILABLE"})
    void journalColdStartRecoversDurableStagesWithoutReopeningContent(String state)
            throws Exception {
        var request = preparedSubmission("baseline-counter");
        long exposureEvents = scalar("evolution_challenge_exposure_events");
        var engine = new ChallengeEvaluationCoordinator(CLOCK);
        switch (state) {
            case "RUNNING" -> {
                // Model a process that died after claiming the command, as the engine's restart
                // tests do.
                assertTrue(ProcessHandle.of(99999999).isEmpty());
                new com.codefit.repository.QuestCatalogRepository()
                        .transaction(
                                c -> {
                                    try (var q =
                                            c.prepareStatement(
                                                    "UPDATE evolution_submissions SET"
                                                        + " state='RUNNING',execution_number=1,run_token='orphan-ui',owner_pid=99999999,owner_started='unknown'"
                                                        + " WHERE command_id=?")) {
                                        q.setString(1, request.id());
                                        assertEquals(1, q.executeUpdate());
                                    }
                                    return null;
                                });
            }
            case "EVALUATED" ->
                    assertThrows(
                            IllegalStateException.class,
                            () ->
                                    EvaluationTestFixtures.interruptAfterStaging(CLOCK)
                                            .resume(request.id()));
            case "CANCELLED" -> {
                engine.cancel(request.id());
                engine.resume(request.id());
            }
            case "TOOL_UNAVAILABLE" -> engine = EvaluationTestFixtures.unavailableRunner(CLOCK);
            default -> throw new AssertionError(state);
        }
        var recoveryEngine = engine;
        reload(
                () ->
                        new PlayerSystemService(
                                CLOCK,
                                new ChallengePresentationService(
                                        new com.codefit.repository.QuestCatalogRepository(),
                                        new BundledAssets(),
                                        CLOCK),
                                new com.codefit.evolution.adaptation.AdaptiveQuestService(
                                        CLOCK,
                                        new BundledAssets(),
                                        () -> true,
                                        new com.codefit.service.ProblemLibraryService()),
                                recoveryEngine,
                                null));
        inspectJournalSubmission(request.id());
        assertClosedEditor();
        if (state.equals("CANCELLED")) {
            assertSavedIdentity(request, "baseline-counter", "FINALIZED", "CANCELLED");
            assertNull(fx(() -> maybeButton(root, "Resume frozen submission")));
        } else {
            assertSavedIdentity(
                    request,
                    "baseline-counter",
                    state.equals("TOOL_UNAVAILABLE") ? "PREPARED" : state,
                    state.equals("EVALUATED") ? "PASSED" : "Not evaluated");
            fx(
                    () -> {
                        assertFalse(findButton(root, "Resume frozen submission").isDisabled());
                        findButton(root, "Resume frozen submission").fire();
                        return null;
                    });
            awaitIdle();
            assertSavedIdentity(
                    request,
                    "baseline-counter",
                    "FINALIZED",
                    state.equals("TOOL_UNAVAILABLE") ? state : "PASSED");
        }
        assertClosedEditor();
        assertEquals(1, scalar("evolution_submissions"));
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertEquals(1, scalar("problem_attempts"));
        assertEquals(1, scalar("evolution_challenge_presentations"));
        assertEquals(exposureEvents, scalar("evolution_challenge_exposure_events"));
        inspectLatest(); // A second supported route returns the same durable result.
        assertEquals(1, scalar("evolution_evidence_observations"));
        assertEquals(1, scalar("problem_attempts"));
    }

    @Test
    void coldStartCancelButtonPersistsCancellationBeforeFrozenResume() throws Exception {
        var request = preparedSubmission("baseline-trace");
        reload(() -> new PlayerSystemService(CLOCK));
        inspectLatest();
        fx(
                () -> {
                    findButton(root, "Cancel frozen submission").fire();
                    return null;
                });
        awaitIdle();
        assertTrue(new DurableEvaluationRepository().find(request.id()).orElseThrow().cancel());
        assertSavedIdentity(request, "baseline-trace", "PREPARED", "Not evaluated");
        fx(
                () -> {
                    findButton(root, "Resume frozen submission").fire();
                    return null;
                });
        awaitIdle();
        assertSavedIdentity(request, "baseline-trace", "FINALIZED", "CANCELLED");
        assertClosedEditor();
        assertEquals(1, scalar("evolution_submissions"));
        assertEquals(1, scalar("evolution_evidence_observations"));
    }

    private void assertSavedIdentity(
            SubmissionRequest request, String challenge, String stageName, String outcome)
            throws Exception {
        fx(
                () -> {
                    assertEquals(
                            loader.getNamespace().get("submissionTab"),
                            ((TabPane) loader.getNamespace().get("tabs"))
                                    .getSelectionModel()
                                    .getSelectedItem());
                    var result = allText((Parent) loader.getNamespace().get("submissionBox"));
                    assertTrue(
                            result.contains("Evaluated challenge: " + challenge + "/v1"), result);
                    assertTrue(result.contains("Frozen submission ID: " + request.id()), result);
                    assertTrue(
                            result.contains("Stage: " + stageName + " · Outcome: " + outcome),
                            result);
                    assertFalse(
                            result.contains("public class Main"),
                            "No trusted harness in saved-result projection");
                    assertFalse(
                            result.contains(request.artifact()),
                            "Saved results must not reveal the frozen source or keyed answer");
                    return null;
                });
    }

    private void assertClosedEditor() throws Exception {
        fx(
                () -> {
                    assertTrue(((VBox) loader.getNamespace().get("challengeBox")).isDisabled());
                    assertEquals(
                            "", ((TextArea) loader.getNamespace().get("promptArea")).getText());
                    assertEquals(
                            "", ((TextArea) loader.getNamespace().get("sourceArea")).getText());
                    return null;
                });
    }

    private void inspectJournalSubmission(String command) throws Exception {
        fx(
                () -> {
                    ((TabPane) loader.getNamespace().get("tabs"))
                            .getSelectionModel()
                            .select((Tab) loader.getNamespace().get("journalTab"));
                    return null;
                });
        awaitIdle();
        fx(
                () -> {
                    var journal = (VBox) loader.getNamespace().get("journalBox");
                    var entry =
                            journal.getChildren().stream()
                                    .filter(
                                            n ->
                                                    n instanceof Parent p
                                                            && allText(p)
                                                                    .contains(
                                                                            "SUBMISSION · source "
                                                                                    + command))
                                    .map(n -> (Parent) n)
                                    .findFirst()
                                    .orElseThrow();
                    findButton(entry, "Inspect / continue").fire();
                    return null;
                });
        awaitIdle();
    }

    private com.codefit.evolution.evaluation.SubmissionRequest preparedSubmission(
            String challenge) {
        var spec =
                JavaTrainingPack.challenges().stream()
                        .filter(c -> c.ref().id().equals(challenge))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(q -> q.challenges().contains(spec.ref()))
                        .findFirst()
                        .orElseThrow();
        String id = UUID.randomUUID().toString();
        new ChallengePresentationService(
                        new com.codefit.repository.QuestCatalogRepository(),
                        new BundledAssets(),
                        CLOCK)
                .present(
                        new ChallengePresentation.Request(
                                "p-" + id,
                                quest.ref(),
                                spec.ref(),
                                spec.cohort(),
                                Familiarity.DECLARED_UNFAMILIAR));
        String source =
                new BundledAssets()
                        .read(
                                spec.assets().stream()
                                        .filter(a -> a.kind() == QuestTypes.AssetKind.REFERENCE)
                                        .findFirst()
                                        .orElseThrow());
        var request =
                new com.codefit.evolution.evaluation.SubmissionRequest(
                        "s-" + id, "p-" + id, source, OutsideAssistance.NONE_DECLARED, "");
        new com.codefit.evolution.evaluation.ChallengeEvaluationCoordinator(CLOCK).prepare(request);
        return request;
    }

    private void inspectLatest() throws Exception {
        fx(
                () -> {
                    ((TabPane) loader.getNamespace().get("tabs"))
                            .getSelectionModel()
                            .select((Tab) loader.getNamespace().get("overviewTab"));
                    findButton(
                                    (Parent) loader.getNamespace().get("overviewBox"),
                                    "Inspect latest result / recovery")
                            .fire();
                    return null;
                });
        awaitIdle();
    }

    private void reload(java.util.function.Supplier<PlayerSystemService> factory) throws Exception {
        fx(
                () -> {
                    controller.dispose();
                    stage.hide();
                    loader = new FXMLLoader(getClass().getResource("/fxml/evolution.fxml"));
                    loader.setControllerFactory(t -> new EvolutionController(factory));
                    root = loader.load();
                    controller = loader.getController();
                    stage = new Stage();
                    stage.setScene(new Scene(root, 1220, 1000));
                    stage.show();
                    return null;
                });
        awaitIdle();
    }

    private void confirmFamiliarityLater() {
        Platform.runLater(
                () -> {
                    for (var window : List.copyOf(Window.getWindows()))
                        if (window instanceof Stage dialog
                                && dialog != stage
                                && dialog.getScene() != null
                                && dialog.getScene().getRoot() instanceof DialogPane pane) {
                            var combo = (ComboBox<Familiarity>) pane.lookup(".combo-box");
                            if (combo != null) combo.setValue(Familiarity.DECLARED_UNFAMILIAR);
                            ((Button) pane.lookupButton(ButtonType.OK)).fire();
                        }
                });
    }

    private void openCounter() throws Exception {
        fx(
                () -> {
                    ((TabPane) loader.getNamespace().get("tabs"))
                            .getSelectionModel()
                            .select((Tab) loader.getNamespace().get("baselineTab"));
                    confirmFamiliarityLater();
                    findButton(diagnosticCard("baseline-counter"), "Start diagnostic").fire();
                    return null;
                });
        awaitIdle();
        assertFalse(
                fx(() -> ((TextArea) loader.getNamespace().get("sourceArea")).getText())
                        .contains("public class Main"));
    }

    private Parent diagnosticCard(String title) {
        var box = (VBox) loader.getNamespace().get("baselineBox");
        return (Parent)
                box.getChildren().stream()
                        .filter(n -> n instanceof Parent p && allText(p).startsWith(title + "\n"))
                        .findFirst()
                        .orElseThrow();
    }

    private static Button findButton(Parent parent, String label) {
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof Button b && label.equals(b.getText())) return b;
            if (n instanceof Parent p) {
                var b = maybeButton(p, label);
                if (b != null) return b;
            }
        }
        throw new AssertionError("Button not found: " + label);
    }

    private static Button maybeButton(Parent p, String label) {
        try {
            return findButton(p, label);
        } catch (AssertionError e) {
            return null;
        }
    }

    private static String allText(Parent parent) {
        var s = new StringBuilder();
        for (Node n : parent.getChildrenUnmodifiable()) {
            if (n instanceof Labeled l) s.append(l.getText()).append('\n');
            if (n instanceof Parent p) s.append(allText(p));
        }
        return s.toString();
    }

    private static int countOccurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }

    private long scalar(String table) throws Exception {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    private void awaitIdle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        int stable = 0;
        while (System.nanoTime() < deadline) {
            boolean idle =
                    fx(
                            () ->
                                    !((ProgressIndicator) loader.getNamespace().get("loading"))
                                            .isVisible());
            if (idle) {
                if (++stable == 3) return;
            } else stable = 0;
            Thread.sleep(40);
        }
        fail(
                "UI did not settle: "
                        + fx(() -> ((Label) loader.getNamespace().get("statusLabel")).getText()));
    }

    private static <T> T fx(Callable<T> work) throws Exception {
        var task = new FutureTask<T>(work);
        Platform.runLater(task);
        return task.get(100, TimeUnit.SECONDS);
    }

    private void snapshot(String name) throws Exception {
        String destination = System.getProperty("codefit.visualDir");
        if (destination == null) return;
        root.applyCss();
        root.layout();
        var image = root.snapshot(new SnapshotParameters(), null);
        var png =
                new java.awt.image.BufferedImage(
                        (int) image.getWidth(),
                        (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var pixels = image.getPixelReader();
        for (int y = 0; y < png.getHeight(); y++)
            for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, pixels.getArgb(x, y));
        var file = Path.of(destination, name);
        Files.createDirectories(file.getParent());
        javax.imageio.ImageIO.write(png, "png", file.toFile());
    }
}

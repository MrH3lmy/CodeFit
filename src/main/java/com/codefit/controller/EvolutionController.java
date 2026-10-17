package com.codefit.controller;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;
import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.player.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.ChallengeExposureRepository;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.service.RunCancellationToken;
import com.codefit.ui.*;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.time.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.*;

/** One shell destination, using existing authoritative services. No JDBC or runner work on FX. */
public final class EvolutionController implements RouteLifecycle {
    @FXML private TabPane tabs;
    @FXML private Tab overviewTab, skillsTab, baselineTab, challengeTab, submissionTab, journalTab;
    @FXML
    private VBox overviewBox,
            skillsBox,
            baselineBox,
            challengeBox,
            feedbackBox,
            submissionBox,
            journalBox;
    @FXML private Label statusLabel, challengeTitle, exposureLabel, draftLabel;
    @FXML private Button refreshButton, retryButton, cancelButton;
    @FXML private ProgressIndicator loading;
    @FXML private TextArea promptArea, sourceArea, reflectionArea;
    @FXML private ComboBox<OutsideAssistance> assistanceBox;
    @FXML private FlowPane challengeActions;
    private final Supplier<PlayerSystemService> factory;
    private PlayerSystemService service;
    private PlayerSystemService.Overview overview;
    private PlayerSystemService.Work work;
    private boolean busy, disposed, filling, dirty, recommendationPending;
    private long generation;
    private Runnable retryAction, leave;
    private String activeCommand;
    private SubmissionRequest pendingSubmission;
    private java.util.concurrent.atomic.AtomicBoolean submissionCancellation;
    private RunCancellationToken practiceToken;
    private Task<?> activeTask;
    private final PauseTransition autosave = new PauseTransition(Duration.millis(900));
    private EvolutionJournal.Cursor journalCursor;

    public EvolutionController() {
        this(PlayerSystemService::new);
    }

    public EvolutionController(Supplier<PlayerSystemService> factory) {
        this.factory = factory;
    }

    @FXML
    private void initialize() {
        assistanceBox.getItems().setAll(OutsideAssistance.values());
        assistanceBox.setValue(OutsideAssistance.UNKNOWN);
        assistanceBox.setConverter(
                new StringConverter<>() {
                    public String toString(OutsideAssistance value) {
                        return value == null
                                ? "Unknown"
                                : switch (value) {
                                    case UNKNOWN -> "Not specified";
                                    case NONE_DECLARED -> "I used no outside help";
                                    case DECLARED -> "I used outside help";
                                };
                    }

                    public OutsideAssistance fromString(String s) {
                        return OutsideAssistance.UNKNOWN;
                    }
                });
        sourceArea.setTextFormatter(
                new TextFormatter<String>(
                        change ->
                                change.getControlNewText()
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)
                                                        .length
                                                <= 200000
                                        ? change
                                        : null));
        reflectionArea.setTextFormatter(
                new TextFormatter<String>(
                        change -> change.getControlNewText().length() <= 16000 ? change : null));
        sourceArea.textProperty().addListener((o, a, b) -> edited());
        reflectionArea.textProperty().addListener((o, a, b) -> edited());
        assistanceBox.valueProperty().addListener((o, a, b) -> edited());
        sourceArea.addEventFilter(
                KeyEvent.KEY_PRESSED,
                e -> {
                    if (e.getCode() == KeyCode.TAB) {
                        e.consume();
                        if (e.isShiftDown()) promptArea.requestFocus();
                        else reflectionArea.requestFocus();
                    }
                });
        reflectionArea.addEventFilter(
                KeyEvent.KEY_PRESSED,
                e -> {
                    if (e.getCode() == KeyCode.TAB) {
                        e.consume();
                        if (e.isShiftDown()) sourceArea.requestFocus();
                        else assistanceBox.requestFocus();
                    }
                });
        autosave.setOnFinished(
                e -> {
                    if (dirty && !busy) save();
                });
        journalTab.setOnSelectionChanged(
                e -> {
                    if (journalTab.isSelected() && journalBox.getChildren().isEmpty() && !busy)
                        loadJournal(true);
                });
        challengeBox.setDisable(true);
        refresh();
    }

    private PlayerSystemService service() {
        if (Platform.isFxApplicationThread())
            throw new IllegalStateException("Evolution service must run on a worker");
        if (service == null) service = factory.get();
        return service;
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    private void edited() {
        if (filling || work == null || disposed) return;
        dirty = true;
        draftLabel.setText("Unsaved edits · saving shortly");
        autosave.playFromStart();
    }

    private PlayerSystemService.Draft draft() {
        return new PlayerSystemService.Draft(
                work.presentationId(),
                sourceArea.getText(),
                reflectionArea.getText(),
                assistanceBox.getValue());
    }

    private <T> void background(String message, Callable<T> operation, Consumer<T> success) {
        if (busy || disposed) return;
        busy = true;
        long request = ++generation;
        retryAction = () -> background(message, operation, success);
        statusLabel.setText(message);
        visible(loading, true);
        visible(retryButton, false);
        tabs.setDisable(true);
        refreshButton.setDisable(true);
        var task =
                new Task<T>() {
                    @Override
                    protected T call() throws Exception {
                        return operation.call();
                    }
                };
        activeTask = task;
        task.setOnSucceeded(
                e -> {
                    if (disposed || request != generation) return;
                    complete();
                    retryAction = null;
                    statusLabel.setText("Local state ready · choose your next step");
                    try {
                        success.accept(task.getValue());
                        finishLeave();
                    } catch (RuntimeException failure) {
                        if (disposed || request != generation) return;
                        statusLabel.setText(
                                PlayerErrorPresentation.message(
                                        failure,
                                        recommendationPending,
                                        message.startsWith("Replaying")));
                        retryAction = () -> background(message, operation, success);
                        visible(retryButton, true);
                        leave = null;
                    }
                });
        task.setOnFailed(
                e -> {
                    if (disposed || request != generation) return;
                    complete();
                    statusLabel.setText(
                            PlayerErrorPresentation.message(
                                    task.getException(),
                                    recommendationPending,
                                    message.startsWith("Replaying")));
                    visible(retryButton, true);
                    // A failed save must keep the current screen; never discard edits during
                    // navigation.
                    leave = null;
                });
        try {
            PlayerTasks.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            complete();
            statusLabel.setText("Local worker queue busy. Retry shortly; your work is retained.");
            visible(retryButton, true);
            leave = null;
        }
    }

    private void complete() {
        busy = false;
        practiceToken = null;
        tabs.setDisable(overview == null);
        refreshButton.setDisable(false);
        visible(loading, false);
        visible(cancelButton, false);
    }

    @FXML
    private void retry() {
        if (retryAction != null) retryAction.run();
    }

    @FXML
    private void refresh() {
        if (dirty) {
            saveThen(this::refresh);
            return;
        }
        var previous = overview;
        background(
                "Reading estimates and choosing a current-time next action…",
                () -> service().overview(),
                next -> {
                    recommendationPending = false;
                    overview = next;
                    tabs.setDisable(false);
                    renderOverview(previous);
                    renderSkills();
                    renderBaseline();
                    if (previous != null && activeCommand != null) {
                        var changes = card("Current skill estimates after refresh");
                        for (var revision : next.estimates()) {
                            var before =
                                    previous.estimates().stream()
                                            .filter(
                                                    r ->
                                                            r.estimate()
                                                                    .skill()
                                                                    .equals(
                                                                            revision.estimate()
                                                                                    .skill()))
                                            .findFirst();
                            changes.getChildren()
                                    .add(
                                            text(
                                                    revision.estimate().skill().skillId()
                                                            + ": "
                                                            + before.map(
                                                                            r ->
                                                                                    r.estimate()
                                                                                            .band()
                                                                                            .toString())
                                                                    .orElse("UNKNOWN")
                                                            + " → "
                                                            + revision.estimate().band()
                                                            + " · revision "
                                                            + revision.id()));
                            revision.estimate()
                                    .reasons()
                                    .forEach(r -> changes.getChildren().add(text(r.message())));
                        }
                        submissionBox.getChildren().add(changes);
                    }
                });
    }

    private void renderOverview(PlayerSystemService.Overview previous) {
        overviewBox.getChildren().clear();
        var welcome =
                card(
                        overview.decision().input().preferences().version() == 0
                                ? "Welcome to your Evolution track"
                                : "Your development direction");
        welcome.getChildren()
                .add(
                        text(
                                "Investigate real bugs, practise repairs and test unfamiliar"
                                    + " situations. Skill bands are revisable estimates of checked"
                                    + " behavior — never XP or permanent labels."));
        welcome.getChildren()
                .add(
                        text(
                                "Local Java checks have a limited supported solution set. Passing"
                                    + " stress tests does not prove universal thread safety. The"
                                    + " runner is not a security sandbox: run only your own local"
                                    + " code."));
        var prefs = overview.decision().input().preferences();
        var goal = new ComboBox<SkillDefinition>();
        goal.getItems().setAll(overview.graph().topologicalOrder());
        goal.setId("evolutionGoal");
        goal.setMaxWidth(Double.MAX_VALUE);
        goal.setPromptText("Foundational track (default)");
        goal.setConverter(
                new StringConverter<>() {
                    public String toString(SkillDefinition s) {
                        return s == null ? "Foundational track" : s.name();
                    }

                    public SkillDefinition fromString(String s) {
                        return null;
                    }
                });
        goal.setValue(
                overview.graph().definitions().stream()
                        .filter(s -> s.ref().equals(prefs.goal()))
                        .findFirst()
                        .orElse(null));
        goal.setAccessibleText("Exact skill goal");
        welcome.getChildren().add(new Label("Goal"));
        welcome.getChildren().add(goal);
        welcome.getChildren()
                .add(
                        actions(
                                button(
                                        "Set goal",
                                        () ->
                                                choice(
                                                        new LearnerCommand(
                                                                id("goal"),
                                                                CommandKind.CHANGE_GOAL,
                                                                null,
                                                                null,
                                                                goal.getValue() == null
                                                                        ? null
                                                                        : goal.getValue().ref(),
                                                                null,
                                                                null,
                                                                null))),
                                button(
                                        "Foundational goal",
                                        () ->
                                                choice(
                                                        LearnerCommand.simple(
                                                                id("goal"),
                                                                CommandKind.CHANGE_GOAL)))));
        var budget = new Spinner<Integer>(1, 180, prefs.budgetMinutes(), 10);
        budget.setId("evolutionBudget");
        budget.setEditable(true);
        budget.setAccessibleText("Active time budget in minutes");
        welcome.getChildren()
                .add(
                        text(
                                "Active-time budget (minutes). The default is 20; Java repair"
                                        + " challenges need 30–50. A smaller budget can leave no"
                                        + " eligible task; it is not a skill judgment."));
        welcome.getChildren()
                .add(
                        actions(
                                budget,
                                button(
                                        "Set budget",
                                        () -> {
                                            try {
                                                int value =
                                                        Integer.parseInt(
                                                                budget.getEditor()
                                                                        .getText()
                                                                        .strip());
                                                if (value < 1 || value > 180)
                                                    throw new IllegalArgumentException();
                                                choice(
                                                        new LearnerCommand(
                                                                id("budget"),
                                                                CommandKind.CHANGE_BUDGET,
                                                                null,
                                                                null,
                                                                null,
                                                                value,
                                                                null,
                                                                null));
                                            } catch (IllegalArgumentException e) {
                                                statusLabel.setText(
                                                        "Choose an active-time budget from 1 to 180"
                                                                + " minutes.");
                                            }
                                        })));
        var track = new ComboBox<Track>();
        track.getItems().setAll(Track.values());
        track.setValue(prefs.track());
        track.setId("evolutionTrack");
        track.setAccessibleText("Training track");
        welcome.getChildren()
                .add(
                        actions(
                                track,
                                button(
                                        "Switch track",
                                        () ->
                                                choice(
                                                        new LearnerCommand(
                                                                id("track"),
                                                                CommandKind.SWITCH_TRACK,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                track.getValue(),
                                                                null))),
                                button(
                                        prefs.paused() ? "Resume Evolution" : "Pause Evolution",
                                        () ->
                                                choice(
                                                        LearnerCommand.simple(
                                                                id("pause"),
                                                                prefs.paused()
                                                                        ? CommandKind.RESUME
                                                                        : CommandKind.PAUSE))),
                                button("Ordinary curriculum", NavigationService::showProblems)));
        welcome.getChildren()
                .add(
                        text(
                                "Selected: "
                                        + prefs.track()
                                        + " · "
                                        + prefs.budgetMinutes()
                                        + " active minutes · "
                                        + (prefs.paused()
                                                ? "Paused — no forced recommendations"
                                                : "Ready when you are")));
        welcome.getChildren()
                .add(
                        actions(
                                button(
                                        "Start / resume baseline",
                                        () -> tabs.getSelectionModel().select(baselineTab)),
                                button("Skip baseline", () -> baselineChoice(null, true)),
                                button(
                                        "Defer baseline / pause",
                                        () ->
                                                choice(
                                                        LearnerCommand.simple(
                                                                id("pause"), CommandKind.PAUSE)))));
        var introduction = card("Your next step, at your pace");
        introduction
                .getChildren()
                .add(
                        text(
                                "Investigate realistic Java bugs. Establish a baseline, practise"
                                    + " unfamiliar repairs, and inspect what the evidence supports."
                                    + " Estimates can change; UNKNOWN is an honest starting"
                                    + " point."));
        introduction
                .getChildren()
                .add(
                        text(
                                "Track: "
                                        + prefs.track()
                                        + " · goal: "
                                        + (prefs.goal() == null
                                                ? "Foundational track"
                                                : definition(prefs.goal()).name())
                                        + " · "
                                        + prefs.budgetMinutes()
                                        + " active minutes · "
                                        + (prefs.paused() ? "Paused" : "Available")));
        introduction
                .getChildren()
                .add(
                        actions(
                                button(
                                        "Establish / review baseline",
                                        () -> tabs.getSelectionModel().select(baselineTab)),
                                button(
                                        "Inspect your skills",
                                        () -> tabs.getSelectionModel().select(skillsTab))));
        overviewBox.getChildren().add(introduction);
        var settings = new TitledPane("Goal, time budget, track and pause controls", welcome);
        settings.setExpanded(false);
        overviewBox.getChildren().add(settings);
        var progression = card("Engagement progression · not proficiency");
        var rewards = new CheckBox("Earn new Evolution XP and milestone rewards");
        rewards.setSelected(overview.progression().rewardsEnabled());
        rewards.setAccessibleText("Enable or opt out of new Evolution XP and milestone rewards");
        rewards.setOnAction(
                event -> {
                    boolean enabled = rewards.isSelected();
                    background(
                            "Saving local reward preference…",
                            () -> {
                                service().setRewardsEnabled(enabled);
                                return null;
                            },
                            ignored -> refresh());
                });
        progression.getChildren()
                .add(
                        text(
                                overview.progression().xp()
                                        + " XP · level "
                                        + overview.progression().level()
                                        + " · "
                                        + overview.progression().engagementRank()
                                        + ". Levels and rank reflect engagement only; they never"
                                        + " unlock harder work or change skill estimates."));
        if (overview.progression().pendingEffortRewards() > 0) {
            int pending = overview.progression().pendingEffortRewards();
            progression.getChildren()
                    .add(
                            text(
                                    pending == 128
                                            ? "Reward recovery is scanning additional finalized"
                                                    + " evaluations; some may not be reward-eligible."
                                                    + " Your results are saved; retry when ready."
                                            : "Reward processing pending for "
                                                    + pending
                                                    + " finalized evaluation(s). Your results are"
                                                    + " saved; retry when ready."));
            progression.getChildren()
                    .add(actions(button("Retry pending rewards", this::refresh)));
        }
        if (overview.progression().quarantinedEffortSources() > 0) {
            progression
                    .getChildren()
                    .add(
                            text(
                                    overview.progression().quarantinedEffortSources()
                                            + " finalized reward source(s) failed integrity checks."
                                            + " They were not rewarded; local database repair is"
                                            + " required before retrying."));
        }
        progression.getChildren().add(rewards);
        progression.getChildren().add(text("Latest 25 rewards · open the journal for the full paged audit history."));
        progression
                .getChildren()
                .add(
                        actions(
                                button(
                                        "Open full reward journal",
                                        () -> {
                                            tabs.getSelectionModel().select(journalTab);
                                            if (!busy) loadJournal(true);
                                        })));
        progression
                .getChildren()
                .add(
                        text(
                                "Cosmetic milestone unlocks: "
                                        + (overview.progression().unlockedMilestones().isEmpty()
                                                ? "none"
                                                : String.join(
                                                        ", ",
                                                        overview.progression()
                                                                .unlockedMilestones()))));
        if (overview.progression().history().isEmpty()) {
            progression.getChildren().add(text("No Evolution reward entitlements yet."));
        } else {
            for (var entitlement : overview.progression().history()) {
                progression
                        .getChildren()
                        .add(
                                text(
                                        entitlement.status()
                                                + " · "
                                                + entitlement.type()
                                                + " · "
                                                + entitlement.subject()
                                                + " · +"
                                                + entitlement.xpAwarded()
                                                + " XP · policy v"
                                                + entitlement.policyVersion()
                                                + " · "
                                                + entitlement.source()
                                                + " · "
                                                + entitlement.reason()
                                                + " · "
                                                + (entitlement.proofAvailable()
                                                        ? "proof/source available"
                                                        : "proof unavailable or superseded")
                                                + " · "
                                                + entitlement.earnedAt()));
            }
        }
        overviewBox.getChildren().add(progression);
        var recommendation = card("Your next quest · " + overview.decision().result().branch());
        overview.decision()
                .result()
                .reasons()
                .forEach(
                        r ->
                                recommendation
                                        .getChildren()
                                        .add(text(r.message() + " [" + r.code() + "]")));
        overviewBox.getChildren().add(recommendation);
        var primary = overview.decision().result().primary();
        if (primary != null)
            recommendation.getChildren().add(selectionCard(primary, CommandKind.ACCEPT));
        else
            recommendation
                    .getChildren()
                    .add(
                            text(
                                    "No automatic challenge is ready. Review the reasons, change"
                                        + " budget or direction, resume a deferred task, explore"
                                        + " permitted practice, or rest."));
        for (var alternative : overview.decision().result().alternatives())
            recommendation
                    .getChildren()
                    .add(
                            selectionCard(
                                    alternative,
                                    alternative.exploratory()
                                            ? CommandKind.EXPLORE
                                            : CommandKind.CHOOSE_ALTERNATIVE));
        var limitations = new VBox(10);
        for (var ranking : overview.decision().result().candidates()) {
            var candidate =
                    overview.decision().input().candidates().stream()
                            .filter(c -> c.key().equals(ranking.candidateKey()))
                            .findFirst()
                            .orElse(null);
            if (candidate == null) continue;
            var box = new VBox(6);
            box.getChildren()
                    .add(
                            text(
                                    candidate.challenge().tag()
                                            + " · "
                                            + candidate.cohort()
                                            + " · "
                                            + candidate.effortMinutes()
                                            + " min · "
                                            + ranking.exclusions()
                                            + (ranking.dueAt() == null
                                                    ? ""
                                                    : " · due " + ranking.dueAt())));
            if (ranking.exploratoryEligible())
                box.getChildren()
                        .add(
                                button(
                                        "Explore practice: " + candidate.challenge().id(),
                                        () ->
                                                selectCandidate(
                                                        candidate.challenge(),
                                                        CommandKind.EXPLORE)));
            if (prefs.declined().contains(candidate.challenge().id())
                    || prefs.deferred().containsKey(candidate.challenge().id()))
                box.getChildren()
                        .add(
                                button(
                                        "Re-enable " + candidate.challenge().id(),
                                        () ->
                                                choice(
                                                        new LearnerCommand(
                                                                id("resume-task"),
                                                                CommandKind.RESUME_CANDIDATE,
                                                                null,
                                                                candidate.challenge(),
                                                                null,
                                                                null,
                                                                null,
                                                                null))));
            limitations.getChildren().add(box);
        }
        var more =
                new TitledPane("Other opportunities, reservations and availability", limitations);
        more.setExpanded(false);
        overviewBox.getChildren().add(more);
        var skills = card("Your skills · evidence, not activity");
        for (var r : overview.estimates()) {
            var definition = definition(r.estimate().skill());
            skills.getChildren()
                    .add(
                            button(
                                    definition.name()
                                            + " · "
                                            + r.estimate().band()
                                            + " · "
                                            + r.estimate().independentFamilies()
                                            + " independent families",
                                    () -> showSkill(r)));
        }
        overviewBox.getChildren().add(skills);
        var changes = card("Latest estimate comparisons");
        for (var revision : overview.estimates()) {
            var before = overview.prior().get(revision.estimate().skill());
            changes.getChildren()
                    .add(
                            text(
                                    definition(revision.estimate().skill()).name()
                                            + ": "
                                            + (before == null
                                                    ? "No previous estimate"
                                                    : before.estimate().band()
                                                            + " (revision "
                                                            + before.id()
                                                            + ")")
                                            + " → "
                                            + revision.estimate().band()
                                            + " (revision "
                                            + revision.id()
                                            + ")"));
        }
        changes.getChildren()
                .add(
                        text(
                                "An unchanged band can still include useful practice. Inspect"
                                    + " Skills for the actual evidence, assistance and exclusions;"
                                    + " history is retained in the journal."));
        overviewBox.getChildren().add(changes);
        if (overview.latest() != null) {
            var latest = overview.latest();
            var box = card("Most recent evaluation");
            box.getChildren()
                    .add(
                            text(
                                    latest.stage() == Stage.FINALIZED
                                            ? "Saved: "
                                                    + latest.outcome()
                                                    + " · withdrawn: "
                                                    + latest.withdrawn()
                                            : "Pending: "
                                                    + latest.stage()
                                                    + " — no completed result yet"));
            box.getChildren()
                    .add(
                            button(
                                    "Inspect latest result / recovery",
                                    () -> openSubmission(latest.command())));
            overviewBox.getChildren().add(box);
        }
        var recent = card("Continue / recover local work");
        if (overview.recent().isEmpty())
            recent.getChildren()
                    .add(
                            text(
                                    "Nothing submitted yet. Choose a diagnostic to begin; skipping"
                                            + " leaves unsupported skills UNKNOWN."));
        overview.recent()
                .forEach(
                        entry ->
                                recent.getChildren()
                                        .add(button(entry.message(), () -> openEntry(entry))));
        recent.getChildren()
                .add(
                        button(
                                "Open learning journal",
                                () -> {
                                    tabs.getSelectionModel().select(journalTab);
                                    if (!busy) loadJournal(true);
                                }));
        overviewBox.getChildren().add(recent);
    }

    private Node selectionCard(NextQuestDecision.Selection selection, CommandKind kind) {
        if (selection.curriculumProblem() != null)
            return button(
                    "Accept ordinary curriculum recommendation",
                    () -> {
                        var command =
                                new LearnerCommand(
                                        id("accept-curriculum"),
                                        CommandKind.ACCEPT,
                                        overview.decision().id(),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null);
                        background(
                                "Saving curriculum choice…",
                                () -> service().adaptive().respond(command),
                                saved ->
                                        NavigationService.showSolvingWorkspace(
                                                selection.curriculumProblem()));
                    });
        var candidate =
                overview.decision().input().candidates().stream()
                        .filter(
                                c ->
                                        c.challenge().equals(selection.challenge())
                                                && c.quest().equals(selection.quest()))
                        .findFirst()
                        .orElseThrow();
        var quest =
                overview.quests().stream()
                        .filter(q -> q.ref().equals(selection.quest()))
                        .findFirst()
                        .orElseThrow();
        var box = card(quest.title() + (selection.exploratory() ? " · Exploratory practice" : ""));
        box.getChildren().add(text(quest.objective()));
        box.getChildren()
                .add(
                        text(
                                candidate.challenge().tag()
                                        + " · "
                                        + candidate.cohort()
                                        + " · difficulty "
                                        + candidate.difficulty()
                                        + " · about "
                                        + candidate.effortMinutes()
                                        + " active minutes"));
        candidate
                .targets()
                .forEach(
                        t ->
                                box.getChildren()
                                        .add(
                                                text(
                                                        definition(t.skill())
                                                                .observableBehaviors()
                                                                .get(t.behavior()))));
        box.getChildren()
                .add(
                        text(
                                "Prerequisites: "
                                        + candidate.prerequisites()
                                        + ". Checks support only their stated scope; acceptance"
                                        + " does not expose content."));
        box.getChildren()
                .add(
                        actions(
                                button(
                                        selection.exploratory()
                                                ? "Choose exploratory practice"
                                                : "Accept quest",
                                        () -> selectCandidate(candidate.challenge(), kind)),
                                button(
                                        "Decline",
                                        () ->
                                                selectCandidate(
                                                        candidate.challenge(),
                                                        CommandKind.DECLINE)),
                                button("Defer…", () -> defer(candidate.challenge()))));
        return box;
    }

    private void selectCandidate(CatalogRef challenge, CommandKind kind) {
        var command =
                new LearnerCommand(
                        id(kind.name().toLowerCase(Locale.ROOT)),
                        kind,
                        overview.decision().id(),
                        challenge,
                        null,
                        null,
                        null,
                        null);
        if (kind == CommandKind.DECLINE) {
            choice(command);
            return;
        }
        background(
                "Saving your selection (no content revealed)…",
                () -> service().adaptive().respond(command),
                saved -> {
                    var accepted = card("Quest accepted · content not yet opened");
                    accepted.getChildren()
                            .add(
                                    text(
                                            "Declare prior familiarity before opening. This"
                                                    + " declaration and family exposure remain in"
                                                    + " history."));
                    accepted.getChildren()
                            .add(
                                    button(
                                            "Start " + challenge.id(),
                                            () -> startChoice(command.id())));
                    overviewBox.getChildren().addFirst(accepted);
                });
    }

    private void defer(CatalogRef ref) {
        var dialog = new ChoiceDialog<Integer>(1, List.of(1, 3, 7, 14));
        dialog.setTitle("Defer quest");
        dialog.setHeaderText("Choose days from now (24-hour periods)");
        dialog.showAndWait()
                .ifPresent(
                        days -> {
                            String commandId = id("defer");
                            String decisionId = overview.decision().id();
                            var frozen =
                                    new java.util.concurrent.atomic.AtomicReference<
                                            LearnerCommand>();
                            background(
                                    "Saving your explicit UTC deferral…",
                                    () -> {
                                        var command = frozen.get();
                                        if (command == null) {
                                            command =
                                                    new LearnerCommand(
                                                            commandId,
                                                            CommandKind.DEFER,
                                                            decisionId,
                                                            ref,
                                                            null,
                                                            null,
                                                            null,
                                                            service()
                                                                    .now()
                                                                    .plus(
                                                                            java.time.Duration
                                                                                    .ofDays(days)));
                                            frozen.set(command);
                                        }
                                        return service().adaptive().respond(command);
                                    },
                                    saved -> refresh());
                        });
    }

    private void choice(LearnerCommand command) {
        background(
                "Saving learner choice…",
                () -> service().adaptive().respond(command),
                saved -> refresh());
    }

    private Optional<Familiarity> familiarity() {
        var dialog =
                new ChoiceDialog<Familiarity>(
                        Familiarity.UNKNOWN,
                        List.of(
                                Familiarity.UNKNOWN,
                                Familiarity.DECLARED_UNFAMILIAR,
                                Familiarity.DECLARED_FAMILIAR));
        dialog.setTitle("Before opening the challenge");
        dialog.setHeaderText("Have you seen this problem or its solution before?");
        dialog.setContentText(
                "Unknown is allowed; it cannot establish novelty. Your choice is retained.");
        return dialog.showAndWait();
    }

    private void startChoice(String command) {
        familiarity().ifPresent(f -> openSafely(() -> service().start(command, f)));
    }

    private void openSafely(Callable<PlayerSystemService.Work> operation) {
        if (dirty) {
            saveThen(() -> openSafely(operation));
            return;
        }
        background(
                "Authorizing presentation and saving exposure before display…",
                operation,
                this::showWork);
    }

    private void showWork(PlayerSystemService.Work next) {
        work = next;
        pendingSubmission = null;
        filling = true;
        challengeTitle.setText(next.challenge().tag());
        exposureLabel.setText(next.exposure());
        promptArea.setText(next.prompt());
        sourceArea.setText(next.source());
        reflectionArea.setText(next.reflection());
        assistanceBox.setValue(next.assistance());
        filling = false;
        dirty = false;
        draftLabel.setText(
                "Local draft loaded · explicit submissions freeze separate immutable artifacts");
        feedbackBox.getChildren().clear();
        challengeActions.getChildren().clear();
        challengeBox.setDisable(false);
        challengeActions
                .getChildren()
                .addAll(
                        button("Save draft", this::save),
                        button("Submit deliberate evaluation", this::submit));
        if (next.practiceAllowed())
            challengeActions
                    .getChildren()
                    .add(button("Run public practice checks", this::practice));
        for (int level : next.hints())
            challengeActions
                    .getChildren()
                    .add(
                            button(
                                    "Hint " + level,
                                    () -> {
                                        String command = id("hint");
                                        background(
                                                "Recording hint exposure…",
                                                () ->
                                                        service()
                                                                .hint(
                                                                        next.presentationId(),
                                                                        command,
                                                                        level),
                                                text -> showReveal("Hint " + level, text));
                                    }));
        challengeActions
                .getChildren()
                .add(
                        button(
                                "Reveal reference solution…",
                                () -> {
                                    var confirm =
                                            new Alert(
                                                    Alert.AlertType.CONFIRMATION,
                                                    "This will record solution exposure for the"
                                                        + " entire family. The result can support"
                                                        + " practice, but not independent"
                                                        + " unfamiliar success.",
                                                    ButtonType.OK,
                                                    ButtonType.CANCEL);
                                    confirm.setHeaderText("Reveal the reference solution?");
                                    if (confirm.showAndWait().orElse(ButtonType.CANCEL)
                                            == ButtonType.OK) {
                                        String command = id("solution");
                                        background(
                                                "Recording solution exposure…",
                                                () ->
                                                        service()
                                                                .solution(
                                                                        next.presentationId(),
                                                                        command),
                                                text -> showReveal("Reference solution", text));
                                    }
                                }));
        challengeActions
                .getChildren()
                .add(
                        button(
                                "I recognize this problem",
                                () -> {
                                    String command = id("familiar");
                                    background(
                                            "Recording known familiarity…",
                                            () -> {
                                                service().familiar(next.presentationId(), command);
                                                return true;
                                            },
                                            done ->
                                                    exposureLabel.setText(
                                                            next.exposure()
                                                                    + " · later familiarity"
                                                                    + " declared"));
                                }));
        challengeActions
                .getChildren()
                .add(
                        button(
                                "Return to overview",
                                () -> {
                                    if (dirty)
                                        saveThen(
                                                () -> tabs.getSelectionModel().select(overviewTab));
                                    else tabs.getSelectionModel().select(overviewTab);
                                }));
        var checks = card("Public checks and limitations");
        next.publicChecks().forEach(s -> checks.getChildren().add(text(s)));
        feedbackBox.getChildren().add(checks);
        tabs.getSelectionModel().select(challengeTab);
        sourceArea.requestFocus();
    }

    private void showReveal(String title, String value) {
        var box = card(title);
        var area = new TextArea(value);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(8);
        area.setAccessibleText(title + "; exposure has been recorded");
        box.getChildren().add(area);
        feedbackBox.getChildren().add(box);
        exposureLabel.setText(
                "Assistance / familiarity updated durably. Independent unfamiliar proof may no"
                        + " longer be eligible. Reopen to inspect current family context.");
    }

    @FXML
    private void save() {
        saveThen(() -> {});
    }

    private void saveThen(Runnable after) {
        if (work == null) {
            after.run();
            return;
        }
        var captured = draft();
        background(
                "Saving local editor…",
                () -> {
                    service().save(captured);
                    return captured;
                },
                saved -> {
                    dirty = !draft().equals(saved);
                    draftLabel.setText(
                            dirty ? "Newer edits waiting to save" : "Draft saved locally");
                    if (!dirty) after.run();
                    else autosave.playFromStart();
                });
    }

    private void submit() {
        if (work == null || busy) return;
        var frozen = draft();
        var request =
                pendingSubmission == null
                        ? new SubmissionRequest(
                                id("submission"),
                                work.presentationId(),
                                frozen.source(),
                                frozen.assistance(),
                                frozen.reflection())
                        : pendingSubmission;
        pendingSubmission = request;
        if (!request.presentationId().equals(work.presentationId())) {
            statusLabel.setText(
                    "A previous frozen submission is pending; inspect it through recovery before"
                            + " submitting this challenge.");
            return;
        }
        submissionCancellation = new java.util.concurrent.atomic.AtomicBoolean();
        var cancellation = submissionCancellation;
        activeCommand = request.id();
        background(
                "Freezing your deliberate submission, then evaluating bounded checks…",
                () -> {
                    service().save(frozen);
                    return service().submit(request, cancellation::get);
                },
                view -> {
                    dirty = false;
                    draftLabel.setText(
                            "Draft saved; this evaluation refers to frozen command "
                                    + request.id());
                    renderResult(view);
                });
        visible(cancelButton, true);
    }

    private void practice() {
        if (work == null || busy) return;
        var frozen = draft();
        String practiceCommand = id("practice");
        practiceToken = new RunCancellationToken();
        var token = practiceToken;
        background(
                "Running public practice checks · no evidence is generated…",
                () -> {
                    service().save(frozen);
                    return service()
                            .practice(
                                    frozen.presentationId(),
                                    practiceCommand,
                                    frozen.source(),
                                    token);
                },
                checks -> {
                    dirty = false;
                    practiceToken = null;
                    var box = card("Practice feedback · no evidence recorded");
                    checks.forEach(
                            c ->
                                    box.getChildren()
                                            .add(
                                                    text(
                                                            c.id()
                                                                    + " · "
                                                                    + c.status()
                                                                    + "\n"
                                                                    + c.feedback())));
                    feedbackBox.getChildren().add(box);
                });
        visible(cancelButton, true);
    }

    @FXML
    private void cancel() {
        if (practiceToken != null) {
            practiceToken.cancel();
            statusLabel.setText("Practice cancellation requested; waiting for cleanup…");
            return;
        }
        if (submissionCancellation != null) submissionCancellation.set(true);
        if (activeCommand == null) return;
        String command = activeCommand;
        try {
            PlayerTasks.execute(
                    () -> {
                        try {
                            service().evaluation().cancel(command);
                        } catch (RuntimeException ignored) {
                        }
                    });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
        statusLabel.setText(
                "Cancellation requested. The durable outcome will be shown when execution returns;"
                        + " use recovery if interrupted.");
    }

    private void renderResult(ChallengeEvaluationCoordinator.View view) {
        // Read frozen identity off FX for every stage, including PREPARED after a cold start.
        // Never resume a presentation or load a mutable draft just to inspect/recover a command.
        if (view.stage() == Stage.FINALIZED) recommendationPending = true;
        background(
                "Reading saved submission identity…",
                () -> service().submissionMetadata(view.command()),
                metadata -> renderSavedResult(view, metadata));
    }

    private void renderSavedResult(
            ChallengeEvaluationCoordinator.View view,
            DurableEvaluationRepository.SubmissionExposure metadata) {
        activeCommand = view.command();
        if (view.stage() == Stage.FINALIZED
                && pendingSubmission != null
                && pendingSubmission.id().equals(view.command())) pendingSubmission = null;
        var box =
                card(
                        view.stage() == Stage.FINALIZED
                                ? "Saved evaluation · " + view.outcome()
                                : "Submission " + view.stage() + " · not a completed result");
        box.getChildren()
                .add(
                        text(
                                "Evaluated challenge: "
                                        + metadata.challenge().tag()
                                        + "\nFrozen submission ID: "
                                        + view.command()
                                        + "\nStage: "
                                        + view.stage()
                                        + " · Outcome: "
                                        + (view.outcome() == null
                                                ? "Not evaluated"
                                                : view.outcome())
                                        + "\nPresentation: "
                                        + metadata.presentationId()
                                        + " · Submitted: "
                                        + metadata.submittedAt()));
        box.getChildren()
                .add(
                        text(
                                "This result grades a frozen submission. The editor is a separate"
                                    + " mutable draft; later edits do not change this result."));
        box.getChildren()
                .add(text("Command: " + view.command() + " · withdrawn: " + view.withdrawn()));
        view.checks()
                .forEach(
                        c ->
                                box.getChildren()
                                        .add(
                                                text(
                                                        c.id()
                                                                + " · "
                                                                + c.status()
                                                                + "\n"
                                                                + c.feedback())));
        box.getChildren()
                .add(
                        text(
                                "A pass describes these checks, not mastery or universal thread"
                                        + " safety. Unsupported, unavailable, timeout and cancelled"
                                        + " outcomes do not establish a failed capability."));
        // One saved command at a time: no stale recovery buttons or association with the editor.
        submissionBox.getChildren().setAll(box);
        tabs.getSelectionModel().select(submissionTab);
        if (view.stage() != Stage.FINALIZED) {
            box.getChildren()
                    .add(
                            text(
                                    "Resume reuses this frozen command and checks execution"
                                        + " ownership. Cancel records a durable request; resume"
                                        + " completes cancellation once execution stops. Neither"
                                        + " action opens challenge content or changes the editor"
                                        + " draft."));
            box.getChildren()
                    .add(
                            button(
                                    "Resume frozen submission",
                                    () -> recoverSubmission(view.command(), false)));
            box.getChildren()
                    .add(
                            button(
                                    "Cancel frozen submission",
                                    () -> recoverSubmission(view.command(), true)));
            return;
        }
        recommendationPending = true;
        background(
                "Result saved — reading evidence context…",
                () -> service().resultContext(view.command()),
                input -> {
                    if (input.isPresent()) {
                        var ctx = input.get();
                        box.getChildren()
                                .add(
                                        text(
                                                "Evaluated challenge: "
                                                        + ctx.challenge().tag()
                                                        + " · "
                                                        + ctx.cohort()
                                                        + " · family "
                                                        + ctx.presentation().familyId()
                                                        + "\nFrozen artifact SHA-256: "
                                                        + ctx.observation()
                                                                .evaluation()
                                                                .artifactSha256()
                                                        + "\n"
                                                        + "Later editor changes are not part of"
                                                        + " this evaluation."));
                        box.getChildren()
                                .add(
                                        text(
                                                "Canonical evidence: "
                                                        + com.codefit.evolution.evidence.EvidenceIds
                                                                .id(ctx.observation().source())
                                                        + " · "
                                                        + ctx.observation().availability()
                                                        + " · "
                                                        + ctx.observation().provenance()
                                                        + " · "
                                                        + ctx.observation().assistance()
                                                        + " · "
                                                        + ctx.observation().exposure()));
                        for (var check : ctx.checks())
                            box.getChildren()
                                    .add(
                                            text(
                                                    check.id()
                                                            + " · "
                                                            + (check.required()
                                                                    ? "required"
                                                                    : "optional")
                                                            + " · targets "
                                                            + targetSummary(check.targets())
                                                            + " · "
                                                            + check.status()
                                                            + "\nLimit: "
                                                            + check.limitations()));
                    } else
                        box.getChildren()
                                .add(
                                        text(
                                                "Canonical evidence context is unavailable. No"
                                                    + " independent capability claim is made."));
                    refresh();
                });
    }

    private void recoverSubmission(String command, boolean cancellation) {
        // The learner may have edited another authorized challenge since last inspecting this tab.
        if (dirty) {
            saveThen(() -> recoverSubmission(command, cancellation));
            return;
        }
        activeCommand = command;
        background(
                cancellation
                        ? "Recording cancellation…"
                        : "Resuming the existing immutable command…",
                () ->
                        cancellation
                                ? service().evaluation().cancel(command)
                                : service().evaluation().resume(command),
                this::renderResult);
        if (!cancellation) visible(cancelButton, true);
    }

    private void renderSkills() {
        skillsBox.getChildren().clear();
        skillsBox
                .getChildren()
                .add(
                        text(
                                "UNKNOWN means insufficient direct evidence. DEVELOPING includes"
                                        + " useful progress; it is not failure. Select a skill to"
                                        + " inspect the exact evidence and missing behaviors."));
        for (var r : overview.estimates()) skillsBox.getChildren().add(skillCard(r));
    }

    private SkillDefinition definition(SkillRef ref) {
        return overview.graph().definitions().stream()
                .filter(s -> s.ref().equals(ref))
                .findFirst()
                .orElseThrow();
    }

    private VBox skillCard(EstimateRevisionRepository.Revision revision) {
        var e = revision.estimate();
        var d = definition(e.skill());
        var box = card(d.name());
        box.getChildren().add(text(d.description()));
        var band = text(e.band() + " · " + e.support());
        band.getStyleClass().add("evolution-band");
        box.getChildren().add(band);
        box.getChildren()
                .add(
                        text(
                                e.freshness()
                                        + " · "
                                        + e.independentFamilies()
                                        + " independent families · contradiction: "
                                        + e.contradicted()
                                        + " · current supported capability: "
                                        + e.currentCapabilitySupported()));
        box.getChildren()
                .add(
                        text(
                                "Last meaningful observation: "
                                        + Objects.toString(e.lastMeaningfulObservation(), "None")
                                        + " · as of "
                                        + e.asOf()));
        for (int i = 0; i < d.observableBehaviors().size(); i++)
            box.getChildren()
                    .add(
                            text(
                                    (e.supportedBehaviors().contains(i)
                                                    ? "Supported within checked scope: "
                                                    : "More independent evidence needed: ")
                                            + d.observableBehaviors().get(i)));
        e.reasons().forEach(r -> box.getChildren().add(text(r.message() + " [" + r.code() + "]")));
        box.getChildren().add(button("Inspect evidence and history", () -> showSkill(revision)));
        return box;
    }

    private void showSkill(EstimateRevisionRepository.Revision revision) {
        skillsBox
                .getChildren()
                .setAll(button("All skills", this::renderSkills), skillCardWithoutLink(revision));
        var e = revision.estimate();
        if (overview.estimates().stream().noneMatch(current -> current.id() == revision.id()))
            skillsBox
                    .getChildren()
                    .add(
                            1,
                            text(
                                    "Historical interpretation as of "
                                            + e.asOf()
                                            + ". Earlier evidence may since have been corrected or"
                                            + " become stale. Select All skills for the current"
                                            + " estimate."));
        var details = card("Evidence and interpretation · revision " + revision.id());
        details.getChildren()
                .add(
                        text(
                                "Exact skill "
                                        + e.skill()
                                        + " · graph "
                                        + e.graph()
                                        + " · epoch "
                                        + e.epoch().id()
                                        + " · policy "
                                        + e.policyVersion()));
        details.getChildren()
                .add(
                        text(
                                "Prerequisites: "
                                        + overview.graph().prerequisitesOf(e.skill()).stream()
                                                .map(SkillDefinition::name)
                                                .toList()));
        details.getChildren()
                .add(
                        text(
                                "Observation counts by provenance: "
                                        + e.provenanceCounts()
                                        + "\nAssistance: "
                                        + e.assistanceCounts()
                                        + "\nExposure: "
                                        + e.exposureCounts()));
        for (var c : e.contributions()) {
            details.getChildren()
                    .add(
                            text(
                                    "Evidence "
                                            + c.evidenceId()
                                            + " · "
                                            + c.mapping()
                                            + " · family "
                                            + c.family()
                                            + " · independent proof: "
                                            + c.independent()
                                            + " · passed behaviors "
                                            + c.behaviors()
                                            + " · failed "
                                            + c.failedBehaviors()));
            if (c.submissionId() != null)
                details.getChildren()
                        .add(
                                button(
                                        "Inspect evaluation " + c.submissionId(),
                                        () -> openSubmission(c.submissionId())));
        }
        for (var x : e.exclusions())
            details.getChildren()
                    .add(
                            text(
                                    "Excluded "
                                            + x.evidenceId()
                                            + " · "
                                            + x.code()
                                            + ": "
                                            + x.message()));
        var need =
                overview.baseline().nextActions().stream()
                        .filter(n -> n.skill().equals(e.skill()))
                        .findFirst();
        need.ifPresent(
                n ->
                        details.getChildren()
                                .add(
                                        text(
                                                "Current baseline evidence need (separate from this"
                                                    + " saved revision): "
                                                        + n.need())));
        skillsBox.getChildren().add(details);
        var history = card("Saved estimate revisions (historical interpretation)");
        skillsBox.getChildren().add(history);
        history.getChildren()
                .add(
                        pageButton(
                                "Load history",
                                history,
                                () -> loadEstimateHistory(e.skill(), 0, history)));
        tabs.getSelectionModel().select(skillsTab);
    }

    private VBox skillCardWithoutLink(EstimateRevisionRepository.Revision revision) {
        var box = skillCard(revision);
        box.getChildren().removeLast();
        return box;
    }

    private void loadEstimateHistory(SkillRef skill, long after, VBox box) {
        background(
                "Reading immutable estimate history…",
                () ->
                        new EstimateRevisionRepository()
                                .history(overview.graph().ref(), skill, after, 10),
                page -> {
                    if (page.isEmpty()) box.getChildren().add(text("No further saved revisions."));
                    page.forEach(
                            r ->
                                    box.getChildren()
                                            .add(
                                                    button(
                                                            "Revision "
                                                                    + r.id()
                                                                    + " · "
                                                                    + r.estimate().asOf()
                                                                    + " · "
                                                                    + r.estimate().band(),
                                                            () -> showSkill(r))));
                    if (page.size() == 10)
                        box.getChildren()
                                .add(
                                        pageButton(
                                                "More revisions",
                                                box,
                                                () ->
                                                        loadEstimateHistory(
                                                                skill, page.getLast().id(), box)));
                });
    }

    private void renderBaseline() {
        baselineBox.getChildren().clear();
        var status = overview.baseline();
        var intro = card("Establish a provisional baseline");
        intro.getChildren()
                .add(
                        text(
                                "Three reserved diagnostics measure reproduction, fault"
                                    + " localization and an atomic repair. Invariants and liveness"
                                    + " remain UNKNOWN until direct evidence exists. Skip or pause"
                                    + " without a negative score."));
        intro.getChildren()
                .add(
                        text(
                                status.skipped()
                                        ? "Baseline skipped / paused — existing results are"
                                                + " preserved"
                                        : "Baseline available · results and exposure survive"
                                                + " restart"));
        intro.getChildren()
                .add(
                        actions(
                                button(
                                        status.skipped()
                                                ? "Resume baseline"
                                                : "Skip entire baseline",
                                        () -> baselineChoice(null, !status.skipped())),
                                button(
                                        "Defer / pause Evolution",
                                        () ->
                                                choice(
                                                        LearnerCommand.simple(
                                                                id("pause"), CommandKind.PAUSE)))));
        baselineBox.getChildren().add(intro);
        for (var task : status.diagnostics()) {
            var box = card(task.challenge().id());
            box.getChildren().add(text(task.measures()));
            box.getChildren()
                    .add(
                            text(
                                    "Targets: "
                                            + targetSummary(task.targets())
                                            + " · "
                                            + (task.skipped() ? "Skipped" : "Available")
                                            + " · saved submissions: "
                                            + task.submissions().size()));
            if (task.presentationId() != null)
                box.getChildren()
                        .add(
                                button(
                                        "Resume exposed diagnostic",
                                        () ->
                                                openSafely(
                                                        () ->
                                                                service()
                                                                        .openBaseline(
                                                                                task.challenge(),
                                                                                Familiarity.UNKNOWN,
                                                                                true))));
            else if (!task.skipped() && !status.skipped())
                box.getChildren()
                        .add(
                                button(
                                        "Start diagnostic",
                                        () ->
                                                familiarity()
                                                        .ifPresent(
                                                                f ->
                                                                        openSafely(
                                                                                () ->
                                                                                        service()
                                                                                                .openBaseline(
                                                                                                        task
                                                                                                                .challenge(),
                                                                                                        f,
                                                                                                        false)))));
            box.getChildren()
                    .add(
                            button(
                                    task.skipped() ? "Resume task" : "Skip task",
                                    () -> baselineChoice(task.challenge(), !task.skipped())));
            task.submissions()
                    .forEach(
                            command ->
                                    box.getChildren()
                                            .add(
                                                    button(
                                                            "Inspect submission " + command,
                                                            () -> openSubmission(command))));
            baselineBox.getChildren().add(box);
        }
    }

    private void baselineChoice(CatalogRef task, boolean skip) {
        String command = id("baseline-choice");
        background(
                "Saving diagnostic choice (no capability score)…",
                () -> {
                    if (task == null) {
                        if (skip) service().baseline().skipAll(command);
                        else service().baseline().resumeAll(command);
                    } else if (skip) service().baseline().skipTask(task, command);
                    else service().baseline().resumeTask(task, command);
                    return true;
                },
                done -> refresh());
    }

    private void loadJournal(boolean reset) {
        if (reset) {
            journalCursor = null;
            journalBox.getChildren().clear();
        }
        if (!reset && journalCursor == null) return;
        var cursor = journalCursor;
        background(
                "Reading a bounded journal page from authoritative records…",
                () -> new EvolutionJournal().page(cursor, 24),
                page -> {
                    if (page.entries().isEmpty())
                        journalBox.getChildren().add(text("No further local events."));
                    page.entries()
                            .forEach(
                                    entry -> {
                                        var box = card(entry.message());
                                        box.getChildren()
                                                .add(
                                                        text(
                                                                entry.at()
                                                                        + " · "
                                                                        + entry.kind()
                                                                        + " · source "
                                                                        + entry.ref()));
                                        if (!Set.of("BASELINE").contains(entry.kind()))
                                            box.getChildren()
                                                    .add(
                                                            button(
                                                                    "Inspect / continue",
                                                                    () -> openEntry(entry)));
                                        journalBox.getChildren().add(box);
                                    });
                    journalCursor = page.next();
                    if (page.next() != null)
                        journalBox
                                .getChildren()
                                .add(
                                        pageButton(
                                                "Older events",
                                                journalBox,
                                                () -> loadJournal(false)));
                });
    }

    private void openEntry(EvolutionJournal.Entry entry) {
        switch (entry.kind()) {
            case "PRESENTATION", "EXPOSURE" ->
                    openSafely(() -> service().resume(entry.ref().split("/", 2)[0]));
            case "SUBMISSION" -> openSubmission(entry.ref());
            case "EVIDENCE" ->
                    background(
                            "Reading evidence metadata…",
                            () -> service().evidence(entry.ref()),
                            stored -> {
                                var o = stored.observation();
                                var box = card("Evidence · " + stored.id());
                                box.getChildren()
                                        .add(
                                                text(
                                                        "Source: "
                                                                + o.source()
                                                                + " · "
                                                                + o.availability()
                                                                + " · "
                                                                + o.provenance()
                                                                + " · "
                                                                + o.outcome()
                                                                + " · assistance "
                                                                + o.assistance()
                                                                + " · exposure "
                                                                + o.exposure()
                                                                + " · recorded "
                                                                + stored.recordedAt()));
                                if (o.evaluation() != null)
                                    box.getChildren()
                                            .add(
                                                    button(
                                                            "Inspect authoritative evaluation",
                                                            () ->
                                                                    openSubmission(
                                                                            o.evaluation()
                                                                                    .submissionId())));
                                journalBox.getChildren().addFirst(box);
                                tabs.getSelectionModel().select(journalTab);
                            });
            case "ESTIMATE" ->
                    background(
                            "Replaying saved estimate revision…",
                            () ->
                                    new EstimateRevisionRepository()
                                            .find(Long.parseLong(entry.ref()))
                                            .orElseThrow(),
                            this::showSkill);
            case "DECISION" ->
                    background(
                            "Replaying the stored immutable decision (not a historical live"
                                    + " recommendation)…",
                            () ->
                                    new AdaptationDecisionRepository()
                                            .find(entry.ref())
                                            .orElseThrow(),
                            decision -> {
                                var box = card("Historical decision · " + decision.input().asOf());
                                decision.result()
                                        .reasons()
                                        .forEach(r -> box.getChildren().add(text(r.message())));
                                box.getChildren()
                                        .add(
                                                text(
                                                        "Primary: "
                                                                + decision.result().primary()
                                                                + " · policy "
                                                                + decision.input().policy()
                                                                + ". Refresh Overview for a current"
                                                                + " recommendation."));
                                journalBox.getChildren().addFirst(box);
                                tabs.getSelectionModel().select(journalTab);
                            });
            case "CHOICE" -> inspectChoice(entry.ref());
            default ->
                    statusLabel.setText(
                            "This entry is a source reference; its interpretation is in skill"
                                    + " details.");
        }
    }

    private record ChoiceResume(
            AdaptationDecisionRepository.Choice choice,
            PlayerSystemService.Work work,
            Long problem) {}

    private void inspectChoice(String command) {
        if (dirty) {
            saveThen(() -> inspectChoice(command));
            return;
        }
        background(
                "Inspecting saved learner choice…",
                () -> {
                    var choice = new AdaptationDecisionRepository().choice(command).orElseThrow();
                    if (!Set.of(
                                    CommandKind.ACCEPT,
                                    CommandKind.CHOOSE_ALTERNATIVE,
                                    CommandKind.EXPLORE)
                            .contains(choice.command().kind()))
                        return new ChoiceResume(choice, null, null);
                    var decision =
                            new AdaptationDecisionRepository()
                                    .find(choice.command().decisionId())
                                    .orElseThrow();
                    if (choice.command().challenge() == null)
                        return new ChoiceResume(choice, null, decision.input().curriculumProblem());
                    var candidate =
                            decision.input().candidates().stream()
                                    .filter(c -> c.challenge().equals(choice.command().challenge()))
                                    .findFirst()
                                    .orElseThrow();
                    String presentation =
                            candidate.cohort() == Cohort.BASELINE
                                    ? BaselineDiagnosticService.presentationId(
                                            candidate.challenge())
                                    : "adaptive-" + CatalogCodec.digest(command).substring(0, 48);
                    boolean exists =
                            new QuestCatalogRepository()
                                    .transaction(
                                            c ->
                                                    new ChallengeExposureRepository()
                                                            .find(c, presentation)
                                                            .isPresent());
                    return new ChoiceResume(
                            choice, exists ? service().resume(presentation) : null, null);
                },
                found -> {
                    if (found.work() != null) {
                        showWork(found.work());
                        return;
                    }
                    if (found.problem() != null) {
                        NavigationService.showSolvingWorkspace(found.problem());
                        return;
                    }
                    var box = card("Saved choice · " + found.choice().command().kind());
                    box.getChildren()
                            .add(
                                    text(
                                            found.choice().command()
                                                    + "\nChosen "
                                                    + found.choice().chosenAt()));
                    if (Set.of(
                                    CommandKind.ACCEPT,
                                    CommandKind.CHOOSE_ALTERNATIVE,
                                    CommandKind.EXPLORE)
                            .contains(found.choice().command().kind()))
                        box.getChildren()
                                .add(button("Start accepted quest", () -> startChoice(command)));
                    overviewBox.getChildren().addFirst(box);
                    tabs.getSelectionModel().select(overviewTab);
                });
    }

    private void openSubmission(String command) {
        if (dirty) {
            saveThen(() -> openSubmission(command));
            return;
        }
        background(
                "Reading durable, redacted evaluation…",
                () -> service().evaluation().status(command),
                this::renderResult);
    }

    @Override
    public void requestLeave(Runnable ready) {
        if (disposed) {
            ready.run();
            return;
        }
        leave = ready;
        if (busy) {
            cancel();
            statusLabel.setText(
                    "Finishing the active operation and preserving your draft before leaving…");
            return;
        }
        finishLeave();
    }

    private void finishLeave() {
        if (leave == null || busy || disposed) return;
        if (dirty) {
            save();
            return;
        }
        var ready = leave;
        leave = null;
        ready.run();
    }

    @Override
    public void dispose() {
        disposed = true;
        ++generation;
        autosave.stop();
        if (practiceToken != null) practiceToken.cancel();
    }

    private String targetSummary(List<BehaviorTarget> targets) {
        return targets.stream()
                .map(t -> definition(t.skill()).name() + " (behavior " + t.behavior() + ")")
                .distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static Button pageButton(String label, VBox container, Runnable action) {
        var result = button(label, () -> {});
        result.setOnAction(
                event -> {
                    container.getChildren().remove(result);
                    action.run();
                });
        return result;
    }

    private static Label text(String value) {
        var label = new Label(value);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static VBox card(String title) {
        var box = new VBox(10);
        box.getStyleClass().add("evolution-card");
        var label = text(title);
        label.getStyleClass().add("evolution-heading");
        box.getChildren().add(label);
        return box;
    }

    private static Button button(String label, Runnable action) {
        var button = new Button(label);
        button.setWrapText(true);
        button.setAccessibleText(label);
        button.setOnAction(e -> action.run());
        return button;
    }

    private static FlowPane actions(Node... nodes) {
        var flow = new FlowPane(8, 8);
        flow.getChildren().addAll(nodes);
        return flow;
    }

    private static void visible(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }
}

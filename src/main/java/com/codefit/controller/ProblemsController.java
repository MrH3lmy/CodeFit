package com.codefit.controller;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.RoadmapStage;
import com.codefit.service.GuidedPracticeService;
import com.codefit.service.ProblemLibraryEntry;
import com.codefit.service.ProblemLibraryFilter;
import com.codefit.service.ProblemLibraryService;
import com.codefit.service.ProblemSolvingSessionService;
import com.codefit.service.TodayPlan;
import com.codefit.ui.NavigationService;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * The Problem Library (#144): Blind Order (the default learning mode, one row per roadmap
 * membership in stage/sequence order) and Topics (one row per problem, filterable) are two views
 * over the exact same {@link ProblemLibraryService} data, so switching between them never
 * duplicates or diverges from the learner's actual progress.
 */
public class ProblemsController extends BaseController {

    private enum ViewMode { BLIND_ORDER, TOPICS }

    @FXML private Label messageLabel;
    @FXML private Button blindOrderToggleButton;
    @FXML private Button topicsToggleButton;
    @FXML private VBox nextRecommendedCard;
    @FXML private Label todayStageSetLabel;
    @FXML private Label todayMandatoryProgressLabel;
    @FXML private Label todayTargetLabel;
    @FXML private Label todayBottleneckLabel;
    @FXML private Label nextRecommendedLabel;
    @FXML private Button nextRecommendedResumeButton;
    @FXML private Button revisitQueueButton;
    @FXML private TextField searchField;
    @FXML private MenuButton stageMenuButton;
    @FXML private MenuButton topicMenuButton;
    @FXML private MenuButton levelMenuButton;
    @FXML private MenuButton qualityMenuButton;
    @FXML private MenuButton platformMenuButton;
    @FXML private MenuButton stateMenuButton;
    @FXML private Label emptyStateLabel;
    @FXML private ListView<ProblemLibraryEntry> problemsList;

    private final ProblemLibraryService problemLibraryService = new ProblemLibraryService();
    private final ProblemSolvingSessionService solvingSessionService = new ProblemSolvingSessionService();
    private final GuidedPracticeService guidedPracticeService = new GuidedPracticeService();

    /** Debounces the search field (#166) so typing doesn't trigger one full refresh per keystroke;
     *  {@link #refresh()} only runs once input has been idle for this long. */
    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(300));

    private ViewMode viewMode = ViewMode.BLIND_ORDER;
    private ProblemLibraryFilter filter = ProblemLibraryFilter.empty();
    private Long pendingResumeProblemId;
    private Long pendingRevisitProblemId;

    /** Bumped on every {@link #refresh()} call; a background load applies its result only if this is
     *  still the current generation when it finishes, so a superseded search/filter request never
     *  overwrites a newer one (#166). */
    private long refreshGeneration = 0;

    @FXML
    public void initialize() {
        problemsList.setCellFactory(list -> new ProblemRowCell());
        configureStaticFilterMenus();
        configureDynamicFilterMenus();
        configureStageFilterMenu();
        defaultStageFilterToCurrentStage();
        searchDebounce.setOnFinished(event -> refresh());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            filter = filter.withSearchText(newValue);
            searchDebounce.playFromStart();
        });
        showBlindOrderView();
    }

    /** "Blind Order defaults to the learner's current stage, not an unbounded 926-row wall" (#166) —
     *  a one-time default applied before the first refresh; the learner can switch to any other stage
     *  or explicitly choose All Stages afterward via {@link #stageMenuButton}. */
    private void defaultStageFilterToCurrentStage() {
        RoadmapStage currentStage = guidedPracticeService.buildTodayPlan().currentStage();
        if (currentStage != null) {
            filter = filter.withStage(currentStage);
            stageMenuButton.setText("Stage: " + currentStage);
        }
    }

    @FXML
    public void showBlindOrderView() {
        viewMode = ViewMode.BLIND_ORDER;
        blindOrderToggleButton.getStyleClass().setAll("primary-button");
        topicsToggleButton.getStyleClass().setAll("ghost-button");
        refresh();
    }

    @FXML
    public void showTopicsView() {
        viewMode = ViewMode.TOPICS;
        topicsToggleButton.getStyleClass().setAll("primary-button");
        blindOrderToggleButton.getStyleClass().setAll("ghost-button");
        refresh();
    }

    @FXML
    public void clearFilters() {
        filter = ProblemLibraryFilter.empty();
        searchField.clear();
        stageMenuButton.setText("Stage: All Stages");
        topicMenuButton.setText("Topic: All");
        levelMenuButton.setText("Level: All");
        qualityMenuButton.setText("Quality: All");
        platformMenuButton.setText("Platform: All");
        stateMenuButton.setText("Status: All");
        refresh();
    }

    @FXML
    public void goProblemDashboard() {
        NavigationService.showProblemDashboard();
    }

    @FXML
    public void resumeNextRecommended() {
        if (pendingResumeProblemId == null) {
            return;
        }
        startOrResumeSession(pendingResumeProblemId);
    }

    /** Starts the first revisit-queue problem directly — an explicit override of the guided
     *  recommendation (#161), the same way starting any other row directly is. */
    @FXML
    public void practiceRevisitQueue() {
        if (pendingRevisitProblemId == null) {
            return;
        }
        startOrResumeSession(pendingRevisitProblemId);
    }

    private void configureStaticFilterMenus() {
        levelMenuButton.getItems().setAll(menuItem("Level: All", () -> setLevelFilter(null)));
        for (DifficultyLevel level : DifficultyLevel.values()) {
            levelMenuButton.getItems().add(menuItem("Level: " + capitalize(level.name()), () -> setLevelFilter(level)));
        }

        qualityMenuButton.getItems().setAll(menuItem("Quality: All", () -> setQualityFilter(null)));
        for (int minQuality = 5; minQuality >= 1; minQuality--) {
            int value = minQuality;
            qualityMenuButton.getItems().add(menuItem("Quality: " + value + "+", () -> setQualityFilter(value)));
        }

        stateMenuButton.getItems().setAll(menuItem("Status: All", () -> setStateFilter(null)));
        for (ProblemState state : ProblemState.values()) {
            stateMenuButton.getItems().add(menuItem("Status: " + displayState(state), () -> setStateFilter(state)));
        }
    }

    private void configureStageFilterMenu() {
        stageMenuButton.getItems().setAll(menuItem("Stage: All Stages", () -> setStageFilter(null)));
        for (RoadmapStage stage : RoadmapStage.values()) {
            stageMenuButton.getItems().add(menuItem("Stage: " + stage, () -> setStageFilter(stage)));
        }
    }

    private void configureDynamicFilterMenus() {
        topicMenuButton.getItems().setAll(menuItem("Topic: All", () -> setTopicFilter(null)));
        for (String topic : problemLibraryService.getDistinctTopics()) {
            topicMenuButton.getItems().add(menuItem("Topic: " + topic, () -> setTopicFilter(topic)));
        }

        platformMenuButton.getItems().setAll(menuItem("Platform: All", () -> setPlatformFilter(null)));
        for (String platform : problemLibraryService.getDistinctPlatforms()) {
            platformMenuButton.getItems().add(menuItem("Platform: " + platform, () -> setPlatformFilter(platform)));
        }
    }

    private MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private void setStageFilter(RoadmapStage stage) {
        filter = filter.withStage(stage);
        stageMenuButton.setText(stage == null ? "Stage: All Stages" : "Stage: " + stage);
        refresh();
    }

    private void setTopicFilter(String topic) {
        filter = filter.withTopic(topic);
        topicMenuButton.setText(topic == null ? "Topic: All" : "Topic: " + topic);
        refresh();
    }

    private void setLevelFilter(DifficultyLevel level) {
        filter = filter.withSuggestedLevel(level);
        levelMenuButton.setText(level == null ? "Level: All" : "Level: " + capitalize(level.name()));
        refresh();
    }

    private void setQualityFilter(Integer minQuality) {
        filter = filter.withMinQualityRating(minQuality);
        qualityMenuButton.setText(minQuality == null ? "Quality: All" : "Quality: " + minQuality + "+");
        refresh();
    }

    private void setPlatformFilter(String platform) {
        filter = filter.withPlatform(platform);
        platformMenuButton.setText(platform == null ? "Platform: All" : "Platform: " + platform);
        refresh();
    }

    private void setStateFilter(ProblemState state) {
        filter = filter.withState(state);
        stateMenuButton.setText(state == null ? "Status: All" : "Status: " + displayState(state));
        refresh();
    }

    /**
     * Reloads the list and Today panel (#166). Every database read happens on a background thread —
     * bulk queries now (see {@link ProblemLibraryService}), not the thousand-plus-round-trip N+1 walk
     * this used to be — and the result is only applied on the JavaFX application thread if this is
     * still the most recent refresh requested; a superseded search/filter change is dropped rather
     * than racing the newer one to update the screen.
     */
    private void refresh() {
        long generation = ++refreshGeneration;
        Thread thread = new Thread(() -> {
            RefreshData data = loadRefreshData();
            Platform.runLater(() -> {
                if (generation == refreshGeneration) {
                    applyRefreshData(data);
                }
            });
        }, "problems-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    /** Runs entirely off the JavaFX application thread; must not touch any UI control. */
    private RefreshData loadRefreshData() {
        if (!problemLibraryService.hasAnyProblems()) {
            return RefreshData.empty();
        }
        List<ProblemLibraryEntry> baseEntries = viewMode == ViewMode.BLIND_ORDER
                ? problemLibraryService.getBlindOrderEntries()
                : problemLibraryService.getTopicBasedEntries();
        List<ProblemLibraryEntry> filtered = problemLibraryService.applyFilter(baseEntries, filter);
        TodayPlan plan = viewMode == ViewMode.BLIND_ORDER ? guidedPracticeService.buildTodayPlan() : null;
        return new RefreshData(true, filtered, plan);
    }

    private record RefreshData(boolean hasAnyProblems, List<ProblemLibraryEntry> filtered, TodayPlan plan) {
        static RefreshData empty() {
            return new RefreshData(false, List.of(), null);
        }
    }

    private void applyRefreshData(RefreshData data) {
        if (!data.hasAnyProblems()) {
            setStatus(messageLabel, "No problems yet. Import a Training Sheet from Settings → Problem-Solving Training to build your roadmap.");
            setVisible(nextRecommendedCard, false);
            setVisible(emptyStateLabel, false);
            problemsList.getItems().clear();
            return;
        }
        setStatus(messageLabel, "");

        updateNextRecommendedCard(data.plan());

        List<ProblemLibraryEntry> filtered = data.filtered();
        if (filtered.isEmpty()) {
            setStatus(emptyStateLabel, "No problems match your filters.");
            problemsList.getItems().clear();
            return;
        }
        setVisible(emptyStateLabel, false);
        problemsList.getItems().setAll(filtered);
    }

    /**
     * The guided curriculum practice loop's "Today" panel (#161): current stage/set, mandatory
     * progress, the learner's daily target vs. how many problems were solved today, the most recent
     * solving bottleneck, and the mandatory-gated next recommendation "Start Today's Practice" opens
     * directly in the Solving Workspace. Only shown in the Blind Order view, since it describes
     * progress through the roadmap sequence the Topics view deliberately doesn't follow.
     *
     * @param plan the Today snapshot already built by {@link #loadRefreshData()} off the JavaFX
     *             thread, or {@code null} outside the Blind Order view
     */
    private void updateNextRecommendedCard(TodayPlan plan) {
        if (viewMode != ViewMode.BLIND_ORDER || plan == null) {
            setVisible(nextRecommendedCard, false);
            pendingResumeProblemId = null;
            pendingRevisitProblemId = null;
            return;
        }

        todayStageSetLabel.setText(plan.currentStage() == null ? "Roadmap complete — every problem is solved."
                : "Stage " + plan.currentStage() + (plan.currentSet() == null ? "" : ", Set " + plan.currentSet()));
        todayMandatoryProgressLabel.setText("Mandatory progress: " + plan.mandatoryCompleted() + " / " + plan.mandatoryTotal()
                + " (" + Math.round(plan.mandatoryCompletionPercent()) + "%)");
        todayTargetLabel.setText("Today: " + plan.solvedToday() + " / " + plan.dailyTargetProblems() + " problems"
                + (plan.dailyTargetMet() ? " — target met!" : ""));
        todayBottleneckLabel.setText(plan.recentBottleneck() == null ? "Not enough timing data yet to spot a bottleneck."
                : "Recent bottleneck: " + capitalize(plan.recentBottleneck().name()));

        plan.nextRecommended().ifPresentOrElse(entry -> {
            Problem problem = entry.problem();
            pendingResumeProblemId = problem.getId();
            nextRecommendedLabel.setText("[" + problem.getExternalCode() + "] " + problem.getTitle() + " — " + plan.nextRecommendedReason());
            setVisible(nextRecommendedCard, true);
        }, () -> {
            pendingResumeProblemId = null;
            nextRecommendedLabel.setText(plan.nextRecommendedReason());
            setVisible(nextRecommendedCard, true);
        });

        if (plan.hasRevisitWork()) {
            pendingRevisitProblemId = plan.revisitQueue().get(0).problem().getId();
            revisitQueueButton.setText("Practice Revisit Queue (" + plan.revisitQueue().size() + ")");
            setVisible(revisitQueueButton, true);
        } else {
            pendingRevisitProblemId = null;
            setVisible(revisitQueueButton, false);
        }
    }

    private VBox createProblemRow(ProblemLibraryEntry entry) {
        Problem problem = entry.problem();
        RoadmapEntry roadmapEntry = entry.roadmapEntry();

        Label titleLabel = new Label("[" + problem.getExternalCode() + "] " + problem.getTitle());
        titleLabel.getStyleClass().add("problem-row-title");
        titleLabel.setWrapText(true);

        StringBuilder subtitle = new StringBuilder(problem.getPlatform());
        if (roadmapEntry != null) {
            subtitle.append(" • Stage ").append(roadmapEntry.getStage());
            if (roadmapEntry.getSetNumber() != null) {
                subtitle.append(" Set ").append(roadmapEntry.getSetNumber());
            }
        }
        if (!"General".equalsIgnoreCase(problem.getTopic())) {
            subtitle.append(" • ").append(problem.getTopic());
        }
        Label subtitleLabel = new Label(subtitle.toString());
        subtitleLabel.getStyleClass().add("problem-row-subtitle");
        subtitleLabel.setWrapText(true);

        HBox badgeRow = new HBox(6);
        badgeRow.setAlignment(Pos.CENTER_LEFT);
        if (roadmapEntry != null) {
            badgeRow.getChildren().add(pill(roadmapEntry.isMandatory() ? "Mandatory" : "Optional",
                    roadmapEntry.isMandatory() ? "pill-mandatory" : "pill-optional"));
        }
        badgeRow.getChildren().add(pill(displayState(entry.progress().getState()), statePillClass(entry.progress().getState())));
        if (entry.progress().getPerceivedDifficultyRating() != null) {
            badgeRow.getChildren().add(pill("Felt: " + entry.progress().getPerceivedDifficultyRating() + "/10", "pill"));
        }

        Button openButton = new Button("Open ↗");
        openButton.getStyleClass().add("ghost-button");
        openButton.setOnAction(event -> openExternally(problem.getUrl()));

        Button resumeButton = new Button("Start / Resume");
        resumeButton.getStyleClass().add("action-button");
        resumeButton.setOnAction(event -> startOrResumeSession(problem.getId()));

        HBox actionsRow = new HBox(8, openButton, resumeButton);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        VBox textColumn = new VBox(4, titleLabel, subtitleLabel, badgeRow);
        textColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textColumn, Priority.ALWAYS);

        HBox row = new HBox(12, textColumn, actionsRow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("deck-row");

        VBox wrapper = new VBox(row);
        wrapper.getStyleClass().add("panel");
        wrapper.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private Label pill(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("pill", styleClass);
        return label;
    }

    private void startOrResumeSession(long problemId) {
        solvingSessionService.startOrResume(problemId);
        NavigationService.showSolvingWorkspace(problemId);
    }

    private void openExternally(String url) {
        if (url == null || url.isBlank()) {
            setStatus(messageLabel, "This problem has no link yet.");
            return;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                setStatus(messageLabel, "Refusing to open a non-http(s) link.");
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            } else {
                setStatus(messageLabel, "Unable to open a browser on this system.");
            }
        } catch (Exception exception) {
            setStatus(messageLabel, "Unable to open link: " + exception.getMessage());
        }
    }

    private String statePillClass(ProblemState state) {
        return switch (state) {
            case SOLVED -> "pill-state-solved";
            case IN_PROGRESS -> "pill-state-in-progress";
            case NEEDS_REVISIT -> "pill-state-needs-revisit";
            case NOT_STARTED -> "pill-state-not-started";
        };
    }

    private String displayState(ProblemState state) {
        return capitalize(state.name().replace('_', ' '));
    }

    private String capitalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder result = new StringBuilder(lower.length());
        boolean capitalizeNext = true;
        for (char c : lower.toCharArray()) {
            if (capitalizeNext && Character.isLetter(c)) {
                result.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                result.append(c);
                if (c == ' ') {
                    capitalizeNext = true;
                }
            }
        }
        return result.toString();
    }

    private void setVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /**
     * Recycles a bounded number of row nodes regardless of curriculum size (#166): {@link ListView}'s
     * virtual flow only ever instantiates enough cells to cover the visible viewport plus a small
     * buffer, calling {@link #updateItem} to rebind each one as it scrolls — unlike the previous plain
     * {@code VBox}, which eagerly created a full node tree for every one of the ~926 roadmap
     * memberships up front.
     */
    private final class ProblemRowCell extends ListCell<ProblemLibraryEntry> {
        @Override
        protected void updateItem(ProblemLibraryEntry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            setGraphic(createProblemRow(entry));
        }
    }
}

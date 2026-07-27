package com.codefit.controller;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.Problem;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapEntry;
import com.codefit.service.GuidedPracticeService;
import com.codefit.service.ProblemLibraryEntry;
import com.codefit.service.ProblemLibraryFilter;
import com.codefit.service.ProblemLibraryService;
import com.codefit.service.ProblemSolvingSessionService;
import com.codefit.service.TodayPlan;
import com.codefit.ui.NavigationService;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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
    @FXML private MenuButton topicMenuButton;
    @FXML private MenuButton levelMenuButton;
    @FXML private MenuButton qualityMenuButton;
    @FXML private MenuButton platformMenuButton;
    @FXML private MenuButton stateMenuButton;
    @FXML private Label emptyStateLabel;
    @FXML private VBox problemsList;

    private final ProblemLibraryService problemLibraryService = new ProblemLibraryService();
    private final ProblemSolvingSessionService solvingSessionService = new ProblemSolvingSessionService();
    private final GuidedPracticeService guidedPracticeService = new GuidedPracticeService();

    private ViewMode viewMode = ViewMode.BLIND_ORDER;
    private ProblemLibraryFilter filter = ProblemLibraryFilter.empty();
    private Long pendingResumeProblemId;
    private Long pendingRevisitProblemId;

    @FXML
    public void initialize() {
        configureStaticFilterMenus();
        configureDynamicFilterMenus();
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            filter = filter.withSearchText(newValue);
            refresh();
        });
        showBlindOrderView();
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

    private void refresh() {
        boolean libraryHasAnyProblems = !problemLibraryService.getTopicBasedEntries().isEmpty();
        if (!libraryHasAnyProblems) {
            setStatus(messageLabel, "No problems yet. Import a Training Sheet from Settings → Problem-Solving Training to build your roadmap.");
            setVisible(nextRecommendedCard, false);
            setVisible(emptyStateLabel, false);
            problemsList.getChildren().clear();
            return;
        }
        setStatus(messageLabel, "");

        List<ProblemLibraryEntry> baseEntries = viewMode == ViewMode.BLIND_ORDER
                ? problemLibraryService.getBlindOrderEntries()
                : problemLibraryService.getTopicBasedEntries();
        List<ProblemLibraryEntry> filtered = problemLibraryService.applyFilter(baseEntries, filter);

        updateNextRecommendedCard();

        problemsList.getChildren().clear();
        if (filtered.isEmpty()) {
            setStatus(emptyStateLabel, "No problems match your filters.");
            return;
        }
        setVisible(emptyStateLabel, false);
        filtered.forEach(entry -> problemsList.getChildren().add(createProblemRow(entry)));
    }

    /**
     * The guided curriculum practice loop's "Today" panel (#161): current stage/set, mandatory
     * progress, the learner's daily target vs. how many problems were solved today, the most recent
     * solving bottleneck, and the mandatory-gated next recommendation "Start Today's Practice" opens
     * directly in the Solving Workspace. Only shown in the Blind Order view, since it describes
     * progress through the roadmap sequence the Topics view deliberately doesn't follow.
     */
    private void updateNextRecommendedCard() {
        if (viewMode != ViewMode.BLIND_ORDER) {
            setVisible(nextRecommendedCard, false);
            pendingResumeProblemId = null;
            pendingRevisitProblemId = null;
            return;
        }
        TodayPlan plan = guidedPracticeService.buildTodayPlan();

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
}

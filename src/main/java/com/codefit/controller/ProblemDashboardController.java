package com.codefit.controller;

import com.codefit.model.Problem;
import com.codefit.model.RoadmapStage;
import com.codefit.service.ProblemDashboard;
import com.codefit.service.ProblemDashboardFilter;
import com.codefit.service.ProblemDashboardService;
import com.codefit.service.ProblemSolvingSessionService;
import com.codefit.ui.NavigationService;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The problem-solving progress dashboard (#147): every figure is read fresh from
 * {@link ProblemDashboardService#build} on every {@link #refresh()} call — nothing on this screen is
 * cached UI-side state, so it is always in sync with the last saved attempt or reflection.
 */
public class ProblemDashboardController extends BaseController {

    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MMM d");

    @FXML private MenuButton stageFilterMenuButton;
    @FXML private MenuButton dateRangeFilterMenuButton;

    @FXML private Label recommendationLabel;
    @FXML private Label recommendationReasonLabel;
    @FXML private Button recommendationResumeButton;

    @FXML private Label currentStageLabel;
    @FXML private Label mandatoryProgressLabel;
    @FXML private Label optionalProgressLabel;
    @FXML private Label solvedThisWeekLabel;
    @FXML private Label acCountLabel;
    @FXML private Label acxCountLabel;
    @FXML private Label couldNotSolveCountLabel;
    @FXML private Label inProgressCountLabel;
    @FXML private Label notStartedCountLabel;
    @FXML private VBox stageBreakdownBox;

    @FXML private Label firstSubmissionAccuracyLabel;
    @FXML private Label independentSolveRateLabel;
    @FXML private Label editorialDependencyRateLabel;
    @FXML private Label averageSubmissionsLabel;
    @FXML private Label averagePerceivedDifficultyLabel;
    @FXML private Label mandatoryCompletionLabel;
    @FXML private Label optionalCompletionLabel;

    @FXML private Label avgReadingLabel;
    @FXML private Label avgThinkingLabel;
    @FXML private Label avgCodingLabel;
    @FXML private Label avgDebuggingLabel;
    @FXML private Label totalSolvingTimeLabel;
    @FXML private Label bottleneckLabel;

    @FXML private Label topicInsightsEmptyLabel;
    @FXML private VBox topicInsightsBox;

    @FXML private Label reflectionGapsEmptyLabel;
    @FXML private VBox reflectionGapsBox;

    @FXML private Label unfinishedAttemptsEmptyLabel;
    @FXML private VBox unfinishedAttemptsBox;

    private final ProblemDashboardService dashboardService = new ProblemDashboardService();
    private final ProblemSolvingSessionService solvingSessionService = new ProblemSolvingSessionService();

    private ProblemDashboardFilter filter = ProblemDashboardFilter.empty();
    private Long pendingResumeProblemId;

    @FXML
    public void initialize() {
        configureFilterMenus();
        refresh();
    }

    @FXML
    public void clearFilters() {
        filter = ProblemDashboardFilter.empty();
        stageFilterMenuButton.setText("Stage: All");
        dateRangeFilterMenuButton.setText("Range: All time");
        refresh();
    }

    @FXML
    public void resumeRecommended() {
        if (pendingResumeProblemId == null) {
            return;
        }
        solvingSessionService.startOrResume(pendingResumeProblemId);
        NavigationService.showSolvingWorkspace(pendingResumeProblemId);
    }

    private void configureFilterMenus() {
        stageFilterMenuButton.getItems().setAll(menuItem("Stage: All", () -> setStageFilter(null)));
        for (RoadmapStage stage : RoadmapStage.values()) {
            stageFilterMenuButton.getItems().add(menuItem("Stage: " + stage, () -> setStageFilter(stage)));
        }

        LocalDate today = LocalDate.now();
        dateRangeFilterMenuButton.getItems().setAll(
                menuItem("Range: All time", () -> setDateRange(null, null, "Range: All time")),
                menuItem("Range: Last 7 days", () -> setDateRange(today.minusDays(6), today, "Range: Last 7 days")),
                menuItem("Range: Last 30 days", () -> setDateRange(today.minusDays(29), today, "Range: Last 30 days")),
                menuItem("Range: Last 90 days", () -> setDateRange(today.minusDays(89), today, "Range: Last 90 days")));
    }

    private MenuItem menuItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private void setStageFilter(RoadmapStage stage) {
        filter = filter.withStage(stage);
        stageFilterMenuButton.setText(stage == null ? "Stage: All" : "Stage: " + stage);
        refresh();
    }

    private void setDateRange(LocalDate from, LocalDate to, String label) {
        filter = filter.withFromDate(from).withToDate(to);
        dateRangeFilterMenuButton.setText(label);
        refresh();
    }

    private void refresh() {
        ProblemDashboard dashboard = dashboardService.build(filter);
        populateRecommendation(dashboard.recommendation());
        populateCoreProgress(dashboard.coreProgress());
        populateQualityMetrics(dashboard.qualityMetrics());
        populateTimingInsights(dashboard.timingInsights());
        populateTopicInsights(dashboard.topicInsights());
        populateReflectionGaps(dashboard.overdueReflections());
        populateUnfinishedAttempts(dashboard.unfinishedAttempts());
    }

    private void populateRecommendation(ProblemDashboard.Recommendation recommendation) {
        recommendationReasonLabel.setText(recommendation.reason());
        recommendation.entry().ifPresentOrElse(entry -> {
            Problem problem = entry.problem();
            pendingResumeProblemId = problem.getId();
            recommendationLabel.setText("[" + problem.getExternalCode() + "] " + problem.getTitle());
            setVisible(recommendationResumeButton, true);
        }, () -> {
            pendingResumeProblemId = null;
            recommendationLabel.setText("Nothing to recommend right now.");
            setVisible(recommendationResumeButton, false);
        });
    }

    private void populateCoreProgress(ProblemDashboard.CoreProgress core) {
        if (core.mandatoryTotal() + core.optionalTotal() == 0) {
            currentStageLabel.setText("No roadmap imported yet. Import a Training Sheet from Settings to build one.");
        } else if (core.roadmapComplete()) {
            currentStageLabel.setText("Roadmap complete — every problem is solved!");
        } else {
            String setPart = core.currentSet() == null ? "" : " · Set " + core.currentSet();
            currentStageLabel.setText("Current: Stage " + core.currentStage() + setPart);
        }

        mandatoryProgressLabel.setText(core.mandatoryCompleted() + " / " + core.mandatoryTotal()
                + " (" + core.mandatoryRemaining() + " remaining)");
        optionalProgressLabel.setText(core.optionalCompleted() + " / " + core.optionalTotal());

        List<ProblemDashboard.WeeklyCount> weeks = core.problemsSolvedPerWeek();
        int solvedThisWeek = weeks.isEmpty() ? 0 : weeks.get(weeks.size() - 1).solvedCount();
        solvedThisWeekLabel.setText(solvedThisWeek + " " + (solvedThisWeek == 1 ? "problem" : "problems"));

        ProblemDashboard.StatusBreakdown status = core.statusBreakdown();
        acCountLabel.setText("AC: " + status.acCount());
        acxCountLabel.setText("ACX: " + status.acxCount());
        couldNotSolveCountLabel.setText("CS: " + status.couldNotSolveCount());
        inProgressCountLabel.setText("In Progress: " + status.inProgressCount());
        notStartedCountLabel.setText("Not Started: " + status.notStartedCount());

        stageBreakdownBox.getChildren().clear();
        List<ProblemDashboard.StageProgress> nonEmptyStages = core.stageBreakdown().stream()
                .filter(stage -> stage.total() > 0).toList();
        if (nonEmptyStages.isEmpty()) {
            stageBreakdownBox.getChildren().add(helperLabel("No roadmap imported yet."));
        } else {
            nonEmptyStages.forEach(stage -> stageBreakdownBox.getChildren().add(helperLabel(
                    "Stage " + stage.stage() + ": " + stage.solved() + " / " + stage.total()
                            + " (" + Math.round(stage.completionPercent()) + "%)")));
        }
    }

    private void populateQualityMetrics(ProblemDashboard.QualityMetrics quality) {
        firstSubmissionAccuracyLabel.setText(quality.hasFirstSubmissionSignal()
                ? String.format("First-submission accuracy: %.0f%% (%d problems)",
                        quality.firstSubmissionAccuracyPercent(), quality.firstSubmissionSampleCount())
                : "First-submission accuracy: not enough data yet");
        independentSolveRateLabel.setText(quality.hasIndependenceSignal()
                ? String.format("Independent solve rate: %.0f%% (%d solved problems)",
                        quality.independentSolveRatePercent(), quality.independenceSampleCount())
                : "Independent solve rate: not enough data yet");
        editorialDependencyRateLabel.setText(quality.hasIndependenceSignal()
                ? String.format("Editorial dependency rate: %.0f%%", quality.editorialDependencyRatePercent())
                : "Editorial dependency rate: not enough data yet");
        averageSubmissionsLabel.setText(quality.hasAcceptedSampleSignal()
                ? String.format("Avg submissions per accepted problem: %.1f (%d problems)",
                        quality.averageSubmissionsPerAccepted(), quality.acceptedSampleCount())
                : "Avg submissions per accepted problem: not enough data yet");
        averagePerceivedDifficultyLabel.setText(quality.hasPerceivedDifficultySignal()
                ? String.format("Avg perceived difficulty: %.1f / 10 (%d rated)",
                        quality.averagePerceivedDifficulty(), quality.perceivedDifficultySampleCount())
                : "Avg perceived difficulty: not enough data yet");
        mandatoryCompletionLabel.setText(String.format("Mandatory completion: %.0f%%", quality.mandatoryCompletionPercent()));
        optionalCompletionLabel.setText(String.format("Optional completion: %.0f%%", quality.optionalCompletionPercent()));
    }

    private void populateTimingInsights(ProblemDashboard.TimingInsights timing) {
        if (!timing.hasSignal()) {
            String noSignal = "Not enough timed attempts yet";
            avgReadingLabel.setText(noSignal);
            avgThinkingLabel.setText(noSignal);
            avgCodingLabel.setText(noSignal);
            avgDebuggingLabel.setText(noSignal);
            totalSolvingTimeLabel.setText("Total solving time: not enough data yet");
            bottleneckLabel.setText("Biggest bottleneck: not enough data yet");
            return;
        }
        avgReadingLabel.setText(formatSeconds(timing.averageReadingSeconds()));
        avgThinkingLabel.setText(formatSeconds(timing.averageThinkingSeconds()));
        avgCodingLabel.setText(formatSeconds(timing.averageCodingSeconds()));
        avgDebuggingLabel.setText(formatSeconds(timing.averageDebuggingSeconds()));
        totalSolvingTimeLabel.setText("Total solving time: " + formatSeconds(timing.totalSolvingSeconds()));
        bottleneckLabel.setText(timing.bottleneckPhase() == null ? "Biggest bottleneck: not enough data yet"
                : "Biggest bottleneck: " + capitalize(timing.bottleneckPhase().name()));
    }

    private void populateTopicInsights(List<ProblemDashboard.TopicInsight> topics) {
        topicInsightsBox.getChildren().clear();
        setVisible(topicInsightsEmptyLabel, topics.isEmpty());
        if (topics.isEmpty()) {
            topicInsightsEmptyLabel.setText("Attempt a few problems to unlock per-topic accuracy and independence.");
            return;
        }
        topics.forEach(topic -> topicInsightsBox.getChildren().add(createTopicRow(topic)));
    }

    private HBox createTopicRow(ProblemDashboard.TopicInsight topic) {
        Label nameLabel = new Label(topic.topic());
        nameLabel.getStyleClass().add("problem-row-title");
        nameLabel.setWrapText(true);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        String detail = topic.category() == ProblemDashboard.TopicCategory.INSUFFICIENT_SAMPLE
                ? topic.sampleCount() + " attempted — not enough data yet"
                : String.format("%d attempted · %.0f%% accuracy · %.0f%% independent",
                        topic.sampleCount(), topic.accuracyPercent(), topic.independencePercent());
        Label detailLabel = new Label(detail);
        detailLabel.getStyleClass().add("dashboard-card-helper");
        detailLabel.setWrapText(true);

        Label badge = new Label(capitalize(topic.category().name()));
        badge.getStyleClass().addAll("skill-status-badge", topicCategoryStyle(topic.category()));

        VBox textColumn = new VBox(2, nameLabel, detailLabel);
        textColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textColumn, Priority.ALWAYS);

        HBox row = new HBox(10, textColumn, badge);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private String topicCategoryStyle(ProblemDashboard.TopicCategory category) {
        return switch (category) {
            case STRONG -> "skill-status-strong";
            case WEAK -> "skill-status-weak";
            case DEVELOPING, INSUFFICIENT_SAMPLE -> "skill-status-stable";
        };
    }

    private void populateReflectionGaps(List<ProblemDashboard.ReflectionGap> gaps) {
        reflectionGapsBox.getChildren().clear();
        setVisible(reflectionGapsEmptyLabel, gaps.isEmpty());
        if (gaps.isEmpty()) {
            reflectionGapsEmptyLabel.setText("Every solved problem already has a reflection recorded.");
            return;
        }
        gaps.stream().limit(10).forEach(gap -> {
            Problem problem = gap.problem();
            String completedAt = gap.progress().getCompletedAt() == null ? ""
                    : " — solved " + gap.progress().getCompletedAt().toLocalDate().format(SHORT_DATE);
            reflectionGapsBox.getChildren().add(helperLabel("[" + problem.getExternalCode() + "] " + problem.getTitle() + completedAt));
        });
    }

    private void populateUnfinishedAttempts(List<ProblemDashboard.UnfinishedAttempt> unfinished) {
        unfinishedAttemptsBox.getChildren().clear();
        setVisible(unfinishedAttemptsEmptyLabel, unfinished.isEmpty());
        if (unfinished.isEmpty()) {
            unfinishedAttemptsEmptyLabel.setText("No unfinished workspace sessions.");
            return;
        }
        unfinished.stream().limit(10).forEach(attempt -> unfinishedAttemptsBox.getChildren().add(createUnfinishedRow(attempt)));
    }

    private HBox createUnfinishedRow(ProblemDashboard.UnfinishedAttempt attempt) {
        Problem problem = attempt.problem();
        Label label = new Label("[" + problem.getExternalCode() + "] " + problem.getTitle()
                + " — last active " + attempt.session().getLastActiveAt().toLocalDate().format(SHORT_DATE));
        label.getStyleClass().add("dashboard-card-helper");
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);

        Button resumeButton = new Button("Resume");
        resumeButton.getStyleClass().add("ghost-button");
        resumeButton.setOnAction(event -> {
            solvingSessionService.startOrResume(problem.getId());
            NavigationService.showSolvingWorkspace(problem.getId());
        });

        HBox row = new HBox(10, label, resumeButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private Label helperLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dashboard-card-helper");
        label.setWrapText(true);
        return label;
    }

    private String formatSeconds(double seconds) {
        long total = Math.round(seconds);
        long minutes = total / 60;
        long remainingSeconds = total % 60;
        return minutes > 0 ? minutes + "m " + remainingSeconds + "s" : remainingSeconds + "s";
    }

    private String capitalize(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
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

package com.codefit.controller;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.UserProgress;
import com.codefit.service.AssessmentRunSummary;
import com.codefit.service.AssessmentStatsService;
import com.codefit.service.EngineerReadinessStats;
import com.codefit.service.FlashcardService;
import com.codefit.service.LearningEfficiencyStats;
import com.codefit.service.MasteryService;
import com.codefit.service.StatsService;
import com.codefit.service.StatsSkillPerformance;
import com.codefit.service.TransferSkillPerformance;
import com.codefit.ui.NavigationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class StatsController extends BaseController {
    @FXML private Label readinessScoreLabel;
    @FXML private Label timedFluencyLabel;
    @FXML private Label weakAreaPressureLabel;
    @FXML private Label streakLabel;
    @FXML private Label consistencyLabel;
    @FXML private Label subjectiveSelfAssessmentLabel;
    @FXML private Label confidenceCalibrationLabel;
    @FXML private Label statsEmptyStateLabel;
    @FXML private VBox statsEmptyStateBox;
    @FXML private ListView<String> recentReviewsListView;
    @FXML private ListView<StatsSkillPerformance> skillPerformanceListView;
    @FXML private ListView<StatsSkillPerformance> needsPracticeListView;
    @FXML private Label heroRetentionLabel;
    @FXML private Label heroMasteredLabel;
    @FXML private Label heroThisWeekLabel;
    @FXML private VBox weakestSkillsCompactList;
    @FXML private Label masterySeenLabel;
    @FXML private Label masteryLearningLabel;
    @FXML private Label masteryMasteredLabel;
    @FXML private Label efficiencyMasteredPerHourLabel;
    @FXML private Label efficiencyRecallsPerMinuteLabel;
    @FXML private Label efficiencyRecoveredMissesLabel;
    @FXML private Label retention7DayLabel;
    @FXML private Label retention14DayLabel;
    @FXML private Label retention30DayLabel;
    @FXML private Label efficiencyConfidenceLabel;
    @FXML private Label suspendedCardTimeLabel;
    @FXML private Label timeBySkillLabel;
    @FXML private Label timeByCardTypeLabel;
    @FXML private Label graduatedCardsLabel;
    @FXML private Label suspendedCardsLabel;
    @FXML private Label needsRewriteCountLabel;
    @FXML private VBox needsRewriteCardsBox;
    @FXML private Label latestAssessmentSummaryLabel;
    @FXML private VBox transferSkillBreakdownBox;
    @FXML private ToggleButton overviewTabButton;
    @FXML private ToggleButton skillsTabButton;
    @FXML private ToggleButton activityTabButton;
    @FXML private VBox overviewTabPanel;
    @FXML private VBox skillsTabPanel;
    @FXML private VBox activityTabPanel;

    private static final int MAX_PROMPT_LENGTH = 48;

    private final StatsService statsService = new StatsService();
    private final FlashcardService flashcardService = new FlashcardService();
    private final MasteryService masteryService = new MasteryService();
    private final AssessmentStatsService assessmentStatsService = new AssessmentStatsService();

    private enum ProgressTab { OVERVIEW, SKILLS, ACTIVITY }

    private ProgressTab activeTab = ProgressTab.OVERVIEW;

    @FXML
    public void initialize() {
        UserProgress progress = statsService.getProgress();
        streakLabel.setText(progress.getStreakDays() + " days");
        EngineerReadinessStats readinessStats = statsService.getEngineerReadinessStats();
        configureReadinessStats(readinessStats);
        configureStatsEmptyState(progress.getTotalReviews());
        populateHeroMetrics(readinessStats);
        populateMasteryBreakdown();
        populateCardStateBreakdown();
        populateWeakestSkillsCompact();
        populateLearningEfficiency();
        populateTransferAssessment();
        populateNeedsRewrite();
        configureTabs();

        configureSkillPerformanceList();
        configureNeedsPracticeList();

        var recent = statsService.getRecentReviews().stream().map(this::formatReview).toList();
        recentReviewsListView.setItems(recent.isEmpty()
                ? FXCollections.observableArrayList("Stats appear after review sessions. Next action: start a review to create your first activity entry.")
                : FXCollections.observableArrayList(recent));

        var skillPerformance = statsService.getSkillPerformance();
        skillPerformanceListView.setItems(skillPerformance.isEmpty()
                ? FXCollections.observableArrayList(emptySkillPerformance())
                : FXCollections.observableArrayList(skillPerformance));

        var needsPractice = statsService.getNeedsPracticeSkills();
        needsPracticeListView.setItems(needsPractice.isEmpty()
                ? FXCollections.observableArrayList(emptyNeedsPractice())
                : FXCollections.observableArrayList(needsPractice));
    }

    private void configureTabs() {
        ToggleGroup tabGroup = new ToggleGroup();
        overviewTabButton.setToggleGroup(tabGroup);
        skillsTabButton.setToggleGroup(tabGroup);
        activityTabButton.setToggleGroup(tabGroup);
        tabGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null) {
                tabGroup.selectToggle(oldValue == null ? overviewTabButton : oldValue);
                return;
            }
            activeTab = newValue == skillsTabButton ? ProgressTab.SKILLS
                    : newValue == activityTabButton ? ProgressTab.ACTIVITY
                    : ProgressTab.OVERVIEW;
            updateTabVisibility();
        });
        updateTabVisibility();
    }

    private void updateTabVisibility() {
        setVisible(overviewTabPanel, activeTab == ProgressTab.OVERVIEW);
        setVisible(skillsTabPanel, activeTab == ProgressTab.SKILLS);
        setVisible(activityTabPanel, activeTab == ProgressTab.ACTIVITY);
    }

    private void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void configureReadinessStats(EngineerReadinessStats readinessStats) {
        if (!readinessStats.hasSignal()) {
            readinessScoreLabel.setText("No signal");
            timedFluencyLabel.setText("No signal");
            weakAreaPressureLabel.setText("No signal");
            consistencyLabel.setText("No signal");
            subjectiveSelfAssessmentLabel.setText("No signal");
            confidenceCalibrationLabel.setText("No signal");
            return;
        }

        readinessScoreLabel.setText(formatPercent(readinessStats.readinessScore()));
        timedFluencyLabel.setText(formatPercent(readinessStats.timedSuccessPercent()));
        weakAreaPressureLabel.setText(formatPercent(readinessStats.weakAreaRatePercent()));
        consistencyLabel.setText(formatPercent(readinessStats.consistencyPercent()));
        subjectiveSelfAssessmentLabel.setText(formatPercent(readinessStats.subjectiveSelfAssessmentPercent()));
        confidenceCalibrationLabel.setText(readinessStats.hasConfidenceSignal()
                ? formatPercent(readinessStats.confidenceCalibrationPercent())
                : "No signal");
    }

    /** Top-line answer to "am I retaining what I learned, and how much is durably mastered?" */
    private void populateHeroMetrics(EngineerReadinessStats readinessStats) {
        heroRetentionLabel.setText(readinessStats.hasSignal()
                ? formatPercent(readinessStats.recentAccuracyPercent())
                : "No signal");

        int masteredCount = masteryService.summarize(flashcardService.getAllCards()).masteredCards();
        heroMasteredLabel.setText(masteredCount + " " + (masteredCount == 1 ? "card" : "cards"));

        long reviewsThisWeek = statsService.getRecentReviews().stream()
                .filter(history -> history.getReviewedAt() != null
                        && !history.getReviewedAt().toLocalDate().isBefore(LocalDate.now().minusDays(6)))
                .count();
        heroThisWeekLabel.setText(reviewsThisWeek + " " + (reviewsThisWeek == 1 ? "review" : "reviews"));
    }

    /** How much content is durably mastered, out of everything that has been seen at all. */
    private void populateMasteryBreakdown() {
        MasteryService.MasteryBreakdown breakdown = masteryService.summarize(flashcardService.getAllCards());
        if (breakdown.totalCards() == 0) {
            masterySeenLabel.setText("No cards yet");
            masteryLearningLabel.setText("No cards yet");
            masteryMasteredLabel.setText("No cards yet");
            return;
        }
        masterySeenLabel.setText(Math.round(breakdown.seenPercent()) + "% (" + breakdown.seenCards() + ")");
        masteryLearningLabel.setText(Math.round(breakdown.learningPercent()) + "% (" + breakdown.learningCards() + ")");
        masteryMasteredLabel.setText(Math.round(breakdown.masteredPercent()) + "% (" + breakdown.masteredCards() + ")");
    }

    /** Distinguishes cards diagnostically graduated ("already know this") from cards suspended out
     *  of every queue, so neither is silently folded into the durable-mastery breakdown above. */
    private void populateCardStateBreakdown() {
        StatsService.CardStateBreakdown breakdown = statsService.getCardStateBreakdown();
        graduatedCardsLabel.setText(breakdown.graduatedCards() + " " + (breakdown.graduatedCards() == 1 ? "card" : "cards"));
        suspendedCardsLabel.setText(breakdown.suspendedCards() + " " + (breakdown.suspendedCards() == 1 ? "card" : "cards"));
    }

    /** Ranked, at-a-glance view of the weakest skills; the full drill-down list with sample size
     *  and a review action lives in the Skills tab. */
    private void populateWeakestSkillsCompact() {
        weakestSkillsCompactList.getChildren().clear();
        List<StatsSkillPerformance> weakSkills = statsService.getNeedsPracticeSkills();
        if (weakSkills.isEmpty()) {
            Label emptyLabel = new Label("No weak-area signal yet — complete reviews to unlock this.");
            emptyLabel.getStyleClass().add("dashboard-card-helper");
            emptyLabel.setWrapText(true);
            weakestSkillsCompactList.getChildren().add(emptyLabel);
            return;
        }
        weakSkills.stream().limit(3).forEach(skill -> {
            HBox row = new HBox(12);
            row.setMaxWidth(Double.MAX_VALUE);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("deck-progress-row");

            Label nameLabel = new Label(skill.skillCategory());
            nameLabel.getStyleClass().add("deck-name");
            nameLabel.setWrapText(true);
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            Label sampleLabel = new Label(skill.recentReviews() + " reviews");
            sampleLabel.getStyleClass().add("deck-count");

            Label accuracyLabel = new Label(Math.round(skill.accuracyPercent()) + "%");
            accuracyLabel.getStyleClass().add("deck-due-label");

            row.getChildren().addAll(nameLabel, sampleLabel, accuracyLabel);
            weakestSkillsCompactList.getChildren().add(row);
        });
    }

    /** Whether training time is producing durable knowledge, kept fully separate from the
     *  gamification/activity signals elsewhere on this screen (XP, streak, consistency). Reads
     *  the full history in scope rather than only the most recent reviews, so long-horizon
     *  figures like the 30+ day retention bucket aren't starved by a small recency window. */
    private void populateLearningEfficiency() {
        LearningEfficiencyStats efficiency = statsService.getLearningEfficiencyStats();

        efficiencyMasteredPerHourLabel.setText(efficiency.hasTrainingTimeSignal()
                ? String.format("%.1f/hr", efficiency.masteredCardsPerHour())
                : "Not enough training time yet");
        efficiencyRecallsPerMinuteLabel.setText(efficiency.hasTrainingTimeSignal()
                ? String.format("%.2f/min", efficiency.objectiveRecallsPerMinute())
                : "Not enough training time yet");
        efficiencyRecoveredMissesLabel.setText(efficiency.hasSessionSignal()
                ? String.format("%.1f/session", efficiency.recoveredMissesPerSession())
                : "Not enough sessions yet");
        efficiencyConfidenceLabel.setText(efficiency.hasConfidenceSignal()
                ? formatPercent(efficiency.confidenceCalibrationPercent())
                : "No signal");
        suspendedCardTimeLabel.setText(efficiency.hasSuspendedCardSignal()
                ? efficiency.suspendedCardCount() + " " + (efficiency.suspendedCardCount() == 1 ? "card" : "cards")
                + " (" + formatMinutes(efficiency.suspendedCardActiveMinutes()) + ")"
                : "No suspended cards yet");

        LearningEfficiencyStats.RetentionByInterval retention = efficiency.retentionByInterval();
        retention7DayLabel.setText(formatRetentionBucket(retention.sevenToThirteenDays()));
        retention14DayLabel.setText(formatRetentionBucket(retention.fourteenToTwentyNineDays()));
        retention30DayLabel.setText(formatRetentionBucket(retention.thirtyPlusDays()));

        timeBySkillLabel.setText(formatTimeBreakdown(efficiency.activeMinutesBySkill().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey() + " " + formatMinutes(entry.getValue()))));
        timeByCardTypeLabel.setText(formatTimeBreakdown(efficiency.activeMinutesByCardType().entrySet().stream()
                .sorted(Map.Entry.<CardType, Double>comparingByValue().reversed())
                .map(entry -> entry.getKey() + " " + formatMinutes(entry.getValue()))));
    }

    /**
     * Transfer-assessment accuracy is reported entirely separately from normal-review retention
     * stats above: it never shares a chart, list, or aggregation with {@link #populateWeakestSkillsCompact}
     * or {@link #configureSkillPerformanceList}, so a learner can never mistake one signal for the
     * other (#104). Assessment results are read-only here — nothing in this method writes anything.
     */
    private void populateTransferAssessment() {
        if (latestAssessmentSummaryLabel == null || transferSkillBreakdownBox == null) {
            return;
        }
        Optional<AssessmentRunSummary> latestRun = assessmentStatsService.getLatestRunSummary();
        latestAssessmentSummaryLabel.setText(latestRun.isPresent()
                ? latestRun.get().runDate() + " — " + latestRun.get().correctCount() + " / " + latestRun.get().totalItems()
                        + " correct (" + formatPercent(latestRun.get().accuracyPercent()) + ")"
                : "No transfer assessment completed yet.");

        transferSkillBreakdownBox.getChildren().clear();
        List<TransferSkillPerformance> bySkill = assessmentStatsService.getTransferPerformanceBySkill();
        if (bySkill.isEmpty()) {
            Label emptyLabel = new Label("Complete a weekly transfer assessment to see per-skill transfer accuracy here.");
            emptyLabel.getStyleClass().add("dashboard-card-helper");
            emptyLabel.setWrapText(true);
            transferSkillBreakdownBox.getChildren().add(emptyLabel);
            return;
        }
        bySkill.forEach(performance -> {
            Label row = new Label(performance.skillCategory() + ": " + performance.correctCount() + " / "
                    + performance.attempts() + " (" + formatPercent(performance.accuracyPercent()) + ")");
            row.getStyleClass().add("dashboard-card-helper");
            row.setWrapText(true);
            transferSkillBreakdownBox.getChildren().add(row);
        });
    }

    /**
     * Cards flagged LEECH are surfaced here once, prominently, rather than only being discovered
     * mid-session; see ReviewService for how a flagged leech avoids being repeatedly re-prioritized
     * into every adaptive session instead of appearing here.
     */
    private void populateNeedsRewrite() {
        if (needsRewriteCountLabel == null || needsRewriteCardsBox == null) {
            return;
        }
        int leechCount = statsService.getCardStateBreakdown().leechCards();
        needsRewriteCountLabel.setText(leechCount == 0
                ? "No cards currently need a rewrite."
                : leechCount + " " + (leechCount == 1 ? "card needs" : "cards need") + " a rewrite.");

        needsRewriteCardsBox.getChildren().clear();
        flashcardService.getLeechCards().stream().limit(10).forEach(card -> {
            Label row = new Label(shortenPrompt(card.getFront()));
            row.getStyleClass().add("dashboard-card-helper");
            row.setWrapText(true);
            needsRewriteCardsBox.getChildren().add(row);
        });
    }

    private String formatRetentionBucket(LearningEfficiencyStats.RetentionBucket bucket) {
        return bucket.hasSignal()
                ? formatPercent(bucket.retentionPercent()) + " (" + bucket.sampleSize() + ")"
                : "Not enough reviews yet";
    }

    private String formatTimeBreakdown(Stream<String> entries) {
        String summary = entries.limit(5).collect(Collectors.joining(", "));
        return summary.isEmpty() ? "Not enough reviews yet" : summary;
    }

    private String formatMinutes(double minutes) {
        return minutes < 1 ? "<1m" : Math.round(minutes) + "m";
    }

    private String formatPercent(double value) {
        return String.format("%.0f%%", value);
    }

    private void configureStatsEmptyState(int totalReviews) {
        boolean empty = totalReviews == 0;
        statsEmptyStateBox.setVisible(empty);
        statsEmptyStateBox.setManaged(empty);
        if (empty) {
            statsEmptyStateLabel.setText("Stats appear after review sessions. Complete one review session to unlock accuracy, weak-area, and activity signals.");
        }
    }

    private void configureSkillPerformanceList() {
        skillPerformanceListView.setCellFactory(listView -> new StatsSkillCell(false));
    }

    private void configureNeedsPracticeList() {
        needsPracticeListView.setCellFactory(listView -> new StatsSkillCell(true));
    }

    private StatsSkillPerformance emptySkillPerformance() {
        return new StatsSkillPerformance("Stats appear after reviews", 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private StatsSkillPerformance emptyNeedsPractice() {
        return new StatsSkillPerformance("No weak areas yet", 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private String formatReview(ReviewHistory history) {
        String objectiveAccuracy = history.isSubjective() ? "Self-rated" : history.isObjectivelyCorrect() ? "Correct" : "Missed";
        return history.getReviewedAt().toLocalDate() + " • " + getCardPrompt(history.getFlashcardId())
                + " • " + objectiveAccuracy + " (rated " + history.getRating() + ")"
                + " • " + history.getPreviousIntervalDays() + "d → " + history.getNewIntervalDays() + "d";
    }

    private String getCardPrompt(long flashcardId) {
        return flashcardService.getCardById(flashcardId)
                .map(Flashcard::getFront)
                .map(this::shortenPrompt)
                .orElse("Deleted card #" + flashcardId);
    }

    private String shortenPrompt(String prompt) {
        String normalized = prompt == null ? "" : prompt.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return "Untitled card";
        }
        if (normalized.length() <= MAX_PROMPT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_PROMPT_LENGTH - 1).stripTrailing() + "…";
    }

    private final class StatsSkillCell extends ListCell<StatsSkillPerformance> {
        private final boolean actionable;

        private StatsSkillCell(boolean actionable) {
            this.actionable = actionable;
        }

        @Override
        protected void updateItem(StatsSkillPerformance performance, boolean empty) {
            super.updateItem(performance, empty);
            if (empty || performance == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(null);
            setGraphic(createSkillRow(performance, actionable));
        }
    }

    private VBox createSkillRow(StatsSkillPerformance performance, boolean actionable) {
        VBox row = new VBox(8);
        row.getStyleClass().add("skill-stat-row");

        Label nameLabel = new Label(performance.skillCategory());
        nameLabel.getStyleClass().add("skill-stat-title");
        nameLabel.setWrapText(true);

        Label statusBadge = new Label(formatSkillStatus(performance));
        statusBadge.getStyleClass().addAll("skill-status-badge", skillStatusStyle(performance));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleLine = new HBox(10, nameLabel, spacer, statusBadge);
        titleLine.setAlignment(Pos.CENTER_LEFT);

        Label detailLabel = new Label(formatSkillDetail(performance));
        detailLabel.getStyleClass().add("skill-stat-detail");
        detailLabel.setWrapText(true);

        ProgressBar accuracyBar = new ProgressBar(performance.accuracyPercent() / 100.0);
        accuracyBar.setMaxWidth(Double.MAX_VALUE);
        accuracyBar.getStyleClass().addAll("skill-accuracy-bar", skillStatusStyle(performance));

        HBox distribution = createDistributionBar(performance);
        Label legendLabel = new Label("Ratings: Again " + performance.againCount() + " · Hard " + performance.hardCount()
                + " · Good " + performance.goodCount() + " · Easy " + performance.easyCount());
        legendLabel.getStyleClass().add("skill-stat-detail");
        legendLabel.setWrapText(true);

        row.getChildren().addAll(titleLine, detailLabel, accuracyBar, distribution, legendLabel);

        if (actionable && performance.recentReviews() > 0) {
            Button reviewButton = new Button("Review due cards");
            reviewButton.getStyleClass().addAll("skill-review-button", "action-button");
            reviewButton.setOnAction(event -> NavigationService.showReview());
            row.getChildren().add(reviewButton);
        }
        return row;
    }

    private HBox createDistributionBar(StatsSkillPerformance performance) {
        HBox bar = new HBox();
        bar.getStyleClass().add("rating-distribution-bar");
        addDistributionSegment(bar, "rating-again-segment", performance.againCount(), performance.recentReviews());
        addDistributionSegment(bar, "rating-hard-segment", performance.hardCount(), performance.recentReviews());
        addDistributionSegment(bar, "rating-good-segment", performance.goodCount(), performance.recentReviews());
        addDistributionSegment(bar, "rating-easy-segment", performance.easyCount(), performance.recentReviews());
        return bar;
    }

    private void addDistributionSegment(HBox bar, String styleClass, int count, int total) {
        Region segment = new Region();
        segment.getStyleClass().addAll("rating-distribution-segment", styleClass);
        HBox.setHgrow(segment, Priority.ALWAYS);
        segment.setMaxWidth(Double.MAX_VALUE);
        segment.setMinWidth(total == 0 || count == 0 ? 4 : Math.max(8, count * 220.0 / total));
        bar.getChildren().add(segment);
    }

    private String formatSkillDetail(StatsSkillPerformance performance) {
        if (performance.totalCards() == 0 && performance.recentReviews() == 0) {
            return "Stats appear after review sessions. Next action: start a review once cards are due to populate accuracy and weak-area signals.";
        }
        return String.format("%.0f%% accuracy from %d recent reviews · %.0f%% Again/Hard weak-area signal · %d due of %d cards",
                performance.accuracyPercent(),
                performance.recentReviews(),
                performance.needsPracticeRate(),
                performance.dueCards(),
                performance.totalCards());
    }

    private String formatSkillStatus(StatsSkillPerformance performance) {
        if (performance.recentReviews() == 0) {
            return "Needs signal";
        }
        if (performance.needsPractice()) {
            return "Weak";
        }
        if (performance.accuracyPercent() >= 80.0 && performance.needsPracticeRate() < 25.0) {
            return "Strong";
        }
        return "Stable";
    }

    private String skillStatusStyle(StatsSkillPerformance performance) {
        if (performance.recentReviews() == 0) {
            return "skill-status-stable";
        }
        if (performance.needsPractice()) {
            return "skill-status-weak";
        }
        if (performance.accuracyPercent() >= 80.0 && performance.needsPracticeRate() < 25.0) {
            return "skill-status-strong";
        }
        return "skill-status-stable";
    }
}

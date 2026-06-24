package com.codefit.controller;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.UserProgress;
import com.codefit.service.FlashcardService;
import com.codefit.service.ProgressService;
import com.codefit.service.StatsService;
import com.codefit.service.StatsSkillPerformance;
import com.codefit.ui.NavigationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class StatsController extends BaseController {
    @FXML private Label levelLabel;
    @FXML private Label xpLabel;
    @FXML private Label streakLabel;
    @FXML private Label totalReviewsLabel;
    @FXML private Label totalCardsLabel;
    @FXML private Label dueCardsLabel;
    @FXML private Label reviewedTodayLabel;
    @FXML private ProgressBar xpProgressBar;
    @FXML private ListView<String> recentReviewsListView;
    @FXML private ListView<StatsSkillPerformance> skillPerformanceListView;
    @FXML private ListView<StatsSkillPerformance> needsPracticeListView;

    private static final int MAX_PROMPT_LENGTH = 48;

    private final StatsService statsService = new StatsService();
    private final FlashcardService flashcardService = new FlashcardService();

    @FXML
    public void initialize() {
        UserProgress progress = statsService.getProgress();
        levelLabel.setText("Level " + progress.getLevel());
        xpLabel.setText(progress.getXp() + " XP");
        streakLabel.setText(progress.getStreakDays() + " days");
        totalReviewsLabel.setText(String.valueOf(progress.getTotalReviews()));
        totalCardsLabel.setText(String.valueOf(statsService.getTotalCards()));
        dueCardsLabel.setText(String.valueOf(statsService.getDueCards()));
        reviewedTodayLabel.setText(String.valueOf(statsService.getReviewedToday()));
        xpProgressBar.setProgress((progress.getXp() % ProgressService.XP_PER_LEVEL) / (double) ProgressService.XP_PER_LEVEL);

        configureSkillPerformanceList();
        configureNeedsPracticeList();

        var recent = statsService.getRecentReviews().stream().map(this::formatReview).toList();
        recentReviewsListView.setItems(recent.isEmpty()
                ? FXCollections.observableArrayList("No reviews logged yet. Complete a review session to see recent activity.")
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

    private void configureSkillPerformanceList() {
        skillPerformanceListView.setCellFactory(listView -> new StatsSkillCell(false));
    }

    private void configureNeedsPracticeList() {
        needsPracticeListView.setCellFactory(listView -> new StatsSkillCell(true));
    }

    private StatsSkillPerformance emptySkillPerformance() {
        return new StatsSkillPerformance("No skill data yet", 0, 0, 0, 0, 0, 0, 0);
    }

    private StatsSkillPerformance emptyNeedsPractice() {
        return new StatsSkillPerformance("No weak areas flagged", 0, 0, 0, 0, 0, 0, 0);
    }

    private String formatReview(ReviewHistory history) {
        return history.getReviewedAt().toLocalDate() + " • " + getCardPrompt(history.getFlashcardId())
                + " • " + history.getRating() + " • " + history.getPreviousIntervalDays()
                + "d → " + history.getNewIntervalDays() + "d";
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
            return "Add categorized cards and complete reviews to populate accuracy, weak-area, and rating distribution signals.";
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

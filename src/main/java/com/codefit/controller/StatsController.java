package com.codefit.controller;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewHistory;
import com.codefit.model.UserProgress;
import com.codefit.service.FlashcardService;
import com.codefit.service.ProgressService;
import com.codefit.service.StatsService;
import com.codefit.service.StatsSkillPerformance;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;

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
    @FXML private ListView<String> skillPerformanceListView;
    @FXML private ListView<String> needsPracticeListView;

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

        var recent = statsService.getRecentReviews().stream().map(this::formatReview).toList();
        recentReviewsListView.setItems(recent.isEmpty()
                ? FXCollections.observableArrayList("No reviews logged yet. Complete a review session to see recent activity.")
                : FXCollections.observableArrayList(recent));

        var skillPerformance = statsService.getSkillPerformance().stream()
                .map(this::formatSkillPerformance)
                .toList();
        skillPerformanceListView.setItems(skillPerformance.isEmpty()
                ? FXCollections.observableArrayList("No cards available yet. Add cards with skill categories to see skill stats.")
                : FXCollections.observableArrayList(skillPerformance));

        var needsPractice = statsService.getNeedsPracticeSkills().stream()
                .map(this::formatNeedsPractice)
                .toList();
        needsPracticeListView.setItems(needsPractice.isEmpty()
                ? FXCollections.observableArrayList("No weak areas flagged. Keep reviewing to build a stronger signal.")
                : FXCollections.observableArrayList(needsPractice));
    }

    private String formatSkillPerformance(StatsSkillPerformance performance) {
        return String.format("%s • %.0f%% accuracy • %d due • recent ratings A:%d H:%d G:%d E:%d",
                performance.skillCategory(),
                performance.accuracyPercent(),
                performance.dueCards(),
                performance.againCount(),
                performance.hardCount(),
                performance.goodCount(),
                performance.easyCount());
    }

    private String formatNeedsPractice(StatsSkillPerformance performance) {
        return String.format("%s • %.0f%% AGAIN/HARD across %d recent reviews • %d due",
                performance.skillCategory(),
                performance.needsPracticeRate(),
                performance.recentReviews(),
                performance.dueCards());
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
}

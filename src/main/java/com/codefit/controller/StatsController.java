package com.codefit.controller;

import com.codefit.model.ReviewHistory;
import com.codefit.model.UserProgress;
import com.codefit.service.StatsService;
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

    private final StatsService statsService = new StatsService();

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
        xpProgressBar.setProgress((progress.getXp() % 100) / 100.0);

        var recent = statsService.getRecentReviews().stream().map(this::formatReview).toList();
        recentReviewsListView.setItems(recent.isEmpty()
                ? FXCollections.observableArrayList("No reviews logged yet. Complete a session to populate combat telemetry.")
                : FXCollections.observableArrayList(recent));
    }

    private String formatReview(ReviewHistory history) {
        return history.getReviewedAt().toLocalDate() + " • Card #" + history.getFlashcardId()
                + " • " + history.getRating() + " • " + history.getPreviousIntervalDays()
                + "d → " + history.getNewIntervalDays() + "d";
    }
}

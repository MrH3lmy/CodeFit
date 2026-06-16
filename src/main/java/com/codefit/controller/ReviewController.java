package com.codefit.controller;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewRating;
import com.codefit.service.ReviewService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class ReviewController extends BaseController {
    @FXML private Label queueLabel;
    @FXML private Label promptLabel;
    @FXML private Label answerLabel;
    @FXML private Label messageLabel;
    @FXML private TextArea attemptTextArea;
    @FXML private Button showAnswerButton;
    @FXML private Button againButton;
    @FXML private Button hardButton;
    @FXML private Button goodButton;
    @FXML private Button easyButton;

    private final ReviewService reviewService = new ReviewService();
    private List<Flashcard> dueCards = new ArrayList<>();
    private int currentIndex;
    private Flashcard currentCard;

    @FXML
    public void initialize() {
        dueCards = new ArrayList<>(reviewService.getDueCards());
        currentIndex = 0;
        showCurrentCard();
    }

    @FXML
    public void showAnswer() {
        if (currentCard == null) {
            return;
        }
        answerLabel.setText(currentCard.getBack());
        setRatingButtonsDisabled(false);
        showAnswerButton.setDisable(true);
    }

    @FXML public void rateAgain() { rate(ReviewRating.AGAIN); }
    @FXML public void rateHard() { rate(ReviewRating.HARD); }
    @FXML public void rateGood() { rate(ReviewRating.GOOD); }
    @FXML public void rateEasy() { rate(ReviewRating.EASY); }

    private void rate(ReviewRating rating) {
        if (currentCard == null) {
            return;
        }
        int previousInterval = currentCard.getIntervalDays();
        LocalDate previousDueDate = currentCard.getDueDate();
        reviewService.review(currentCard, rating);
        setStatus(messageLabel, formatReviewFeedback(rating, previousInterval, previousDueDate, currentCard));
        currentIndex++;
        showCurrentCard();
    }

    private String formatReviewFeedback(ReviewRating rating, int previousInterval, LocalDate previousDueDate, Flashcard reviewedCard) {
        String ratingLabel = rating.name().charAt(0) + rating.name().substring(1).toLowerCase();
        String nextReview = formatNextReview(previousInterval, previousDueDate, reviewedCard);
        return ratingLabel + " logged. +" + rating.getXp() + " XP. Next review " + nextReview + ".";
    }

    private String formatNextReview(int previousInterval, LocalDate previousDueDate, Flashcard reviewedCard) {
        int updatedInterval = reviewedCard.getIntervalDays();
        LocalDate updatedDueDate = reviewedCard.getDueDate();
        if (updatedDueDate != null) {
            long daysUntilDue = ChronoUnit.DAYS.between(LocalDate.now(), updatedDueDate);
            if (daysUntilDue <= 0) {
                return "today";
            }
            return "in " + daysUntilDue + " " + pluralizeDay(daysUntilDue);
        }
        if (updatedInterval > 0) {
            return "in " + updatedInterval + " " + pluralizeDay(updatedInterval);
        }
        if (previousDueDate != null || previousInterval > 0) {
            return "today";
        }
        return "soon";
    }

    private String pluralizeDay(long days) {
        return days == 1 ? "day" : "days";
    }

    private void showCurrentCard() {
        if (dueCards.isEmpty()) {
            currentCard = null;
            queueLabel.setText("0 due");
            promptLabel.setText("No due reviews.");
            answerLabel.setText("Add cards or come back when scheduled reviews mature.");
            attemptTextArea.clear();
            setStatus(messageLabel, "");
            showAnswerButton.setDisable(true);
            setRatingButtonsDisabled(true);
            return;
        }
        if (currentIndex >= dueCards.size()) {
            currentCard = null;
            queueLabel.setText("Complete");
            promptLabel.setText("Review session complete.");
            answerLabel.setText("Great work. Your XP, streak, and schedules are updated.");
            attemptTextArea.clear();
            showAnswerButton.setDisable(true);
            setRatingButtonsDisabled(true);
            return;
        }

        currentCard = dueCards.get(currentIndex);
        queueLabel.setText((currentIndex + 1) + " / " + dueCards.size());
        promptLabel.setText(currentCard.getFront());
        attemptTextArea.clear();
        answerLabel.setText("Answer hidden. Reveal when ready.");
        showAnswerButton.setDisable(false);
        setRatingButtonsDisabled(true);
    }

    private void setRatingButtonsDisabled(boolean disabled) {
        againButton.setDisable(disabled);
        hardButton.setDisable(disabled);
        goodButton.setDisable(disabled);
        easyButton.setDisable(disabled);
    }
}

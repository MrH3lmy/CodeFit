package com.codefit.controller;

import com.codefit.model.Flashcard;
import com.codefit.model.ReviewRating;
import com.codefit.service.ReviewService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import java.util.ArrayList;
import java.util.List;

public class ReviewController extends BaseController {
    @FXML private Label queueLabel;
    @FXML private Label promptLabel;
    @FXML private Label answerLabel;
    @FXML private Label messageLabel;
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
        reviewService.review(currentCard, rating);
        messageLabel.setText(rating.name() + " logged. +" + rating.getXp() + " XP");
        currentIndex++;
        showCurrentCard();
    }

    private void showCurrentCard() {
        if (dueCards.isEmpty()) {
            currentCard = null;
            queueLabel.setText("0 due");
            promptLabel.setText("No due reviews.");
            answerLabel.setText("Add cards or come back when scheduled reviews mature.");
            messageLabel.setText("Queue clear.");
            showAnswerButton.setDisable(true);
            setRatingButtonsDisabled(true);
            return;
        }
        if (currentIndex >= dueCards.size()) {
            currentCard = null;
            queueLabel.setText("Complete");
            promptLabel.setText("Review session complete.");
            answerLabel.setText("Great work. Your XP, streak, and schedules are updated.");
            showAnswerButton.setDisable(true);
            setRatingButtonsDisabled(true);
            return;
        }

        currentCard = dueCards.get(currentIndex);
        queueLabel.setText((currentIndex + 1) + " / " + dueCards.size());
        promptLabel.setText(currentCard.getFront());
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

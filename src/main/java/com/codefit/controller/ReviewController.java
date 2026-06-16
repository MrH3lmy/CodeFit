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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ReviewController extends BaseController {
    @FXML private Label queueLabel;
    @FXML private Label promptLabel;
    @FXML private Label answerLabel;
    @FXML private Label messageLabel;
    @FXML private Label matchRequirementLabel;
    @FXML private Label againDescriptionLabel;
    @FXML private Label hardDescriptionLabel;
    @FXML private Label goodDescriptionLabel;
    @FXML private Label easyDescriptionLabel;
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
    private int reviewedCardCount;
    private int earnedXp;
    private final Map<ReviewRating, Integer> ratingCounts = new EnumMap<>(ReviewRating.class);
    private static final boolean ALLOW_CASE_INSENSITIVE_MATCH = true;
    private AttemptValidationResult latestValidationResult = AttemptValidationResult.EMPTY;
    private boolean answerRevealed;

    @FXML
    public void initialize() {
        dueCards = new ArrayList<>(reviewService.getDueCards());
        currentIndex = 0;
        resetSessionMetrics();
        attemptTextArea.textProperty().addListener((observable, oldValue, newValue) -> updateAttemptValidation());
        showCurrentCard();
    }

    @FXML
    public void showAnswer() {
        if (currentCard == null) {
            return;
        }

        AttemptValidationResult validationResult = validateAttempt();
        if (validationResult == AttemptValidationResult.EMPTY) {
            latestValidationResult = validationResult;
            setStatus(messageLabel, "Enter an attempt before revealing the answer.");
            showAnswerButton.setDisable(true);
            return;
        }

        latestValidationResult = validationResult;
        answerRevealed = true;
        answerLabel.setText(currentCard.getBack());
        setRatingButtonsDisabled(false);
        showAnswerButton.setDisable(true);
        setStatus(messageLabel, formatAttemptFeedback(validationResult));
        setRatingDescriptions(currentCard);
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
        reviewedCardCount++;
        earnedXp += rating.getXp();
        ratingCounts.merge(rating, 1, Integer::sum);
        setStatus(messageLabel, formatReviewFeedback(rating, previousInterval, previousDueDate, currentCard));
        currentIndex++;
        showCurrentCard();
    }

    private void resetSessionMetrics() {
        reviewedCardCount = 0;
        earnedXp = 0;
        ratingCounts.clear();
        for (ReviewRating rating : ReviewRating.values()) {
            ratingCounts.put(rating, 0);
        }
    }

    private String formatSessionSummary() {
        return "Session complete: "
                + reviewedCardCount + " " + pluralize(reviewedCardCount, "card") + " reviewed, "
                + earnedXp + " XP earned, "
                + formatRatingCount(ReviewRating.AGAIN) + " marked Again, "
                + formatRatingCount(ReviewRating.HARD) + " marked Hard, "
                + formatRatingCount(ReviewRating.GOOD) + " marked Good, and "
                + formatRatingCount(ReviewRating.EASY) + " marked Easy.";
    }

    private String formatRatingCount(ReviewRating rating) {
        int count = ratingCounts.getOrDefault(rating, 0);
        return count + " " + pluralize(count, "card");
    }

    private String pluralize(int count, String singular) {
        return count == 1 ? singular : singular + "s";
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
            latestValidationResult = AttemptValidationResult.EMPTY;
            answerRevealed = false;
            setStatus(messageLabel, "");
            matchRequirementLabel.setText("");
            showAnswerButton.setDisable(true);
            setRatingDescriptions(null);
            setRatingButtonsDisabled(true);
            return;
        }
        if (currentIndex >= dueCards.size()) {
            currentCard = null;
            queueLabel.setText("Complete");
            promptLabel.setText("Review session complete.");
            answerLabel.setText("Great work. Your XP, streak, and schedules are updated.");
            attemptTextArea.clear();
            latestValidationResult = AttemptValidationResult.EMPTY;
            answerRevealed = false;
            setStatus(messageLabel, formatSessionSummary());
            matchRequirementLabel.setText("");
            showAnswerButton.setDisable(true);
            setRatingDescriptions(null);
            setRatingButtonsDisabled(true);
            return;
        }

        currentCard = dueCards.get(currentIndex);
        queueLabel.setText((currentIndex + 1) + " / " + dueCards.size());
        promptLabel.setText(currentCard.getFront());
        latestValidationResult = AttemptValidationResult.EMPTY;
        answerRevealed = false;
        matchRequirementLabel.setText(formatMatchRequirement());
        attemptTextArea.clear();
        answerLabel.setText("Answer hidden. Reveal when ready.");
        setRatingDescriptions(currentCard);
        showAnswerButton.setDisable(true);
        setRatingButtonsDisabled(true);
    }

    private void setRatingDescriptions(Flashcard card) {
        ReviewRating recommendedRating = latestValidationResult.recommendedRating();
        againDescriptionLabel.setText(formatRecommendedDescription(ReviewRating.AGAIN, recommendedRating,
                "Missed it — review again today"));
        hardDescriptionLabel.setText(formatRecommendedDescription(ReviewRating.HARD, recommendedRating,
                formatRatingDescription(card, ReviewRating.HARD, "Remembered with effort", "short interval")));
        goodDescriptionLabel.setText(formatRecommendedDescription(ReviewRating.GOOD, recommendedRating,
                formatRatingDescription(card, ReviewRating.GOOD, "Solid recall", "normal interval")));
        easyDescriptionLabel.setText(formatRecommendedDescription(ReviewRating.EASY, recommendedRating,
                formatRatingDescription(card, ReviewRating.EASY, "Instant recall", "longer interval")));
    }

    private String formatRecommendedDescription(ReviewRating rating, ReviewRating recommendedRating, String description) {
        if (rating == recommendedRating) {
            return description + " (recommended)";
        }
        return description;
    }

    private String formatRatingDescription(Flashcard card, ReviewRating rating, String recallDescription, String fallbackInterval) {
        if (card == null) {
            return recallDescription + " — " + fallbackInterval;
        }

        int previewInterval = calculatePreviewInterval(card, rating);
        return recallDescription + " — " + formatInterval(previewInterval);
    }

    private int calculatePreviewInterval(Flashcard card, ReviewRating rating) {
        int interval = card.getIntervalDays();
        double ease = card.getEaseFactor();

        return switch (rating) {
            case AGAIN -> 0;
            case HARD -> Math.max(1, (int) Math.ceil(interval * 1.2));
            case GOOD -> interval == 0 ? 1 : Math.max(1, (int) Math.round(interval * ease));
            case EASY -> interval == 0 ? 4 : Math.max(4, (int) Math.round(interval * ease * 1.3));
        };
    }

    private String formatInterval(int intervalDays) {
        if (intervalDays <= 0) {
            return "review again today";
        }
        return "next review in " + intervalDays + " " + pluralizeDay(intervalDays);
    }


    private void updateAttemptValidation() {
        if (currentCard == null) {
            latestValidationResult = AttemptValidationResult.EMPTY;
            showAnswerButton.setDisable(true);
            return;
        }

        latestValidationResult = validateAttempt();
        showAnswerButton.setDisable(answerRevealed || latestValidationResult == AttemptValidationResult.EMPTY);
        if (latestValidationResult == AttemptValidationResult.EMPTY) {
            setStatus(messageLabel, "Enter an attempt to enable Reveal Answer.");
        } else {
            setStatus(messageLabel, formatAttemptFeedback(latestValidationResult));
        }
    }

    private AttemptValidationResult validateAttempt() {
        String attempt = attemptTextArea.getText() == null ? "" : attemptTextArea.getText().strip();
        String expectedAnswer = currentCard == null || currentCard.getBack() == null ? "" : currentCard.getBack().strip();
        if (attempt.isEmpty()) {
            return AttemptValidationResult.EMPTY;
        }
        if (attempt.equals(expectedAnswer) || (ALLOW_CASE_INSENSITIVE_MATCH && attempt.equalsIgnoreCase(expectedAnswer))) {
            return AttemptValidationResult.EXACT;
        }
        if (normalizeSpacing(attempt).equals(normalizeSpacing(expectedAnswer))
                || (ALLOW_CASE_INSENSITIVE_MATCH && normalizeSpacing(attempt).equalsIgnoreCase(normalizeSpacing(expectedAnswer)))) {
            return AttemptValidationResult.CLOSE_SPACING;
        }
        return AttemptValidationResult.DIFFERENT;
    }

    private String normalizeSpacing(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private String formatAttemptFeedback(AttemptValidationResult result) {
        return switch (result) {
            case EMPTY -> "Enter an attempt to enable Reveal Answer.";
            case EXACT -> "Exact match. Recommended rating: " + result.recommendedRatingLabel() + ".";
            case CLOSE_SPACING -> "Close, check spacing. Recommended rating: " + result.recommendedRatingLabel() + ".";
            case DIFFERENT -> "Different from expected answer. Recommended rating: " + result.recommendedRatingLabel() + ".";
        };
    }

    private String formatMatchRequirement() {
        if (ALLOW_CASE_INSENSITIVE_MATCH) {
            return "Case-insensitive exact matching is accepted for this card.";
        }
        return "Exact capitalization, wording, and spacing are required for this card.";
    }

    private void setRatingButtonsDisabled(boolean disabled) {
        againButton.setDisable(disabled);
        hardButton.setDisable(disabled);
        goodButton.setDisable(disabled);
        easyButton.setDisable(disabled);
    }

    private enum AttemptValidationResult {
        EMPTY(null),
        EXACT(ReviewRating.EASY),
        CLOSE_SPACING(ReviewRating.GOOD),
        DIFFERENT(ReviewRating.AGAIN);

        private final ReviewRating recommendedRating;

        AttemptValidationResult(ReviewRating recommendedRating) {
            this.recommendedRating = recommendedRating;
        }

        private ReviewRating recommendedRating() {
            return recommendedRating;
        }

        private String recommendedRatingLabel() {
            if (recommendedRating == null) {
                return "none";
            }
            return recommendedRating.name().charAt(0) + recommendedRating.name().substring(1).toLowerCase();
        }
    }
}

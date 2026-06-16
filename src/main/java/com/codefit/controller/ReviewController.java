package com.codefit.controller;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;
import com.codefit.model.ReviewRating;
import com.codefit.service.ReviewService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class ReviewController extends BaseController {
    @FXML private Label queueLabel;
    @FXML private Label timerLabel;
    @FXML private Label promptLabel;
    @FXML private Label answerLabel;
    @FXML private Label messageLabel;
    @FXML private Label matchRequirementLabel;
    @FXML private Label againDescriptionLabel;
    @FXML private Label hardDescriptionLabel;
    @FXML private Label goodDescriptionLabel;
    @FXML private Label easyDescriptionLabel;
    @FXML private TextArea attemptTextArea;
    @FXML private VBox commandPracticePanel;
    @FXML private HBox commandAttemptBox;
    @FXML private TextField commandTextField;
    @FXML private TextArea terminalHistoryArea;
    @FXML private Button showAnswerButton;
    @FXML private Button showHintButton;
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
    private AttemptValidationResult latestValidationResult = AttemptValidationResult.EMPTY;
    private boolean answerRevealed;
    private Timeline timeLimitTimeline;
    private int remainingTimeSeconds;
    private boolean submittedInTime = true;
    private boolean hintUsed;

    @FXML
    public void initialize() {
        dueCards = new ArrayList<>(reviewService.getDueCards());
        currentIndex = 0;
        resetSessionMetrics();
        attemptTextArea.textProperty().addListener((observable, oldValue, newValue) -> updateAttemptValidation());
        commandTextField.textProperty().addListener((observable, oldValue, newValue) -> updateAttemptValidation());
        showCurrentCard();
    }

    @FXML
    public void showHint() {
        if (currentCard == null || !currentCard.hasHint() || answerRevealed) {
            return;
        }

        hintUsed = true;
        answerLabel.setText("Hint:\n" + currentCard.getHint().strip());
        showHintButton.setDisable(true);
        setStatus(messageLabel, "Hint shown. If it helped, avoid marking this card Easy.");
        setRatingDescriptions(currentCard);
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
            updateShowHintButton();
            return;
        }

        latestValidationResult = validationResult;
        stopTimeLimitTimeline();
        submittedInTime = !isTimedCardExpired();
        answerRevealed = true;
        answerLabel.setText(formatRevealedAnswer());
        renderTerminalSubmission(validationResult);
        setRatingButtonsDisabled(false);
        showAnswerButton.setDisable(true);
        updateShowHintButton();
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
        stopTimeLimitTimeline();
        reviewService.review(currentCard, rating, submittedInTime);
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
            stopTimeLimitTimeline();
            currentCard = null;
            queueLabel.setText("0 due");
            hideTimer();
            promptLabel.setText("No due reviews.");
            answerLabel.setText("Add cards or come back when scheduled reviews mature.");
            clearAttempts();
            latestValidationResult = AttemptValidationResult.EMPTY;
            answerRevealed = false;
            hintUsed = false;
            setStatus(messageLabel, "");
            matchRequirementLabel.setText("");
            configureAttemptInput();
            showAnswerButton.setDisable(true);
            updateShowHintButton();
            setRatingDescriptions(null);
            setRatingButtonsDisabled(true);
            return;
        }
        if (currentIndex >= dueCards.size()) {
            currentCard = null;
            queueLabel.setText("Complete");
            hideTimer();
            promptLabel.setText("Review session complete.");
            answerLabel.setText("Great work. Your XP, streak, and schedules are updated.");
            clearAttempts();
            latestValidationResult = AttemptValidationResult.EMPTY;
            answerRevealed = false;
            hintUsed = false;
            setStatus(messageLabel, formatSessionSummary());
            matchRequirementLabel.setText("");
            configureAttemptInput();
            showAnswerButton.setDisable(true);
            updateShowHintButton();
            setRatingDescriptions(null);
            setRatingButtonsDisabled(true);
            return;
        }

        currentCard = dueCards.get(currentIndex);
        queueLabel.setText((currentIndex + 1) + " / " + dueCards.size());
        promptLabel.setText(currentCard.getFront());
        latestValidationResult = AttemptValidationResult.EMPTY;
        answerRevealed = false;
        hintUsed = false;
        submittedInTime = true;
        matchRequirementLabel.setText(formatMatchRequirement());
        clearAttempts();
        configureAttemptInput();
        answerLabel.setText(currentCard.hasHint() ? "Answer hidden. Use Show Hint for a clue, or reveal when ready." : "Answer hidden. Reveal when ready.");
        setRatingDescriptions(currentCard);
        showAnswerButton.setDisable(true);
        updateShowHintButton();
        setRatingButtonsDisabled(true);
        startTimeLimitIfNeeded();
    }

    private void startTimeLimitIfNeeded() {
        stopTimeLimitTimeline();
        if (currentCard == null || currentCard.getTimeLimitSeconds() == null || currentCard.getTimeLimitSeconds() <= 0) {
            hideTimer();
            return;
        }
        remainingTimeSeconds = currentCard.getTimeLimitSeconds();
        updateTimerLabel();
        timerLabel.setVisible(true);
        timerLabel.setManaged(true);
        timeLimitTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            remainingTimeSeconds--;
            updateTimerLabel();
            if (remainingTimeSeconds <= 0) {
                handleTimeExpired();
            }
        }));
        timeLimitTimeline.setCycleCount(Timeline.INDEFINITE);
        timeLimitTimeline.play();
    }

    private void handleTimeExpired() {
        stopTimeLimitTimeline();
        submittedInTime = false;
        latestValidationResult = validateAttempt();
        if (latestValidationResult == AttemptValidationResult.EXACT || latestValidationResult == AttemptValidationResult.CLOSE_SPACING) {
            latestValidationResult = AttemptValidationResult.TIMED_OUT_WITH_ATTEMPT;
        } else {
            latestValidationResult = AttemptValidationResult.TIMED_OUT;
        }
        answerRevealed = true;
        attemptTextArea.setDisable(true);
        commandTextField.setDisable(true);
        answerLabel.setText(formatRevealedAnswer());
        renderTerminalSubmission(latestValidationResult);
        showAnswerButton.setDisable(true);
        updateShowHintButton();
        setRatingButtonsDisabled(false);
        setRatingDescriptions(currentCard);
        setStatus(messageLabel, "Time expired. Answer revealed. Recommended rating: " + recommendedRatingLabel(latestValidationResult) + ".");
    }

    private boolean isTimedCardExpired() {
        return currentCard != null && currentCard.getTimeLimitSeconds() != null && remainingTimeSeconds <= 0;
    }

    private void stopTimeLimitTimeline() {
        if (timeLimitTimeline != null) {
            timeLimitTimeline.stop();
            timeLimitTimeline = null;
        }
    }

    private void hideTimer() {
        stopTimeLimitTimeline();
        if (timerLabel != null) {
            timerLabel.setVisible(false);
            timerLabel.setManaged(false);
            timerLabel.setText("");
        }
    }

    private void updateTimerLabel() {
        timerLabel.setText("Time left: " + Math.max(0, remainingTimeSeconds) + "s");
    }

    private void setRatingDescriptions(Flashcard card) {
        ReviewRating recommendedRating = recommendedRating();
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
            updateShowHintButton();
            return;
        }

        latestValidationResult = validateAttempt();
        showAnswerButton.setDisable(answerRevealed || latestValidationResult == AttemptValidationResult.EMPTY);
        updateShowHintButton();
        if (latestValidationResult == AttemptValidationResult.EMPTY) {
            setStatus(messageLabel, "Enter an attempt to enable Reveal Answer.");
        } else {
            setStatus(messageLabel, formatAttemptFeedback(latestValidationResult));
        }
    }

    private AttemptValidationResult validateAttempt() {
        String attempt = getAttemptText();
        if (attempt.isEmpty()) {
            return AttemptValidationResult.EMPTY;
        }
        for (String expectedAnswer : acceptedAnswers()) {
            if (matchesByMode(attempt, expectedAnswer, currentCard.getValidationMode())) {
                return AttemptValidationResult.EXACT;
            }
            if (normalizeSpacing(attempt).equalsIgnoreCase(normalizeSpacing(expectedAnswer))) {
                return AttemptValidationResult.CLOSE_SPACING;
            }
        }
        return AttemptValidationResult.DIFFERENT;
    }

    private String getAttemptText() {
        String attempt = currentCard != null && currentCard.getCardType() == CardType.COMMAND
                ? commandTextField.getText()
                : attemptTextArea.getText();
        return attempt == null ? "" : attempt.strip();
    }

    private List<String> acceptedAnswers() {
        String rawAnswers = currentCard == null || currentCard.getAcceptedAnswers() == null || currentCard.getAcceptedAnswers().isBlank()
                ? currentCard == null ? "" : currentCard.getBack()
                : currentCard.getAcceptedAnswers();
        return rawAnswers.lines()
                .map(String::strip)
                .filter(answer -> !answer.isEmpty())
                .toList();
    }

    private boolean matchesByMode(String attempt, String expectedAnswer, ValidationMode validationMode) {
        return switch (validationMode == null ? ValidationMode.CASE_INSENSITIVE : validationMode) {
            case EXACT -> attempt.equals(expectedAnswer);
            case CASE_INSENSITIVE -> attempt.equalsIgnoreCase(expectedAnswer);
            case NORMALIZED_SPACING -> normalizeSpacing(attempt).equalsIgnoreCase(normalizeSpacing(expectedAnswer));
            case COMMAND_NORMALIZED -> normalizeCommand(attempt).equalsIgnoreCase(normalizeCommand(expectedAnswer));
        };
    }

    private String normalizeCommand(String value) {
        return normalizeSpacing(value).replaceAll("\\s*=\\s*", "=");
    }

    private String formatRevealedAnswer() {
        String answer = currentCard.getBack();
        if (currentCard.getCardType() == CardType.COMMAND && currentCard.getSimulatedOutput() != null
                && !currentCard.getSimulatedOutput().isBlank()) {
            return answer + "\n\nSimulated output:\n" + currentCard.getSimulatedOutput();
        }
        return answer;
    }

    private void configureAttemptInput() {
        boolean command = currentCard != null && currentCard.getCardType() == CardType.COMMAND;
        attemptTextArea.setVisible(!command);
        attemptTextArea.setManaged(!command);
        commandPracticePanel.setVisible(command);
        commandPracticePanel.setManaged(command);
        commandAttemptBox.setVisible(command);
        commandAttemptBox.setManaged(command);
        if (command) {
            resetTerminalHistory();
        }
    }

    private void clearAttempts() {
        attemptTextArea.clear();
        attemptTextArea.setDisable(false);
        commandTextField.clear();
        commandTextField.setDisable(false);
        resetTerminalHistory();
    }

    private String normalizeSpacing(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private void resetTerminalHistory() {
        if (terminalHistoryArea != null) {
            terminalHistoryArea.setText("$ # output history appears after reveal");
        }
    }

    private void renderTerminalSubmission(AttemptValidationResult result) {
        if (currentCard == null || currentCard.getCardType() != CardType.COMMAND || terminalHistoryArea == null) {
            return;
        }

        StringBuilder history = new StringBuilder();
        history.append("$ ").append(getAttemptText()).append("\n");
        if (result == AttemptValidationResult.EXACT || result == AttemptValidationResult.CLOSE_SPACING) {
            history.append(formatSimulatedOutput());
        } else {
            history.append(formatSafeCommandFeedback());
            String expectedOutput = formatSimulatedOutput();
            if (!expectedOutput.isBlank()) {
                history.append("\n\n# Expected simulated output\n").append(expectedOutput);
            }
        }
        terminalHistoryArea.setText(history.toString());
    }

    private String formatSimulatedOutput() {
        if (currentCard == null || currentCard.getSimulatedOutput() == null || currentCard.getSimulatedOutput().isBlank()) {
            return "# No simulated output saved for this card.";
        }
        return currentCard.getSimulatedOutput();
    }

    private String formatSafeCommandFeedback() {
        String attempt = normalizeCommand(getAttemptText()).toLowerCase();
        String expected = acceptedAnswers().stream()
                .map(this::normalizeCommand)
                .map(String::toLowerCase)
                .findFirst()
                .orElse("");

        if (!expected.isBlank() && sharesCommandName(attempt, expected)) {
            return "command accepted but flags are missing";
        }
        if (expected.contains(" -l") || expected.contains(" --all") || expected.contains(" -a")) {
            return "expected long listing output";
        }
        return "command accepted, but output differs from the saved simulation";
    }

    private boolean sharesCommandName(String attempt, String expected) {
        return firstToken(attempt).equals(firstToken(expected));
    }

    private String firstToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.strip().split("\\s+", 2)[0];
    }

    private String formatAttemptFeedback(AttemptValidationResult result) {
        if (result == AttemptValidationResult.TIMED_OUT || result == AttemptValidationResult.TIMED_OUT_WITH_ATTEMPT) {
            return "Time expired. Recommended rating: " + recommendedRatingLabel(result) + ".";
        }
        if (currentCard != null && currentCard.getCardType() == CardType.COMMAND && result == AttemptValidationResult.DIFFERENT) {
            return formatSafeCommandFeedback() + ". Recommended rating: " + recommendedRatingLabel(result) + ".";
        }
        return switch (result) {
            case EMPTY -> "Enter an attempt to enable Reveal Answer.";
            case EXACT -> "Exact match. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case CLOSE_SPACING -> "Close, check spacing. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case DIFFERENT -> "Different from expected answer. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case TIMED_OUT -> "Time expired with no matching attempt. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case TIMED_OUT_WITH_ATTEMPT -> "Time expired after an attempt. Recommended rating: " + recommendedRatingLabel(result) + ".";
        };
    }

    private String formatMatchRequirement() {
        if (currentCard != null && currentCard.getCardType() == CardType.COMMAND) {
            return "Enter a command. Any listed accepted answer is valid (for example, ls -la or ls -al).";
        }
        return switch (currentCard == null || currentCard.getValidationMode() == null ? ValidationMode.CASE_INSENSITIVE : currentCard.getValidationMode()) {
            case EXACT -> "Exact capitalization, wording, and spacing are required for this card.";
            case CASE_INSENSITIVE -> "Case-insensitive exact matching is accepted for this card.";
            case NORMALIZED_SPACING, COMMAND_NORMALIZED -> "Extra spacing is normalized, and case-insensitive alternatives are accepted.";
        };
    }

    private void setRatingButtonsDisabled(boolean disabled) {
        againButton.setDisable(disabled);
        hardButton.setDisable(disabled);
        goodButton.setDisable(disabled);
        easyButton.setDisable(disabled);
    }


    private void updateShowHintButton() {
        if (showHintButton != null) {
            showHintButton.setDisable(currentCard == null || !currentCard.hasHint() || hintUsed || answerRevealed);
        }
    }

    private ReviewRating recommendedRating() {
        ReviewRating rating = latestValidationResult.recommendedRating();
        if (hintUsed && rating == ReviewRating.EASY) {
            return ReviewRating.GOOD;
        }
        return rating;
    }

    private String recommendedRatingLabel(AttemptValidationResult result) {
        ReviewRating rating = result == latestValidationResult ? recommendedRating() : result.recommendedRating();
        if (rating == null) {
            return "none";
        }
        return rating.name().charAt(0) + rating.name().substring(1).toLowerCase();
    }

    private enum AttemptValidationResult {
        EMPTY(null),
        EXACT(ReviewRating.EASY),
        CLOSE_SPACING(ReviewRating.GOOD),
        DIFFERENT(ReviewRating.AGAIN),
        TIMED_OUT(ReviewRating.AGAIN),
        TIMED_OUT_WITH_ATTEMPT(ReviewRating.HARD);

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

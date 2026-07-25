package com.codefit.controller;

import com.codefit.model.CardType;
import com.codefit.model.ConfidenceLevel;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.JavaCardConfig;
import com.codefit.model.ValidationMode;
import com.codefit.model.ReviewAttempt;
import com.codefit.model.ReviewRating;
import com.codefit.model.RegexCardConfig;
import com.codefit.model.RegexMatchMode;
import com.codefit.service.AcceptedAnswerCodec;
import com.codefit.service.AnswerValidator;
import com.codefit.service.CommandValidator;
import com.codefit.service.DeckService;
import com.codefit.service.JavaExerciseCodec;
import com.codefit.service.JavaSandboxRunner;
import com.codefit.service.RegexCardCodec;
import com.codefit.service.RegexCardValidator;
import com.codefit.service.RatingGuardrail;
import com.codefit.service.ReviewService;
import com.codefit.service.SqlCardValidator;
import com.codefit.service.SessionQueue;
import com.codefit.service.SessionBudgetService;
import com.codefit.service.SystemMessageService;
import com.codefit.ui.NavigationService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ReviewController extends BaseController {
    @FXML private ScrollPane reviewRoot;
    @FXML private Label queueLabel;
    @FXML private Label sessionTimeLabel;
    @FXML private Label timerLabel;
    @FXML private Label categoryLabel;
    @FXML private Label promptLabel;
    @FXML private Label answerLabel;
    @FXML private Label messageLabel;
    @FXML private Label matchRequirementLabel;
    @FXML private VBox againOptionBox;
    @FXML private VBox hardOptionBox;
    @FXML private VBox goodOptionBox;
    @FXML private VBox easyOptionBox;
    @FXML private Label againDescriptionLabel;
    @FXML private Label hardDescriptionLabel;
    @FXML private Label goodDescriptionLabel;
    @FXML private Label easyDescriptionLabel;
    @FXML private ProgressBar sessionProgressBar;
    @FXML private TextArea attemptTextArea;
    @FXML private VBox attemptSection;
    @FXML private VBox confidencePanel;
    @FXML private VBox commandPracticePanel;
    @FXML private VBox reflectionPromptPanel;
    @FXML private HBox preRevealActions;
    @FXML private VBox answerSection;
    @FXML private Label answerSectionHeading;
    @FXML private VBox ratingPanel;
    @FXML private VBox completionStatStrip;
    @FXML private Label completionCardsLabel;
    @FXML private Label completionTimeLabel;
    @FXML private Label completionAccuracyLabel;
    @FXML private Label completionMissedLabel;
    @FXML private HBox commandAttemptBox;
    @FXML private TextField commandTextField;
    @FXML private TextArea terminalHistoryArea;
    @FXML private Button showAnswerButton;
    @FXML private Button showHintButton;
    @FXML private Button againButton;
    @FXML private Button hardButton;
    @FXML private Button goodButton;
    @FXML private Button easyButton;
    @FXML private Button reviewMissedButton;
    @FXML private ToggleGroup confidenceToggleGroup;
    @FXML private ToggleButton confidenceLowButton;
    @FXML private ToggleButton confidenceMediumButton;
    @FXML private ToggleButton confidenceHighButton;
    @FXML private Button addFixedBugButton;
    @FXML private Button addSearchedCommandButton;
    @FXML private Button addMissedConceptButton;
    @FXML private Button emptyStateActionButton;

    private static final int AGAIN_REQUEUE_OFFSET = 3;
    private static final int HARD_REQUEUE_OFFSET = 6;
    private static final int SAME_SESSION_RETRY_LIMIT = 3;

    private final ReviewService reviewService = new ReviewService();
    private final DeckService deckService = new DeckService();
    private final SystemMessageService systemMessageService = new SystemMessageService();
    private final JavaSandboxRunner javaSandboxRunner = new JavaSandboxRunner();
    private JavaSandboxRunner.ExecutionResult lastJavaExecutionResult;
    private SessionQueue sessionQueue = new SessionQueue(new ArrayList<>(), SAME_SESSION_RETRY_LIMIT);
    private Flashcard currentCard;
    private int reviewedCardCount;
    private int earnedXp;
    private int initialMissCount;
    private int recoveredMissCount;
    private Integer sessionBudgetMinutes;
    private int sessionElapsedSeconds;
    private Map<String, Integer> sessionComposition = Map.of();
    private final Map<ReviewRating, Integer> ratingCounts = new EnumMap<>(ReviewRating.class);
    private final List<Flashcard> missedCards = new ArrayList<>();
    private AttemptValidationResult latestValidationResult = AttemptValidationResult.EMPTY;
    private SqlCardValidator.GradingResult latestSqlGradingResult;
    private boolean answerRevealed;
    private Timeline timeLimitTimeline;
    private int remainingTimeSeconds;
    private boolean submittedInTime = true;
    private boolean hintUsed;
    private boolean weeklyBossMode;
    private final String sessionId = UUID.randomUUID().toString();
    private long cardShownAtMillis;
    private Integer lastResponseTimeMs;

    @FXML
    public void initialize() {
        weeklyBossMode = NavigationService.consumeWeeklyBossModeRequest();
        sessionBudgetMinutes = weeklyBossMode ? null : NavigationService.consumeSessionMinutesRequest();

        List<Flashcard> initialCards;
        if (weeklyBossMode) {
            initialCards = reviewService.getWeeklyBossCards();
        } else if (sessionBudgetMinutes != null) {
            ReviewService.AdaptiveSessionPlan plan = reviewService.getAdaptiveSessionCards(sessionBudgetMinutes);
            initialCards = plan.cards();
            sessionComposition = plan.composition();
        } else {
            initialCards = reviewService.getDueCards();
        }

        sessionQueue = new SessionQueue(initialCards, SAME_SESSION_RETRY_LIMIT);
        resetSessionMetrics();
        updateSessionTimeLabel();
        attemptTextArea.textProperty().addListener((observable, oldValue, newValue) -> updateAttemptValidation());
        commandTextField.textProperty().addListener((observable, oldValue, newValue) -> updateAttemptValidation());
        configureKeyboardShortcuts();
        showCurrentCard();
        Platform.runLater(this::focusActiveAttemptInput);
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
        focusActiveAttemptInput();
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
            updateRevealVisibility();
            return;
        }

        latestValidationResult = validationResult;
        stopTimeLimitTimeline();
        submittedInTime = !isTimedCardExpired();
        lastResponseTimeMs = computeResponseTimeMs();
        if (currentCard.getCardType() == CardType.JAVA_CODE && validationResult == AttemptValidationResult.JAVA_PENDING) {
            latestValidationResult = runJavaSandboxAndGrade(getAttemptText());
        }
        answerRevealed = true;
        answerLabel.setText(formatRevealedAnswer());
        renderTerminalSubmission(validationResult);
        updateRatingButtonAvailability();
        showAnswerButton.setDisable(true);
        updateShowHintButton();
        setStatus(messageLabel, formatAttemptFeedback(validationResult));
        setRatingDescriptions(currentCard);
        updateRevealVisibility();
        focusRecommendedRatingButton();
    }

    @FXML
    public void reviewMissedNow() {
        if (missedCards.isEmpty()) {
            return;
        }

        sessionQueue = new SessionQueue(new ArrayList<>(missedCards), SAME_SESSION_RETRY_LIMIT);
        resetSessionMetrics();
        updateSessionTimeLabel();
        if (reviewMissedButton != null) {
            reviewMissedButton.setVisible(false);
            reviewMissedButton.setManaged(false);
            reviewMissedButton.setDisable(true);
        }
        showCurrentCard();
    }

    @FXML public void rateAgain() { rate(ReviewRating.AGAIN); }
    @FXML public void rateHard() { rate(ReviewRating.HARD); }
    @FXML public void rateGood() { rate(ReviewRating.GOOD); }
    @FXML public void rateEasy() { rate(ReviewRating.EASY); }

    @FXML public void addFixedBugReflection() {
        NavigationService.showAddCardReflection("bug");
    }

    @FXML public void addSearchedCommandReflection() {
        NavigationService.showAddCardReflection("command");
    }

    @FXML public void addMissedConceptReflection() {
        NavigationService.showAddCardReflection("concept");
    }

    private void rate(ReviewRating rating) {
        if (currentCard == null || !answerRevealed) {
            return;
        }
        if (!allowedRatings().contains(rating)) {
            setStatus(messageLabel, ratingBlockedReason(rating));
            return;
        }
        int previousInterval = currentCard.getIntervalDays();
        LocalDate previousDueDate = currentCard.getDueDate();
        stopTimeLimitTimeline();
        Flashcard reviewedCard = currentCard;
        ReviewAttempt attempt = new ReviewAttempt(latestValidationResult.name(), getAttemptText(),
                lastResponseTimeMs, hintUsed, sessionId, selectedConfidence());
        if (weeklyBossMode) {
            reviewService.reviewBossBattle(reviewedCard, rating, submittedInTime, attempt);
        } else {
            reviewService.review(reviewedCard, rating, submittedInTime, attempt);
        }
        reviewedCardCount++;
        earnedXp += rating.getXp();
        sessionElapsedSeconds += (lastResponseTimeMs == null ? 15_000 : lastResponseTimeMs) / 1000 + 5;
        updateSessionTimeLabel();
        ratingCounts.merge(rating, 1, Integer::sum);
        String feedback = formatReviewFeedback(rating, previousInterval, previousDueDate, reviewedCard);
        if (rating == ReviewRating.AGAIN || rating == ReviewRating.HARD) {
            feedback = feedback + " " + registerMissAndRequeue(reviewedCard, rating);
        } else {
            registerRecovery(reviewedCard);
        }
        setStatus(messageLabel, feedback);
        showCurrentCard();
    }

    private String registerMissAndRequeue(Flashcard reviewedCard, ReviewRating rating) {
        boolean wasAlreadyMissed = missedCards.stream().anyMatch(card -> card.getId() == reviewedCard.getId());
        if (!wasAlreadyMissed) {
            missedCards.add(reviewedCard);
            initialMissCount++;
        }
        int offset = rating == ReviewRating.AGAIN ? AGAIN_REQUEUE_OFFSET : HARD_REQUEUE_OFFSET;
        boolean requeued = sessionQueue.requeue(reviewedCard, offset);
        return requeued
                ? "It will come back for another try in a few cards."
                : "Retry limit reached for this session — it stays in relearning and returns on its next scheduled review.";
    }

    private void registerRecovery(Flashcard reviewedCard) {
        boolean recovered = missedCards.removeIf(card -> card.getId() == reviewedCard.getId());
        if (recovered) {
            recoveredMissCount++;
        }
    }

    private void resetSessionMetrics() {
        reviewedCardCount = 0;
        earnedXp = 0;
        initialMissCount = 0;
        recoveredMissCount = 0;
        sessionElapsedSeconds = 0;
        ratingCounts.clear();
        missedCards.clear();
        for (ReviewRating rating : ReviewRating.values()) {
            ratingCounts.put(rating, 0);
        }
    }

    private void updateSessionTimeLabel() {
        if (sessionTimeLabel == null) {
            return;
        }
        if (sessionBudgetMinutes == null) {
            sessionTimeLabel.setVisible(false);
            sessionTimeLabel.setManaged(false);
            return;
        }
        int remainingMinutes = Math.max(0, sessionBudgetMinutes - (sessionElapsedSeconds / 60));
        sessionTimeLabel.setText("~" + remainingMinutes + " of " + sessionBudgetMinutes + " min remaining (est.)");
        sessionTimeLabel.setVisible(true);
        sessionTimeLabel.setManaged(true);
    }

    private String formatSessionSummary() {
        StringBuilder summary = new StringBuilder(weeklyBossMode ? "Weekly boss battle complete: " : "Session complete: ")
                .append(reviewedCardCount).append(" ").append(pluralize(reviewedCardCount, "card")).append(" reviewed, ")
                .append(earnedXp).append(" XP earned, ")
                .append(formatRatingCount(ReviewRating.AGAIN)).append(" marked Again, ")
                .append(formatRatingCount(ReviewRating.HARD)).append(" marked Hard, ")
                .append(formatRatingCount(ReviewRating.GOOD)).append(" marked Good, and ")
                .append(formatRatingCount(ReviewRating.EASY)).append(" marked Easy.");

        summary.append("\n").append(systemMessageService.formatSessionCompletionMessage(
                reviewedCardCount, earnedXp, missedCards.size(), weeklyBossMode));

        if (initialMissCount > 0) {
            summary.append("\nRelearning: ").append(initialMissCount).append(" initial ")
                    .append(pluralize(initialMissCount, "miss")).append(", ")
                    .append(recoveredMissCount).append(" recovered this session, ")
                    .append(missedCards.size()).append(" still outstanding.");
        }

        if (sessionBudgetMinutes != null) {
            summary.append("\nTimed session: ").append(sessionBudgetMinutes).append(" min budget, ~")
                    .append(Math.round(sessionElapsedSeconds / 60.0)).append(" min used.");
        }

        String queueComposition = formatQueueComposition();
        if (!queueComposition.isBlank()) {
            summary.append("\n").append(queueComposition);
        }

        String weakAreaSignal = formatWeakAreaSignal();
        if (!weakAreaSignal.isBlank()) {
            summary.append("\n").append(weakAreaSignal);
        }

        String missedCardGroups = formatMissedCardGroups();
        if (!missedCardGroups.isBlank()) {
            summary.append("\n").append(missedCardGroups);
        }

        summary.append("\nReflection prompt: add one card from today’s real work while it is still fresh. Pick a bug you fixed, a command you searched, or a concept you missed.");
        summary.append("\nNext action: ").append(formatSuggestedNextAction());
        return summary.toString();
    }

    private String formatQueueComposition() {
        if (sessionComposition.isEmpty()) {
            return "";
        }
        return "Queue mix: " + sessionComposition.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", ")) + ".";
    }

    private String formatWeakAreaSignal() {
        int againCount = ratingCounts.getOrDefault(ReviewRating.AGAIN, 0);
        int hardCount = ratingCounts.getOrDefault(ReviewRating.HARD, 0);
        int weakCount = againCount + hardCount;
        if (weakCount == 0) {
            return "Weak-area signal: none today — keep the streak going.";
        }
        String intensity = againCount > hardCount ? "missed recall" : hardCount > againCount ? "effortful recall" : "mixed missed and effortful recall";
        return "Weak-area signal: " + weakCount + " " + pluralize(weakCount, "card") + " need reinforcement ("
                + againCount + " Again, " + hardCount + " Hard), pointing to " + intensity + ".";
    }

    private String formatMissedCardGroups() {
        if (missedCards.isEmpty()) {
            return "";
        }

        Map<String, Long> groupedMisses = missedCards.stream()
                .collect(Collectors.groupingBy(this::missedCardGroupLabel, LinkedHashMap::new, Collectors.counting()));

        return "Missed-card focus: " + groupedMisses.entrySet().stream()
                .map(entry -> entry.getKey() + " (" + entry.getValue() + ")")
                .collect(Collectors.joining(", ")) + ".";
    }

    private String missedCardGroupLabel(Flashcard card) {
        if (card.getCardType().isCommandTemplate()) {
            return commandSkillLabel(card) + " / " + commandFamily(card);
        }
        return deckName(card.getDeckId()) + " / " + card.getCardType();
    }

    private String commandSkillLabel(Flashcard card) {
        return switch (card.getCardType()) {
            case LINUX_COMMAND -> "Linux commands";
            case GIT_COMMAND -> "Git commands";
            default -> "Commands";
        };
    }

    private String commandFamily(Flashcard card) {
        String rawAnswers = card.getAcceptedAnswers() == null || card.getAcceptedAnswers().isBlank() ? card.getBack() : card.getAcceptedAnswers();
        String command = AcceptedAnswerCodec.decode(rawAnswers).stream()
                .map(this::firstToken)
                .filter(token -> !token.isBlank())
                .findFirst()
                .orElse("syntax");
        return command + " family";
    }

    private String deckName(long deckId) {
        return deckService.getDecks().stream()
                .filter(deck -> deck.getId() == deckId)
                .map(Deck::getName)
                .findFirst()
                .orElse("Deck " + deckId);
    }

    private String commandPracticeFocus(Flashcard card) {
        String rawAnswers = card.getAcceptedAnswers() == null || card.getAcceptedAnswers().isBlank() ? card.getBack() : card.getAcceptedAnswers();
        String answers = String.join(" ", AcceptedAnswerCodec.decode(rawAnswers));
        String skill = commandSkillLabel(card).toLowerCase();
        if (answers.contains(" -") || answers.contains("--")) {
            return skill.replace(" commands", " flags");
        }
        return skill + " in the " + commandFamily(card).replace(" family", "") + " family";
    }

    private String formatSuggestedNextAction() {
        if (missedCards.isEmpty()) {
            return "Add a stretch card or start another review when new cards are due.";
        }

        long missedCommands = missedCards.stream().filter(card -> card.getCardType().isCommandTemplate()).count();
        if (missedCommands > 0) {
            Flashcard firstCommandMiss = missedCards.stream()
                    .filter(card -> card.getCardType().isCommandTemplate())
                    .findFirst()
                    .orElse(missedCards.get(0));
            return "Practice " + commandPracticeFocus(firstCommandMiss) + " again, then add 3 cards for commands you missed.";
        }

        return "Review missed cards now, then add 3 cards for the weakest deck.";
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
        if (!sessionQueue.hasNext() && reviewedCardCount == 0) {
            stopTimeLimitTimeline();
            currentCard = null;
            queueLabel.setText(weeklyBossMode ? "Boss unavailable" : "0 due");
            hideTimer();
            setCategoryText(null);
            promptLabel.setText(weeklyBossMode ? "Weekly boss battle is not available yet." : "You're all caught up — no cards are due.");
            answerLabel.setText(weeklyBossMode ? "Complete normal reviews or check back next week for the next mixed assessment." : "Nice work keeping your queue clear. Next action: add a new card if you want more practice today.");
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
            updateRatingButtonAvailability();
            updateReviewMissedButton(false);
            updateReflectionActions(false);
            updateEmptyStateAction(true, "Add a Card", this::goAddCard);
            updateCompletionStats(false);
            updateSessionProgressBar(false, 0);
            updateRevealVisibility();
            return;
        }
        if (!sessionQueue.hasNext()) {
            currentCard = null;
            queueLabel.setText("Complete");
            hideTimer();
            setCategoryText(null);
            promptLabel.setText("Review session complete — great work!");
            answerLabel.setText("Your XP, streak, and schedules are updated. Next action: review missed cards now if any were flagged.");
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
            updateRatingButtonAvailability();
            updateReviewMissedButton(!missedCards.isEmpty());
            updateReflectionActions(true);
            updateEmptyStateAction(false, "View Stats", this::goStats);
            updateCompletionStats(true);
            updateSessionProgressBar(true, 1.0);
            updateRevealVisibility();
            return;
        }

        updateReviewMissedButton(false);
        updateReflectionActions(false);
        updateEmptyStateAction(false, "", this::goDashboard);
        updateCompletionStats(false);
        currentCard = sessionQueue.poll();
        int totalSoFar = reviewedCardCount + 1 + sessionQueue.remainingSize();
        queueLabel.setText((weeklyBossMode ? "Boss " : "") + (reviewedCardCount + 1) + " / " + totalSoFar);
        updateSessionProgressBar(true, totalSoFar == 0 ? 0 : (double) reviewedCardCount / totalSoFar);
        setCategoryText(currentCard.getSkillCategory());
        promptLabel.setText(currentCard.getFront());
        latestValidationResult = AttemptValidationResult.EMPTY;
        lastJavaExecutionResult = null;
        answerRevealed = false;
        hintUsed = false;
        submittedInTime = true;
        cardShownAtMillis = System.currentTimeMillis();
        lastResponseTimeMs = null;
        resetConfidenceSelection();
        matchRequirementLabel.setText(formatMatchRequirement());
        clearAttempts();
        configureAttemptInput();
        answerLabel.setText(currentCard.hasHint() ? "Answer hidden. Use Show Hint for a clue, or reveal when ready." : "Answer hidden. Reveal when ready.");
        setRatingDescriptions(currentCard);
        showAnswerButton.setDisable(true);
        updateShowHintButton();
        updateRatingButtonAvailability();
        startTimeLimitIfNeeded();
        updateRevealVisibility();
        Platform.runLater(this::focusActiveAttemptInput);
    }

    private void setCategoryText(String category) {
        if (categoryLabel == null) {
            return;
        }
        boolean hasCategory = category != null && !category.isBlank();
        categoryLabel.setText(hasCategory ? category : "");
        categoryLabel.setVisible(hasCategory);
        categoryLabel.setManaged(hasCategory);
    }

    private void updateSessionProgressBar(boolean visible, double progress) {
        if (sessionProgressBar == null) {
            return;
        }
        sessionProgressBar.setVisible(visible);
        sessionProgressBar.setManaged(visible);
        sessionProgressBar.setProgress(Math.max(0, Math.min(1, progress)));
    }

    private void updateCompletionStats(boolean visible) {
        if (completionStatStrip == null) {
            return;
        }
        completionStatStrip.setVisible(visible);
        completionStatStrip.setManaged(visible);
        if (!visible) {
            return;
        }

        completionCardsLabel.setText(reviewedCardCount + " " + pluralize(reviewedCardCount, "card"));
        int minutesSpent = (int) Math.round(sessionElapsedSeconds / 60.0);
        completionTimeLabel.setText(sessionElapsedSeconds < 30 ? "< 1 min" : minutesSpent + " " + pluralize(minutesSpent, "min"));

        int good = ratingCounts.getOrDefault(ReviewRating.GOOD, 0);
        int easy = ratingCounts.getOrDefault(ReviewRating.EASY, 0);
        int totalRated = ratingCounts.values().stream().mapToInt(Integer::intValue).sum();
        completionAccuracyLabel.setText(totalRated == 0 ? "No signal" : Math.round(100.0 * (good + easy) / totalRated) + "% Good/Easy");

        completionMissedLabel.setText(missedCards.isEmpty() ? "None" : missedCards.size() + " outstanding");
    }

    private void updateReflectionActions(boolean visible) {
        if (reflectionPromptPanel != null) {
            reflectionPromptPanel.setVisible(visible);
            reflectionPromptPanel.setManaged(visible);
        }
        setReflectionActionVisible(addFixedBugButton, visible);
        setReflectionActionVisible(addSearchedCommandButton, visible);
        setReflectionActionVisible(addMissedConceptButton, visible);
    }

    private void setReflectionActionVisible(Button button, boolean visible) {
        if (button == null) {
            return;
        }
        button.setVisible(visible);
        button.setManaged(visible);
        button.setDisable(!visible);
    }

    private void updateEmptyStateAction(boolean visible, String text, Runnable action) {
        if (emptyStateActionButton == null) {
            return;
        }
        emptyStateActionButton.setVisible(visible);
        emptyStateActionButton.setManaged(visible);
        emptyStateActionButton.setText(text);
        emptyStateActionButton.setOnAction(event -> action.run());
    }

    private void configureKeyboardShortcuts() {
        if (reviewRoot == null) {
            return;
        }

        reviewRoot.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isConsumed()) {
                return;
            }
            KeyCode code = event.getCode();
            if (code == KeyCode.H && !isFocusInTextInput()) {
                fireIfAvailable(showHintButton);
                event.consume();
            } else if (code == KeyCode.SPACE && !isFocusInTextInput()) {
                fireIfAvailable(showAnswerButton);
                event.consume();
            } else if (answerRevealed && code == KeyCode.DIGIT1) {
                fireIfAvailable(againButton);
                event.consume();
            } else if (answerRevealed && code == KeyCode.DIGIT2) {
                fireIfAvailable(hardButton);
                event.consume();
            } else if (answerRevealed && code == KeyCode.DIGIT3) {
                fireIfAvailable(goodButton);
                event.consume();
            } else if (answerRevealed && code == KeyCode.DIGIT4) {
                fireIfAvailable(easyButton);
                event.consume();
            }
        });

        commandTextField.setOnAction(event -> focusRevealButtonIfReady());
        attemptTextArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && event.isControlDown()) {
                focusRevealButtonIfReady();
                event.consume();
            }
        });
    }

    private void fireIfAvailable(Button button) {
        if (button != null && button.isVisible() && !button.isDisabled()) {
            button.fire();
        }
    }

    private boolean isFocusInTextInput() {
        Node focusedNode = reviewRoot.getScene() == null ? null : reviewRoot.getScene().getFocusOwner();
        return focusedNode == attemptTextArea || focusedNode == commandTextField;
    }

    private void focusRevealButtonIfReady() {
        if (showAnswerButton != null && !showAnswerButton.isDisabled()) {
            showAnswerButton.requestFocus();
        }
    }

    private void focusActiveAttemptInput() {
        if (currentCard == null || answerRevealed) {
            return;
        }
        if (currentCard.getCardType().isCommandTemplate()) {
            commandTextField.requestFocus();
        } else {
            attemptTextArea.requestFocus();
        }
    }

    private void focusRecommendedRatingButton() {
        ReviewRating rating = recommendedRating();
        Button target = goodButton;
        if (rating == ReviewRating.AGAIN) {
            target = againButton;
        } else if (rating == ReviewRating.HARD) {
            target = hardButton;
        } else if (rating == ReviewRating.EASY) {
            target = easyButton;
        }
        if (target != null && !target.isDisabled()) {
            target.requestFocus();
        }
    }

    /** Keeps the pre-reveal (attempt/hint/reveal) and post-reveal (answer/rating) layouts
     *  mutually exclusive, and locks confidence and the attempt input once revealed. */
    private void updateRevealVisibility() {
        boolean hasCard = currentCard != null;
        boolean showPreReveal = hasCard && !answerRevealed;
        boolean showAnswerArea = answerRevealed || !hasCard;

        if (attemptSection != null) {
            attemptSection.setVisible(hasCard);
            attemptSection.setManaged(hasCard);
        }
        if (confidencePanel != null) {
            confidencePanel.setVisible(hasCard);
            confidencePanel.setManaged(hasCard);
        }
        if (preRevealActions != null) {
            preRevealActions.setVisible(showPreReveal);
            preRevealActions.setManaged(showPreReveal);
        }
        if (answerSection != null) {
            answerSection.setVisible(showAnswerArea);
            answerSection.setManaged(showAnswerArea);
        }
        if (answerSectionHeading != null) {
            answerSectionHeading.setText(hasCard ? "EXPECTED ANSWER" : "SESSION SUMMARY");
        }
        if (ratingPanel != null) {
            ratingPanel.setVisible(answerRevealed);
            ratingPanel.setManaged(answerRevealed);
        }
        setConfidenceLocked(answerRevealed);

        // Lock the attempt once revealed: editing it afterward would silently desync the
        // rating-guardrail check (re-evaluated from the live attempt) from the buttons shown here.
        if (hasCard) {
            attemptTextArea.setDisable(answerRevealed);
            commandTextField.setDisable(answerRevealed);
        }
    }

    private void setConfidenceLocked(boolean locked) {
        if (confidenceLowButton != null) {
            confidenceLowButton.setDisable(locked);
        }
        if (confidenceMediumButton != null) {
            confidenceMediumButton.setDisable(locked);
        }
        if (confidenceHighButton != null) {
            confidenceHighButton.setDisable(locked);
        }
    }

    @FXML
    public void exitReview() {
        goDashboard();
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
        lastResponseTimeMs = computeResponseTimeMs();
        latestValidationResult = validateAttempt();
        if (latestValidationResult == AttemptValidationResult.SUBJECTIVE) {
            // Concept cards are never given a time limit today, but guard against
            // misclassifying a subjective attempt as an objective timeout if that changes.
        } else if (latestValidationResult == AttemptValidationResult.EXACT || latestValidationResult == AttemptValidationResult.CLOSE_SPACING) {
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
        updateRatingButtonAvailability();
        setRatingDescriptions(currentCard);
        setStatus(messageLabel, "Time expired. Answer revealed. Recommended rating: " + recommendedRatingLabel(latestValidationResult) + ".");
        updateRevealVisibility();
        focusRecommendedRatingButton();
    }

    private boolean isTimedCardExpired() {
        return currentCard != null && currentCard.getTimeLimitSeconds() != null && remainingTimeSeconds <= 0;
    }

    private Integer computeResponseTimeMs() {
        if (cardShownAtMillis <= 0) {
            return null;
        }
        return (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - cardShownAtMillis);
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
        updateRatingOption(againOptionBox, againDescriptionLabel, ReviewRating.AGAIN, recommendedRating, formatInterval(0));
        updateRatingOption(hardOptionBox, hardDescriptionLabel, ReviewRating.HARD, recommendedRating, formatRatingInterval(card, ReviewRating.HARD));
        updateRatingOption(goodOptionBox, goodDescriptionLabel, ReviewRating.GOOD, recommendedRating, formatRatingInterval(card, ReviewRating.GOOD));
        updateRatingOption(easyOptionBox, easyDescriptionLabel, ReviewRating.EASY, recommendedRating, formatRatingInterval(card, ReviewRating.EASY));
    }

    private void updateRatingOption(VBox optionBox, Label descriptionLabel, ReviewRating rating, ReviewRating recommendedRating, String intervalText) {
        descriptionLabel.setText(intervalText);
        if (optionBox == null) {
            return;
        }
        boolean recommended = rating == recommendedRating;
        optionBox.getStyleClass().remove("rating-option-recommended");
        if (recommended) {
            optionBox.getStyleClass().add("rating-option-recommended");
        }
    }

    private String formatRatingInterval(Flashcard card, ReviewRating rating) {
        if (card == null) {
            return "";
        }
        return formatInterval(calculatePreviewInterval(card, rating));
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
            return "Today";
        }
        return "In " + intervalDays + " " + pluralizeDay(intervalDays);
    }


    private void updateAttemptValidation() {
        if (currentCard == null) {
            latestValidationResult = AttemptValidationResult.EMPTY;
            showAnswerButton.setDisable(true);
            updateShowHintButton();
            updateRevealVisibility();
            return;
        }

        latestValidationResult = validateAttempt();
        showAnswerButton.setDisable(answerRevealed || latestValidationResult == AttemptValidationResult.EMPTY);
        updateShowHintButton();
        updateRevealVisibility();
        if (latestValidationResult == AttemptValidationResult.EMPTY) {
            setStatus(messageLabel, "Enter an attempt to enable Reveal Answer.");
        } else {
            setStatus(messageLabel, formatAttemptFeedback(latestValidationResult));
        }
    }

    private AttemptValidationResult validateAttempt() {
        if (currentCard.getCardType() == CardType.SQL_QUERY) {
            return validateSqlAttempt();
        }
        AnswerValidator.Outcome outcome = AnswerValidator.validateForCardType(currentCard.getCardType(),
                getAttemptText(), acceptedAnswers(), currentCard.getValidationMode());
        return switch (outcome) {
            case EMPTY -> AttemptValidationResult.EMPTY;
            case EXACT -> AttemptValidationResult.EXACT;
            case CLOSE_SPACING -> AttemptValidationResult.CLOSE_SPACING;
            case DIFFERENT -> AttemptValidationResult.DIFFERENT;
            case SUBJECTIVE -> AttemptValidationResult.SUBJECTIVE;
            case JAVA_PENDING -> AttemptValidationResult.JAVA_PENDING;
        };
    }

    /**
     * Compiles and runs the learner's Java attempt in {@link JavaSandboxRunner} exactly once, when
     * the answer is revealed — never per keystroke, since that would spawn a JVM on every edit.
     * Falls back to a single generic result if the card's exercise config can't be decoded so a
     * malformed card never crashes the review screen.
     */
    private AttemptValidationResult runJavaSandboxAndGrade(String attempt) {
        JavaCardConfig config;
        try {
            config = JavaExerciseCodec.decode(currentCard.getAcceptedAnswers());
        } catch (RuntimeException exception) {
            lastJavaExecutionResult = new JavaSandboxRunner.ExecutionResult(JavaSandboxRunner.Outcome.COMPILE_ERROR,
                    "", "", "", false, "This card's exercise data is malformed and could not be decoded.");
            return AttemptValidationResult.JAVA_COMPILE_ERROR;
        }

        JavaSandboxRunner.Expectation expectation =
                new JavaSandboxRunner.Expectation(config.expectedOutput(), config.expectedExceptionSimpleName());
        JavaSandboxRunner.Limits limits = new JavaSandboxRunner.Limits(config.timeoutSeconds(), config.memoryLimitMb(),
                JavaSandboxRunner.Limits.defaults().maxOutputBytes());
        lastJavaExecutionResult = javaSandboxRunner.run(config.assembleSource(attempt), JavaCardConfig.MAIN_CLASS_NAME,
                expectation, limits);

        return switch (lastJavaExecutionResult.outcome()) {
            case CORRECT -> AttemptValidationResult.JAVA_CORRECT;
            case WRONG_OUTPUT -> AttemptValidationResult.JAVA_WRONG_OUTPUT;
            case COMPILE_ERROR -> AttemptValidationResult.JAVA_COMPILE_ERROR;
            case TIMEOUT -> AttemptValidationResult.JAVA_TIMEOUT;
            case UNEXPECTED_EXCEPTION -> AttemptValidationResult.JAVA_UNEXPECTED_EXCEPTION;
            case MISSING_EXPECTED_EXCEPTION -> AttemptValidationResult.JAVA_MISSING_EXPECTED_EXCEPTION;
            case REJECTED_UNSAFE_SNIPPET -> AttemptValidationResult.JAVA_REJECTED_UNSAFE_SNIPPET;
            case SANDBOX_UNAVAILABLE -> AttemptValidationResult.JAVA_SANDBOX_UNAVAILABLE;
        };
    }

    /**
     * SQL_QUERY cards are graded by executing the attempt against an isolated fixture (see
     * {@link SqlCardValidator}) rather than text-matching a saved answer, so any accepted outcome
     * other than EMPTY collapses to EXACT/DIFFERENT here; the grading detail is kept in
     * {@link #latestSqlGradingResult} for {@link #formatSafeSqlFeedback()}.
     */
    private AttemptValidationResult validateSqlAttempt() {
        latestSqlGradingResult = SqlCardValidator.grade(getAttemptText(), currentCard.getAcceptedAnswers());
        if (latestSqlGradingResult.outcome() == SqlCardValidator.Outcome.EMPTY) {
            return AttemptValidationResult.EMPTY;
        }
        return latestSqlGradingResult.isCorrect() ? AttemptValidationResult.EXACT : AttemptValidationResult.DIFFERENT;
    }

    private String getAttemptText() {
        String attempt = currentCard != null && currentCard.getCardType().isCommandTemplate()
                ? commandTextField.getText()
                : attemptTextArea.getText();
        return attempt == null ? "" : attempt.strip();
    }

    /**
     * The learner's optional self-reported confidence, kept entirely separate from the scheduler
     * rating (Again/Hard/Good/Easy) picked in {@link #rate(ReviewRating)}. Most useful when set
     * before revealing the answer, for later confidence-calibration statistics.
     */
    private ConfidenceLevel selectedConfidence() {
        if (confidenceToggleGroup == null) {
            return null;
        }
        Toggle selected = confidenceToggleGroup.getSelectedToggle();
        if (selected == confidenceLowButton) {
            return ConfidenceLevel.LOW;
        }
        if (selected == confidenceMediumButton) {
            return ConfidenceLevel.MEDIUM;
        }
        if (selected == confidenceHighButton) {
            return ConfidenceLevel.HIGH;
        }
        return null;
    }

    private void resetConfidenceSelection() {
        if (confidenceToggleGroup != null) {
            confidenceToggleGroup.selectToggle(null);
        }
    }

    private List<String> acceptedAnswers() {
        String rawAnswers = currentCard == null || currentCard.getAcceptedAnswers() == null || currentCard.getAcceptedAnswers().isBlank()
                ? currentCard == null ? "" : currentCard.getBack()
                : currentCard.getAcceptedAnswers();
        return AcceptedAnswerCodec.decode(rawAnswers);
    }

    private String formatRevealedAnswer() {
        String answer = currentCard.getBack();
        if (currentCard.getCardType().isCommandTemplate() && currentCard.getSimulatedOutput() != null
                && !currentCard.getSimulatedOutput().isBlank()) {
            return answer + "\n\nSimulated output:\n" + currentCard.getSimulatedOutput();
        }
        if (currentCard.getCardType() == CardType.JAVA_CODE) {
            return answer + "\n\n" + formatJavaExecutionDetails();
        }
        return answer;
    }

    /** Surfaces exactly what the sandbox captured — compile diagnostics, stderr, or stdout — so the
     *  learner can tell a compile error from a wrong-output run from a timeout, not just see a verdict. */
    private String formatJavaExecutionDetails() {
        JavaSandboxRunner.ExecutionResult result = lastJavaExecutionResult;
        if (result == null) {
            return "No sandbox result available.";
        }
        StringBuilder details = new StringBuilder(result.message());
        if (result.outputTruncated()) {
            details.append("\n(output truncated)");
        }
        return details.toString();
    }

    private void configureAttemptInput() {
        boolean command = currentCard != null && currentCard.getCardType().isCommandTemplate();
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

    private void resetTerminalHistory() {
        if (terminalHistoryArea != null) {
            terminalHistoryArea.setText("$ # output history appears after reveal");
        }
    }

    private void renderTerminalSubmission(AttemptValidationResult result) {
        if (currentCard == null || !currentCard.getCardType().isCommandTemplate() || terminalHistoryArea == null) {
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

    /**
     * Diffs the attempt against every accepted answer structurally (see {@link CommandValidator})
     * and reports the closest one. A different executable or subcommand is always named as such,
     * never described as "accepted" just because an unrelated flag or token happens to line up.
     */
    private String formatSafeCommandFeedback() {
        String attempt = getAttemptText();
        List<String> expectedAnswers = acceptedAnswers();
        if (expectedAnswers.isEmpty()) {
            return "command different from expected answer";
        }
        CommandValidator.Comparison closest = expectedAnswers.stream()
                .map(expected -> CommandValidator.compare(attempt, expected))
                .min(Comparator.comparingInt(CommandValidator.Comparison::mismatchCount))
                .orElseThrow();
        return describeCommandMismatch(closest);
    }

    /** Reuses the {@link SqlCardValidator.GradingResult} computed for this attempt in {@link #validateSqlAttempt()}. */
    private String formatSafeSqlFeedback() {
        return latestSqlGradingResult == null ? "Query result different from expected." : latestSqlGradingResult.feedback();
    }

    private String describeCommandMismatch(CommandValidator.Comparison comparison) {
        if (!comparison.executableMatches()) {
            return "different command: expected \"" + comparison.expectedExecutable()
                    + "\", got \"" + comparison.actualExecutable() + "\"";
        }
        if (!comparison.subcommandMatches()) {
            return "different subcommand: expected \"" + describeToken(comparison.expectedSubcommand())
                    + "\", got \"" + describeToken(comparison.actualSubcommand()) + "\"";
        }
        List<String> issues = new ArrayList<>();
        if (!comparison.missingFlags().isEmpty()) {
            issues.add("missing flag(s) " + String.join(", ", comparison.missingFlags()));
        }
        if (!comparison.extraFlags().isEmpty()) {
            issues.add("unexpected flag(s) " + String.join(", ", comparison.extraFlags()));
        }
        if (!comparison.incorrectFlagValues().isEmpty()) {
            issues.add("incorrect value for " + String.join(", ", comparison.incorrectFlagValues()));
        }
        if (!comparison.positionalArgsMatch()) {
            issues.add("arguments differ");
        }
        return issues.isEmpty() ? "command different from expected answer" : String.join("; ", issues);
    }

    private String describeToken(String token) {
        return token == null ? "(none)" : token;
    }

    private boolean isRegexExamplesCard() {
        return currentCard != null && currentCard.getCardType() == CardType.REGEX_PATTERN
                && currentCard.getValidationMode() == ValidationMode.REGEX_EXAMPLES;
    }

    /**
     * Runs the submitted pattern through {@link RegexCardValidator} again (grading itself only needs a
     * pass/fail boolean via {@link AnswerValidator}) purely to report which configured example broke and
     * how, without ever naming or displaying the card's own accepted pattern.
     */
    private String formatRegexFeedback() {
        String attempt = getAttemptText();
        RegexCardConfig config = RegexCardCodec.decode(rawAcceptedAnswersForCurrentCard());
        RegexCardValidator.Result result = RegexCardValidator.grade(attempt, config);
        return describeRegexOutcome(result);
    }

    private String describeRegexOutcome(RegexCardValidator.Result result) {
        return switch (result.outcome()) {
            case PASS -> "pattern matches all configured examples";
            case INVALID_SYNTAX -> "invalid regex syntax" + (result.syntaxError() == null ? "" : ": " + result.syntaxError());
            case TIMEOUT -> "pattern took too long to evaluate against example \"" + result.failingExample()
                    + "\" (possible catastrophic backtracking) — simplify the pattern";
            case MISCONFIGURED -> "this card has no configured example strings to grade against";
            case FAIL -> result.failingExampleShouldMatch()
                    ? "pattern didn't match required string \"" + result.failingExample() + "\""
                    : "pattern incorrectly matched string that should be rejected: \"" + result.failingExample() + "\"";
        };
    }

    private String rawAcceptedAnswersForCurrentCard() {
        List<String> decoded = acceptedAnswers();
        return decoded.isEmpty() ? "" : decoded.get(0);
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
        if (currentCard != null && currentCard.getCardType().isCommandTemplate() && result == AttemptValidationResult.DIFFERENT) {
            return formatSafeCommandFeedback() + ". Recommended rating: " + recommendedRatingLabel(result) + ".";
        }
        if (currentCard != null && currentCard.getCardType() == CardType.SQL_QUERY && result == AttemptValidationResult.DIFFERENT) {
            return formatSafeSqlFeedback() + ". Recommended rating: " + recommendedRatingLabel(result) + ".";
        }
        if (isRegexExamplesCard() && result == AttemptValidationResult.DIFFERENT) {
            return formatRegexFeedback() + ". Recommended rating: " + recommendedRatingLabel(result) + ".";
        }
        return switch (result) {
            case EMPTY -> "Enter an attempt to enable Reveal Answer.";
            case EXACT -> "Exact match. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case CLOSE_SPACING -> "Close, check spacing. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case DIFFERENT -> "Different from expected answer. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case TIMED_OUT -> "Time expired with no matching attempt. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case TIMED_OUT_WITH_ATTEMPT -> "Time expired after an attempt. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case SUBJECTIVE -> "Self-graded card. Compare your answer with the explanation, then rate yourself honestly — there's no recommended rating.";
            case JAVA_PENDING -> "Ready to compile and run. Reveal the answer to execute it in the sandbox.";
            case JAVA_CORRECT -> "Compiled and ran correctly. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case JAVA_WRONG_OUTPUT -> "Compiled and ran, but the output didn't match. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case JAVA_COMPILE_ERROR -> "Didn't compile. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case JAVA_TIMEOUT -> "Ran too long and was stopped (possible infinite loop). Recommended rating: " + recommendedRatingLabel(result) + ".";
            case JAVA_UNEXPECTED_EXCEPTION -> "Threw an exception that wasn't expected. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case JAVA_MISSING_EXPECTED_EXCEPTION -> "Ran to completion, but the expected exception was never thrown. Recommended rating: " + recommendedRatingLabel(result) + ".";
            case JAVA_REJECTED_UNSAFE_SNIPPET -> "This attempt uses a construct outside what this exercise allows — rewrite it without that and try again.";
            case JAVA_SANDBOX_UNAVAILABLE -> "The Java code sandbox is unavailable on this machine (no JDK found) — this card can't be graded here.";
        };
    }

    private String formatMatchRequirement() {
        if (currentCard != null && currentCard.getCardType().isCommandTemplate()) {
            return "Enter a command. Flags can be in any order (ls -la or ls -al both work), but the "
                    + "executable, subcommand, and arguments must match.";
        }
        if (currentCard != null && currentCard.getCardType() == CardType.CONCEPT) {
            return "This is a self-graded card. Your wording is not text-matched — compare your answer with the "
                    + "revealed explanation, then rate yourself honestly.";
        }
        if (currentCard != null && currentCard.getCardType() == CardType.JAVA_CODE) {
            return "Write the missing code. It is compiled and run in a sandboxed subprocess when you reveal the "
                    + "answer, and graded against the expected output or exception.";
        }
        if (currentCard != null && currentCard.getCardType() == CardType.SQL_QUERY) {
            return "Write a query. It runs against an isolated practice database and is graded by comparing "
                    + "its result to the expected result, not by matching exact wording.";
        }
        if (isRegexExamplesCard()) {
            RegexCardConfig config = RegexCardCodec.decode(rawAcceptedAnswersForCurrentCard());
            String modeText = config.matchMode() == RegexMatchMode.FULL_MATCH
                    ? "must match an example's entire string" : "may match anywhere within an example";
            String flagsText = config.flags().isEmpty() ? "no special flags"
                    : config.flags().stream().map(Object::toString).collect(Collectors.joining(", "));
            return "Your submitted pattern is compiled and run against configured example strings, not compared as "
                    + "text. It " + modeText + ". Flags: " + flagsText + ".";
        }
        return switch (currentCard == null || currentCard.getValidationMode() == null ? ValidationMode.CASE_INSENSITIVE : currentCard.getValidationMode()) {
            case EXACT -> "Exact capitalization, wording, and spacing are required for this card.";
            case CASE_INSENSITIVE -> "Case-insensitive exact matching is accepted for this card.";
            case NORMALIZED_SPACING, COMMAND_NORMALIZED -> "Extra spacing is normalized, and case-insensitive alternatives are accepted.";
            case REGEX_EXAMPLES -> "Your submitted pattern is compiled and run against configured example strings.";
        };
    }

    private void updateRatingButtonAvailability() {
        Set<ReviewRating> allowed = allowedRatings();
        againButton.setDisable(!allowed.contains(ReviewRating.AGAIN));
        hardButton.setDisable(!allowed.contains(ReviewRating.HARD));
        goodButton.setDisable(!allowed.contains(ReviewRating.GOOD));
        easyButton.setDisable(!allowed.contains(ReviewRating.EASY));
    }

    private Set<ReviewRating> allowedRatings() {
        if (!answerRevealed) {
            return EnumSet.noneOf(ReviewRating.class);
        }
        return RatingGuardrail.allowedRatings(currentCard.getCardType(), latestValidationResult.name(), hintUsed);
    }

    private String ratingBlockedReason(ReviewRating rating) {
        return RatingGuardrail.blockedReason(rating, currentCard.getCardType(), latestValidationResult.name(), hintUsed);
    }

    private void updateReviewMissedButton(boolean visible) {
        if (reviewMissedButton != null) {
            reviewMissedButton.setVisible(visible);
            reviewMissedButton.setManaged(visible);
            reviewMissedButton.setDisable(!visible);
        }
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
        TIMED_OUT_WITH_ATTEMPT(ReviewRating.HARD),
        /** Concept/reflection cards are self-assessed, never text-matched against an answer key. */
        SUBJECTIVE(null),
        /** Attempt entered but not yet compiled/run — resolves to one of the JAVA_* results below on reveal. */
        JAVA_PENDING(null),
        JAVA_CORRECT(ReviewRating.EASY),
        JAVA_WRONG_OUTPUT(ReviewRating.AGAIN),
        JAVA_COMPILE_ERROR(ReviewRating.AGAIN),
        JAVA_TIMEOUT(ReviewRating.AGAIN),
        JAVA_UNEXPECTED_EXCEPTION(ReviewRating.AGAIN),
        JAVA_MISSING_EXPECTED_EXCEPTION(ReviewRating.AGAIN),
        /** Defense-in-depth guard rejected the assembled source before any process was spawned. */
        JAVA_REJECTED_UNSAFE_SNIPPET(null),
        /** No JDK (javac) discoverable at java.home — the feature disables itself gracefully. */
        JAVA_SANDBOX_UNAVAILABLE(null);

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

package com.codefit.controller;

import com.codefit.model.AssessmentVariant;
import com.codefit.service.AssessmentAttemptService;
import com.codefit.service.AssessmentGradingService;
import com.codefit.service.WeeklyAssessmentSelectionService;
import com.codefit.service.WeeklyAssessmentSelectionService.SelectedAssessment;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drives a weekly transfer assessment session: unseen/parameterized scenarios that measure whether
 * a concept can be applied, not whether the exact wording of a normal review card is recognized
 * (#104). Deliberately its own controller/route rather than reusing {@link ReviewController} — the
 * two activities must stay visibly and structurally separate, and this controller never touches a
 * {@link com.codefit.model.Flashcard} or writes to {@code review_history}.
 */
public class WeeklyAssessmentController extends BaseController {
    @FXML private Label queueLabel;
    @FXML private Label categoryLabel;
    @FXML private Label scenarioLabel;
    @FXML private Label feedbackLabel;
    @FXML private Label referenceAnswerLabel;
    @FXML private Label hintLabel;
    @FXML private Label completionSummaryLabel;
    @FXML private ProgressBar assessmentProgressBar;
    @FXML private TextArea attemptTextArea;
    @FXML private VBox itemPanel;
    @FXML private VBox completionPanel;
    @FXML private VBox emptyStatePanel;
    @FXML private VBox hintBox;
    @FXML private VBox referenceAnswerSection;
    @FXML private VBox selfRatingBox;
    @FXML private VBox completionSkillBreakdownBox;
    @FXML private Button showHintButton;
    @FXML private Button checkAnswerButton;
    @FXML private Button nextButton;

    private final WeeklyAssessmentSelectionService selectionService = new WeeklyAssessmentSelectionService();
    private final AssessmentAttemptService assessmentAttemptService = new AssessmentAttemptService();

    private final Map<String, int[]> skillTally = new LinkedHashMap<>();
    private final String runId = UUID.randomUUID().toString();
    private List<SelectedAssessment> queue = List.of();
    private int currentIndex;
    private int correctCount;
    private int totalAnswered;
    private SelectedAssessment current;
    private AssessmentGradingService.GradingResult latestGrading;
    private boolean answerRevealed;
    private long itemShownAtMillis;

    @FXML
    public void initialize() {
        queue = selectionService.selectWeeklyAssessment();
        currentIndex = 0;
        correctCount = 0;
        totalAnswered = 0;
        attemptTextArea.textProperty().addListener((observable, oldValue, newValue) -> updateCheckButtonState());
        showCurrentItem();
    }

    @FXML
    public void checkAnswer() {
        if (current == null || answerRevealed) {
            return;
        }
        String attemptText = getAttemptText();
        AssessmentGradingService.GradingResult result = AssessmentGradingService.grade(
                current.item().getCardType(), current.item().getValidationMode(), current.variant(), attemptText);
        if (result.outcome() == AssessmentGradingService.Outcome.EMPTY) {
            setStatus(feedbackLabel, "Enter an answer before checking.");
            return;
        }

        latestGrading = result;
        answerRevealed = true;
        itemShownAtMillis = itemShownAtMillis == 0 ? System.currentTimeMillis() : itemShownAtMillis;
        attemptTextArea.setDisable(true);
        checkAnswerButton.setDisable(true);
        showHintButton.setDisable(true);
        setVisible(referenceAnswerSection, true);
        referenceAnswerLabel.setText(current.variant().referenceAnswer());
        setStatus(feedbackLabel, formatFeedback(result));

        if (result.needsSelfRating()) {
            setVisible(selfRatingBox, true);
            setVisible(nextButton, false);
        } else {
            recordAttempt(result.isCorrect());
            setVisible(selfRatingBox, false);
            setVisible(nextButton, true);
        }
    }

    @FXML
    public void markGotIt() {
        selfRate(true);
    }

    @FXML
    public void markMissedIt() {
        selfRate(false);
    }

    private void selfRate(boolean correct) {
        if (current == null || !answerRevealed || latestGrading == null || !latestGrading.needsSelfRating()) {
            return;
        }
        recordAttempt(correct);
        setVisible(selfRatingBox, false);
        setVisible(nextButton, true);
        setStatus(feedbackLabel, correct ? "Marked correct — nice transfer." : "Marked missed — this concept needs more practice.");
    }

    @FXML
    public void showHint() {
        if (current == null || answerRevealed || !current.variant().hasHint()) {
            return;
        }
        hintLabel.setText("Hint: " + current.variant().hint());
        setVisible(hintBox, true);
        showHintButton.setDisable(true);
    }

    @FXML
    public void nextItem() {
        currentIndex++;
        showCurrentItem();
    }

    @FXML
    public void exitAssessment() {
        goStats();
    }

    private void recordAttempt(boolean correct) {
        Integer responseTimeMs = itemShownAtMillis <= 0 ? null
                : (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - itemShownAtMillis);
        assessmentAttemptService.recordAttempt(current.item(), current.variant(), correct, getAttemptText(), responseTimeMs, runId);
        totalAnswered++;
        if (correct) {
            correctCount++;
        }
        int[] counts = skillTally.computeIfAbsent(current.item().getSkillCategory(), ignored -> new int[2]);
        counts[0]++;
        if (correct) {
            counts[1]++;
        }
    }

    private void showCurrentItem() {
        if (queue.isEmpty()) {
            showPanel(emptyStatePanel);
            queueLabel.setText("No items");
            return;
        }
        if (currentIndex >= queue.size()) {
            showCompletion();
            return;
        }

        showPanel(itemPanel);
        current = queue.get(currentIndex);
        answerRevealed = false;
        latestGrading = null;
        itemShownAtMillis = System.currentTimeMillis();

        AssessmentVariant variant = current.variant();
        queueLabel.setText((currentIndex + 1) + " / " + queue.size());
        assessmentProgressBar.setProgress((double) currentIndex / queue.size());
        categoryLabel.setText(current.item().getSkillCategory() + " • " + current.item().getModuleName());
        scenarioLabel.setText(variant.scenario());
        attemptTextArea.clear();
        attemptTextArea.setDisable(false);
        setVisible(hintBox, false);
        setVisible(referenceAnswerSection, false);
        setVisible(selfRatingBox, false);
        setVisible(nextButton, false);
        setStatus(feedbackLabel, "");
        showHintButton.setDisable(!variant.hasHint());
        updateCheckButtonState();
    }

    private void showCompletion() {
        showPanel(completionPanel);
        assessmentProgressBar.setProgress(1.0);
        queueLabel.setText("Complete");
        completionSummaryLabel.setText(totalAnswered + " of " + queue.size() + " items answered, "
                + correctCount + " correct (" + formatPercent(correctCount, totalAnswered) + ").");

        completionSkillBreakdownBox.getChildren().clear();
        skillTally.forEach((skill, counts) -> {
            Label row = new Label(skill + ": " + counts[1] + " / " + counts[0] + " (" + formatPercent(counts[1], counts[0]) + ")");
            row.getStyleClass().add("skill-stat-detail");
            row.setWrapText(true);
            completionSkillBreakdownBox.getChildren().add(row);
        });
        if (skillTally.isEmpty()) {
            Label emptyLabel = new Label("No items were answered this session.");
            emptyLabel.getStyleClass().add("skill-stat-detail");
            completionSkillBreakdownBox.getChildren().add(emptyLabel);
        }
    }

    private String formatPercent(int numerator, int denominator) {
        return denominator == 0 ? "0%" : Math.round(numerator * 100.0 / denominator) + "%";
    }

    private String formatFeedback(AssessmentGradingService.GradingResult result) {
        return switch (result.outcome()) {
            case CORRECT -> "Correct. " + result.feedback();
            case INCORRECT -> "Not quite. " + result.feedback();
            case SUBJECTIVE -> result.feedback();
            case EMPTY -> "";
        };
    }

    private void updateCheckButtonState() {
        checkAnswerButton.setDisable(current == null || answerRevealed || getAttemptText().isEmpty());
    }

    private String getAttemptText() {
        String text = attemptTextArea.getText();
        return text == null ? "" : text.strip();
    }

    private void showPanel(VBox panel) {
        setVisible(itemPanel, panel == itemPanel);
        setVisible(completionPanel, panel == completionPanel);
        setVisible(emptyStatePanel, panel == emptyStatePanel);
    }

    private void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}

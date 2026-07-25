package com.codefit.controller;

import com.codefit.model.ReflectionType;
import com.codefit.service.GuidedStage;
import com.codefit.service.GuidedTrainingPlan;
import com.codefit.service.GuidedTrainingService;
import com.codefit.service.GuidedTrainingSummary;
import com.codefit.service.ReviewService;
import com.codefit.ui.NavigationService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Map;

/**
 * Drives the guided daily routine hub (#111): one screen that resolves which stage comes next
 * (review, optional reflection, weekly assessment when due, or the completion summary) and either
 * renders that stage directly (when there's nothing to hand off to) or launches the existing
 * Review/Reflection/Assessment screen for it. Those screens mark their own stage done and call
 * {@link NavigationService#resumeGuidedTraining()} to come back here — this controller never
 * duplicates their review/reflection/assessment logic, only sequences them.
 */
public class GuidedTrainingController extends BaseController {
    @FXML private Label stageProgressLabel;
    @FXML private ProgressBar stageProgressBar;
    @FXML private Label stageTitleLabel;
    @FXML private Label stageBodyLabel;
    @FXML private Label stageWarningLabel;
    @FXML private VBox stageStatsBox;
    @FXML private HBox stageActionsBox;

    private final GuidedTrainingService guidedTrainingService = new GuidedTrainingService();
    private GuidedTrainingPlan plan;

    @FXML
    public void initialize() {
        plan = guidedTrainingService.buildPlan(guidedTrainingService.getPreferredSessionMinutes());
        GuidedStage stage = guidedTrainingService.resolveCurrentStage(NavigationService.getCompletedGuidedStages(), plan);
        renderStage(stage);
    }

    @FXML
    public void exitGuidedTraining() {
        NavigationService.exitGuidedTraining();
        goDashboard();
    }

    private void renderStage(GuidedStage stage) {
        stageActionsBox.getChildren().clear();
        stageStatsBox.getChildren().clear();
        setStatus(stageWarningLabel, "");
        int totalSteps = plan.weeklyAssessmentDue() ? 4 : 3;

        switch (stage) {
            case REVIEW -> renderReviewStage(totalSteps);
            case REFLECTION -> renderReflectionStage(totalSteps);
            case WEEKLY_ASSESSMENT -> renderAssessmentStage(totalSteps);
            case COMPLETE -> renderCompletionStage(totalSteps);
        }
    }

    private void renderReviewStage(int totalSteps) {
        updateStageProgress(1, totalSteps);
        stageTitleLabel.setText("Review & Relearn");
        ReviewService.AdaptiveSessionPlan reviewPlan = plan.reviewPlan();
        if (plan.hasReviewWork()) {
            int cardCount = reviewPlan.cards().size();
            stageBodyLabel.setText(cardCount + " " + pluralize(cardCount, "card") + " queued, ~"
                    + Math.round(reviewPlan.estimatedSeconds() / 60.0) + " min. Due and relearning cards always come "
                    + "first; any Again/Hard card comes back later in the same session, and new cards (capped for "
                    + "today) are drawn mainly from your focus module.");
            renderComposition(reviewPlan.composition());
            if (plan.cardsNeedingRewrite() > 0) {
                addStatRow(plan.cardsNeedingRewrite() + " " + pluralize(plan.cardsNeedingRewrite(), "card")
                        + " flagged for a rewrite — see Progress.");
            }
            addPrimaryAction("Start Review (~" + plan.sessionMinutes() + " min)",
                    () -> NavigationService.showTimedReview(plan.sessionMinutes()));
        } else {
            stageBodyLabel.setText("Nothing due or new right now — nice work! Continuing to the optional reflection step.");
            addPrimaryAction("Continue", () -> {
                NavigationService.markGuidedStageDone(GuidedStage.REVIEW);
                NavigationService.resumeGuidedTraining();
            });
        }
    }

    private void renderReflectionStage(int totalSteps) {
        updateStageProgress(2, totalSteps);
        stageTitleLabel.setText("Reflect (optional)");
        stageBodyLabel.setText("Capture or refine one atomic card from real work: a bug you fixed, a command you "
                + "searched, or a concept you missed. Entirely optional — skipping never discards the review work "
                + "you just finished, since every review was already saved as you went.");
        if (!plan.hasTimeForOptionalStages()) {
            setStatus(stageWarningLabel, "Low on time this session — a quick reflection still fits in a minute, or skip for today.");
        }
        addAction("Add a reflection", () -> NavigationService.showReflectionCapture(ReflectionType.BUG), true);
        addAction("Skip reflection", () -> {
            NavigationService.markGuidedStageDone(GuidedStage.REFLECTION);
            NavigationService.resumeGuidedTraining();
        }, false);
    }

    private void renderAssessmentStage(int totalSteps) {
        updateStageProgress(3, totalSteps);
        stageTitleLabel.setText("Weekly Assessment");
        int itemCount = plan.weeklyAssessmentItems().size();
        stageBodyLabel.setText("This week's unseen transfer assessment is ready: " + itemCount + " "
                + pluralize(itemCount, "scenario") + " prioritizing skills that need practice. Optional, and offered "
                + "at most once a week.");
        addAction("Take this week's assessment", NavigationService::showWeeklyAssessment, true);
        addAction("Skip this week", () -> {
            NavigationService.markGuidedStageDone(GuidedStage.WEEKLY_ASSESSMENT);
            NavigationService.resumeGuidedTraining();
        }, false);
    }

    private void renderCompletionStage(int totalSteps) {
        updateStageProgress(totalSteps, totalSteps);
        stageTitleLabel.setText("Training Complete");
        GuidedTrainingSummary summary = guidedTrainingService.buildCompletionSummary();
        stageBodyLabel.setText("Here's what today's training added up to.");
        addStatRow(summary.cardsReviewed() + " " + pluralize(summary.cardsReviewed(), "card") + " reviewed today.");
        addStatRow(summary.retainedCount() + " retained, " + summary.missedCount() + " missed, "
                + summary.recoveredCount() + " recovered after an initial miss.");
        addStatRow(summary.newlyCapturedCount() + " newly captured " + pluralize(summary.newlyCapturedCount(), "card")
                + " from reflection.");
        if (summary.weeklyAssessmentTaken()) {
            addStatRow("Weekly assessment: " + summary.weeklyAssessmentCorrect() + " / "
                    + summary.weeklyAssessmentTotal() + " correct.");
        }
        if (plan.cardsNeedingRewrite() > 0) {
            addStatRow(plan.cardsNeedingRewrite() + " " + pluralize(plan.cardsNeedingRewrite(), "card")
                    + " still flagged for a rewrite — see Progress.");
        }
        addPrimaryAction("Done", this::exitGuidedTraining);
    }

    private void renderComposition(Map<String, Integer> composition) {
        composition.forEach((label, count) -> addStatRow(count + " " + label));
    }

    private void updateStageProgress(int stepNumber, int totalSteps) {
        stageProgressLabel.setText("Step " + stepNumber + " of " + totalSteps);
        stageProgressBar.setProgress(totalSteps == 0 ? 0 : (double) stepNumber / totalSteps);
    }

    private void addStatRow(String text) {
        Label row = new Label(text);
        row.getStyleClass().add("dashboard-card-helper");
        row.setWrapText(true);
        stageStatsBox.getChildren().add(row);
    }

    private void addPrimaryAction(String text, Runnable action) {
        addAction(text, action, true);
    }

    private void addAction(String text, Runnable action, boolean primary) {
        Button button = new Button(text);
        button.getStyleClass().add(primary ? "action-button" : "ghost-button");
        button.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(button, Priority.ALWAYS);
        button.setOnAction(event -> action.run());
        stageActionsBox.getChildren().add(button);
    }

    private String pluralize(int count, String singular) {
        return count == 1 ? singular : singular + "s";
    }
}

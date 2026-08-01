package com.codefit.controller;

import com.codefit.service.DatabaseInternalsPackService;
import com.codefit.ui.NavigationService;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;

public abstract class BaseController {
    protected void setStatus(Label label, String message) {
        String status = message == null ? "" : message.strip();
        boolean hasStatus = !status.isBlank();
        label.setText(status);
        label.setVisible(hasStatus);
        label.setManaged(hasStatus);
    }

    /** Controllers with in-flight work may veto navigation until their state is safe to discard. */
    protected boolean canNavigateAway() {
        return true;
    }

    /** Called immediately before an allowed navigation so controllers can release screen-owned state. */
    protected void beforeNavigateAway() {
    }

    private void navigate(Runnable action) {
        if (canNavigateAway()) {
            beforeNavigateAway();
            action.run();
        }
    }

    public void goDashboard() { navigate(NavigationService::showDashboard); }
    public void goDecks() { navigate(NavigationService::showDecks); }
    public void goProblems() { navigate(NavigationService::showProblems); }
    public void goAddCard() { navigate(NavigationService::showAddCard); }
    public void goReview() { navigate(NavigationService::showReview); }
    public void goSyllabus() { navigate(NavigationService::showSyllabus); }
    public void goStats() { navigate(NavigationService::showStats); }
    public void goWeeklyAssessment() { navigate(NavigationService::showWeeklyAssessment); }
    public void goGuidedTraining() { navigate(NavigationService::beginGuidedTraining); }

    /** Shared Library action: install the bundled curriculum, then reload Library to show its decks. */
    @FXML
    public void installDatabaseInternalsPath() {
        if (!canNavigateAway()) {
            return;
        }
        beforeNavigateAway();
        try {
            DatabaseInternalsPackService.InstallSummary summary = new DatabaseInternalsPackService().install();
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Database Internals");
            alert.setHeaderText("Learning path ready");
            alert.setContentText(summary.message());
            alert.showAndWait();
            NavigationService.showDecks();
        } catch (RuntimeException exception) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Database Internals");
            alert.setHeaderText("The learning path could not be installed");
            alert.setContentText(exception.getMessage() == null ? "Unexpected installation error." : exception.getMessage());
            alert.showAndWait();
        }
    }
}

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

    public void goDashboard() { NavigationService.showDashboard(); }
    public void goDecks() { NavigationService.showDecks(); }
    public void goAddCard() { NavigationService.showAddCard(); }
    public void goReview() { NavigationService.showReview(); }
    public void goSyllabus() { NavigationService.showSyllabus(); }
    public void goStats() { NavigationService.showStats(); }
    public void goWeeklyAssessment() { NavigationService.showWeeklyAssessment(); }
    public void goGuidedTraining() { NavigationService.beginGuidedTraining(); }

    /** Shared Library action: install the bundled curriculum, then reload Library to show its decks. */
    @FXML
    public void installDatabaseInternalsPath() {
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

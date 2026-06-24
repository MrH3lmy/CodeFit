package com.codefit.controller;

import com.codefit.ui.NavigationService;
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
}

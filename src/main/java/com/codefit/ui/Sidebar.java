package com.codefit.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.Locale;

public class Sidebar extends VBox {
    private static final String DEFAULT_NAV_CLASS = "nav-button";
    private static final String ACTIVE_NAV_CLASS = "nav-button-primary";

    @FXML private Button dashboardButton;
    @FXML private Button decksButton;
    @FXML private Button addCardButton;
    @FXML private Button reviewButton;
    @FXML private Button statsButton;

    private String activePage = "";

    public Sidebar() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/sidebar.fxml"));
        loader.setRoot(this);
        loader.setController(this);

        try {
            loader.load();
            updateActiveNavigation();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load sidebar component.", exception);
        }
    }

    public String getActivePage() {
        return activePage;
    }

    public void setActivePage(String activePage) {
        this.activePage = normalize(activePage);
        updateActiveNavigation();
    }

    @FXML
    private void goDashboard() {
        NavigationService.showDashboard();
    }

    @FXML
    private void goDecks() {
        NavigationService.showDecks();
    }

    @FXML
    private void goAddCard() {
        NavigationService.showAddCard();
    }

    @FXML
    private void goReview() {
        NavigationService.showReview();
    }

    @FXML
    private void goStats() {
        NavigationService.showStats();
    }

    private void updateActiveNavigation() {
        setNavigationStyle(dashboardButton, "dashboard");
        setNavigationStyle(decksButton, "decks");
        setNavigationStyle(addCardButton, "add-card");
        setNavigationStyle(reviewButton, "review");
        setNavigationStyle(statsButton, "stats");
    }

    private void setNavigationStyle(Button button, String page) {
        if (button == null) {
            return;
        }

        button.getStyleClass().removeAll(DEFAULT_NAV_CLASS, ACTIVE_NAV_CLASS);
        button.getStyleClass().add(activePage.equals(page) ? ACTIVE_NAV_CLASS : DEFAULT_NAV_CLASS);
    }

    private String normalize(String page) {
        if (page == null) {
            return "";
        }

        return page.trim().toLowerCase(Locale.ROOT);
    }
}

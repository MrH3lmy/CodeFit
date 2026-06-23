package com.codefit.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.Locale;

public class Sidebar extends VBox {
    private static final String DEFAULT_NAV_CLASS = "nav-item";
    private static final String ACTIVE_NAV_CLASS = "nav-item-active";
    private static final String COMPACT_CLASS = "sidebar-compact";
    private static final String NARROW_CLASS = "sidebar-narrow";
    private static final double NARROW_WINDOW_WIDTH = 920;

    @FXML private Button dashboardButton;
    @FXML private Button decksButton;
    @FXML private Button addCardButton;
    @FXML private Button reviewButton;
    @FXML private Button statsButton;
    @FXML private ChoiceBox<String> themeChoiceBox;
    @FXML private Label subtitleLabel;
    @FXML private VBox footerCard;

    private final ChangeListener<Number> widthListener = (observable, oldWidth, newWidth) ->
            updateNarrowState(newWidth.doubleValue());

    private String activePage = "";

    public Sidebar() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/sidebar.fxml"));
        loader.setRoot(this);
        loader.setController(this);

        try {
            loader.load();
            configureThemeSelector();
            configureCompactBehavior();
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

    private void configureCompactBehavior() {
        if (!getStyleClass().contains(COMPACT_CLASS)) {
            getStyleClass().add(COMPACT_CLASS);
        }

        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (oldScene != null) {
                oldScene.widthProperty().removeListener(widthListener);
            }

            if (newScene == null) {
                updateNarrowState(false);
                return;
            }

            updateNarrowState(newScene.getWidth());
            newScene.widthProperty().addListener(widthListener);
        });
    }

    private void updateNarrowState(double width) {
        updateNarrowState(width > 0 && width < NARROW_WINDOW_WIDTH);
    }

    private void updateNarrowState(boolean narrow) {
        if (narrow) {
            if (!getStyleClass().contains(NARROW_CLASS)) {
                getStyleClass().add(NARROW_CLASS);
            }
        } else {
            getStyleClass().remove(NARROW_CLASS);
        }

        setNodeVisible(subtitleLabel, !narrow);
        setNodeVisible(footerCard, !narrow);
    }

    private void setNodeVisible(Node node, boolean visible) {
        if (node == null) {
            return;
        }

        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void updateActiveNavigation() {
        setNavigationStyle(dashboardButton, "dashboard");
        setNavigationStyle(decksButton, "decks");
        setNavigationStyle(addCardButton, "add-card");
        setNavigationStyle(reviewButton, "review");
        setNavigationStyle(statsButton, "stats");
    }

    private void configureThemeSelector() {
        if (themeChoiceBox == null) {
            return;
        }

        themeChoiceBox.getItems().setAll(NavigationService.getThemeDisplayNames());
        themeChoiceBox.setValue(NavigationService.getCurrentThemeDisplayName());
        themeChoiceBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                NavigationService.setTheme(NavigationService.getThemeClassByDisplayName(newValue));
            }
        });
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

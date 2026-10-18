package com.codefit.ui;

import javafx.beans.value.ChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

import java.io.IOException;

public class Sidebar extends VBox {
    private static final String DEFAULT_NAV_CLASS = "nav-item";
    private static final String ACTIVE_NAV_CLASS = "nav-item-active";
    private static final String NARROW_CLASS = "sidebar-narrow";
    private static final double NARROW_WINDOW_WIDTH = 920;

    @FXML private Button evolutionButton;
    @FXML private Button todayButton;
    @FXML private Button reviewButton;
    @FXML private Button libraryButton;
    @FXML private Button problemsButton;
    @FXML private Button interviewButton;
    @FXML private Button progressButton;
    @FXML private Button peersButton;
    @FXML private Button settingsButton;
    @FXML private Label localNote;

    private final ChangeListener<Number> widthListener = (observable, oldWidth, newWidth) ->
            updateNarrowState(newWidth.doubleValue());

    private Route.NavItem activeNavItem;

    public Sidebar() {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/sidebar.fxml"));
        loader.setRoot(this);
        loader.setController(this);

        try {
            loader.load();
            configureIcons();
            configureCompactBehavior();
            updateActiveNavigation();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load sidebar component.", exception);
        }
    }

    public Route.NavItem getActiveNavItem() {
        return activeNavItem;
    }

    /** Highlights the sidebar item for the given route, or clears the highlight for a global action. */
    public void setActiveRoute(Route.NavItem navItem) {
        this.activeNavItem = navItem;
        updateActiveNavigation();
    }

    @FXML private void goEvolution() { NavigationService.navigate(Route.EVOLUTION); }

    @FXML
    private void goToday() {
        NavigationService.showDashboard();
    }

    @FXML
    private void goReview() {
        NavigationService.showReview();
    }

    @FXML
    private void goLibrary() {
        NavigationService.showDecks();
    }

    @FXML
    private void goProblems() {
        NavigationService.showProblems();
    }

    @FXML
    private void goInterview() {
        NavigationService.navigate(Route.INTERVIEW);
    }

    @FXML
    private void goProgress() {
        NavigationService.showStats();
    }

    @FXML
    private void goPeers() {
        NavigationService.showPeers();
    }

    @FXML
    private void goSettings() {
        NavigationService.showSettings();
    }

    /** Original geometric vectors: no font-glyph dependency or external asset loading. */
    private void configureIcons() {
        icon(todayButton, "M2 2 H14 V14 H2 Z M2 6 H14 M6 6 V14");
        icon(evolutionButton, "M8 1 L15 8 L8 15 L1 8 Z M5 8 H11 M8 5 V11");
        icon(reviewButton, "M3 5 A6 6 0 1 1 2 10 M3 1 V5 H7");
        icon(libraryButton, "M2 2 H6 V14 H2 Z M10 2 L14 3 V14 L10 13 Z");
        icon(problemsButton, "M5 3 L1 8 L5 13 M11 3 L15 8 L11 13 M9 2 L7 14");
        icon(interviewButton, "M2 2 H14 V11 H8 L4 15 V11 H2 Z");
        icon(progressButton, "M2 14 V9 H5 V14 M7 14 V5 H10 V14 M12 14 V1 H15 V14");
        icon(peersButton, "M6 4 A2 2 0 1 1 2 4 A2 2 0 1 1 6 4 M1 14 V12 Q4 8 7 12 V14 M14 4 A2 2 0 1 1 10 4 A2 2 0 1 1 14 4 M9 14 V12 Q12 8 15 12 V14");
        icon(settingsButton, "M8 3 A5 5 0 1 1 7.9 3 M8 6 A2 2 0 1 1 7.9 6 M8 0 V3 M8 13 V16 M0 8 H3 M13 8 H16");
    }

    private static void icon(Button button, String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("nav-icon");
        icon.setMouseTransparent(true);
        button.setGraphic(icon);
    }

    private void configureCompactBehavior() {
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

        setNodeVisible(localNote, !narrow);
    }

    private void setNodeVisible(Node node, boolean visible) {
        if (node == null) {
            return;
        }

        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void updateActiveNavigation() {
        setNavigationStyle(evolutionButton, Route.NavItem.EVOLUTION);
        setNavigationStyle(todayButton, Route.NavItem.TODAY);
        setNavigationStyle(reviewButton, Route.NavItem.REVIEW);
        setNavigationStyle(libraryButton, Route.NavItem.LIBRARY);
        setNavigationStyle(problemsButton, Route.NavItem.PROBLEMS);
        setNavigationStyle(interviewButton, Route.NavItem.INTERVIEW);
        setNavigationStyle(progressButton, Route.NavItem.PROGRESS);
        setNavigationStyle(peersButton, Route.NavItem.PEERS);
        setNavigationStyle(settingsButton, Route.NavItem.SETTINGS);
    }

    private void setNavigationStyle(Button button, Route.NavItem navItem) {
        if (button == null) {
            return;
        }

        button.getStyleClass().removeAll(DEFAULT_NAV_CLASS, ACTIVE_NAV_CLASS);
        button.getStyleClass().add(navItem == activeNavItem ? ACTIVE_NAV_CLASS : DEFAULT_NAV_CLASS);
        button.setAccessibleText(button.getText() + (navItem == activeNavItem ? ", current page" : ""));
    }
}

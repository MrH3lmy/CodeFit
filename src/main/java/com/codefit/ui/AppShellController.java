package com.codefit.ui;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import java.io.IOException;
import java.net.URL;

/**
 * Controller for the persistent application shell: a single Sidebar and top bar that stay in the
 * scene graph across navigation, with only the content host's child swapped per route.
 */
public class AppShellController {
    @FXML private BorderPane shellRoot;
    @FXML private Sidebar sidebar;
    @FXML private HBox topBar;
    @FXML private Label topBarTitleLabel;
    @FXML private StackPane contentHost;

    private RouteLifecycle lifecycle;
    private long navigationGeneration;
    private Route currentRoute;
    private boolean distractionFree;

    @FXML
    private void initialize() {
        shellRoot.widthProperty().addListener((observable, before, width) -> {
            boolean narrow = width.doubleValue() > 0 && width.doubleValue() < 920;
            if (narrow && !shellRoot.getStyleClass().contains("shell-narrow")) {
                shellRoot.getStyleClass().add("shell-narrow");
            } else if (!narrow) {
                shellRoot.getStyleClass().remove("shell-narrow");
            }
        });
    }

    void showRoute(Route route) {
        long generation = ++navigationGeneration;
        Runnable display =
                () -> {
                    if (generation == navigationGeneration) displayRoute(route);
                };
        if (lifecycle != null) lifecycle.requestLeave(display);
        else display.run();
    }

    private void displayRoute(Route route) {
        if (lifecycle != null) {
            lifecycle.dispose();
            lifecycle = null;
        }
        Parent content = loadContent(route);
        contentHost.getChildren().setAll(content);
        currentRoute = route;

        sidebar.setActiveRoute(route.navItem());
        if (topBarTitleLabel != null) {
            topBarTitleLabel.setText(route.shortLabel());
        }

        setDistractionFreeMode(route == Route.REVIEW || route == Route.ASSESSMENT || route == Route.GUIDED_TRAINING);
    }

    Route getCurrentRoute() {
        return currentRoute;
    }

    void setDistractionFreeMode(boolean enabled) {
        if (distractionFree == enabled) {
            return;
        }
        distractionFree = enabled;
        sidebar.setVisible(!enabled);
        sidebar.setManaged(!enabled);
        topBar.setVisible(!enabled);
        topBar.setManaged(!enabled);
    }

    boolean hasLifecycle() {
        return lifecycle != null;
    }

    void requestClose(Runnable close) {
        lifecycle.requestLeave(
                () -> {
                    lifecycle.dispose();
                    lifecycle = null;
                    close.run();
                });
    }

    private Parent loadContent(Route route) {
        try {
            URL resource = getClass().getResource(route.fxmlPath());
            FXMLLoader loader = new FXMLLoader(resource);
            Parent content = loader.load();
            if (loader.getController() instanceof RouteLifecycle owner) lifecycle = owner;
            return content;
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to load screen: " + route.fxmlResource(), exception);
        }
    }

    @FXML
    private void openNewCard() {
        NavigationService.showAddCard();
    }
}

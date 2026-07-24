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

    private Route currentRoute;
    private boolean distractionFree;

    void showRoute(Route route) {
        Parent content = loadContent(route);
        contentHost.getChildren().setAll(content);
        currentRoute = route;

        if (route.navItem() != null) {
            sidebar.setActiveRoute(route.navItem());
        }
        if (topBarTitleLabel != null) {
            topBarTitleLabel.setText(route.shortLabel());
        }

        setDistractionFreeMode(route == Route.REVIEW);
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

    private Parent loadContent(Route route) {
        try {
            URL resource = getClass().getResource(route.fxmlPath());
            return FXMLLoader.load(resource);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load screen: " + route.fxmlResource(), exception);
        }
    }

    @FXML
    private void openNewCard() {
        NavigationService.showAddCard();
    }
}

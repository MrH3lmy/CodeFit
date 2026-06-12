package com.codefit.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;

public final class NavigationService {
    private static Stage primaryStage;

    private NavigationService() {
    }

    public static void setPrimaryStage(Stage stage) {
        primaryStage = stage;
    }

    public static void showDashboard() {
        navigate("dashboard.fxml", "CodeFit - Dashboard");
    }

    public static void showDecks() {
        navigate("decks.fxml", "CodeFit - Decks");
    }

    public static void showAddCard() {
        navigate("add-card.fxml", "CodeFit - Add Card");
    }

    public static void showReview() {
        navigate("review.fxml", "CodeFit - Review");
    }

    public static void showStats() {
        navigate("stats.fxml", "CodeFit - Stats");
    }

    public static void navigate(String fxmlName, String title) {
        if (primaryStage == null) {
            throw new IllegalStateException("Primary stage has not been configured.");
        }
        try {
            URL resource = NavigationService.class.getResource("/fxml/" + fxmlName);
            Parent root = FXMLLoader.load(resource);
            Scene scene = new Scene(root, 1100, 720);
            scene.getStylesheets().add(NavigationService.class.getResource("/css/app.css").toExternalForm());
            primaryStage.setTitle(title);
            primaryStage.setMinWidth(760);
            primaryStage.setMinHeight(560);
            primaryStage.setScene(scene);
            primaryStage.show();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load screen: " + fxmlName, exception);
        }
    }
}

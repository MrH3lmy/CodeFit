package com.codefit.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.List;

public final class NavigationService {
    private static final List<ThemeOption> THEMES = List.of(
            new ThemeOption("Dark", "theme-dark"),
            new ThemeOption("Light", "theme-light"),
            new ThemeOption("Ocean", "theme-ocean"),
            new ThemeOption("Forest", "theme-forest"),
            new ThemeOption("Synthwave", "theme-synthwave")
    );
    private static final String BASE_THEME_CLASS = "theme-dark";

    private static Stage primaryStage;
    private static ThemeOption currentTheme = THEMES.getFirst();

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
            applyTheme(root);
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

    public static List<String> getThemeDisplayNames() {
        return THEMES.stream().map(ThemeOption::displayName).toList();
    }

    public static String getCurrentThemeDisplayName() {
        return currentTheme.displayName();
    }

    public static void setThemeByDisplayName(String displayName) {
        currentTheme = THEMES.stream()
                .filter(theme -> theme.displayName().equals(displayName))
                .findFirst()
                .orElse(currentTheme);

        if (primaryStage != null && primaryStage.getScene() != null) {
            applyTheme(primaryStage.getScene().getRoot());
        }
    }

    private static void applyTheme(Parent root) {
        root.getStyleClass().removeAll(THEMES.stream().map(ThemeOption::styleClass).toArray(String[]::new));
        root.getStyleClass().remove(BASE_THEME_CLASS);

        if (!currentTheme.styleClass().equals(BASE_THEME_CLASS)) {
            root.getStyleClass().add(BASE_THEME_CLASS);
        }
        root.getStyleClass().add(currentTheme.styleClass());
    }

    private record ThemeOption(String displayName, String styleClass) {
    }
}

package com.codefit.ui;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.prefs.Preferences;

public final class NavigationService {
    private static final List<ThemeOption> THEMES = List.of(
            new ThemeOption("Dark", "theme-dark"),
            new ThemeOption("Light", "theme-light"),
            new ThemeOption("Ocean", "theme-ocean"),
            new ThemeOption("Forest", "theme-forest"),
            new ThemeOption("Synthwave", "theme-synthwave")
    );
    private static final String BASE_THEME_CLASS = "theme-dark";
    private static final String THEME_PREFERENCE_KEY = "selectedTheme";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(NavigationService.class);

    private static Stage primaryStage;
    private static Scene primaryScene;
    private static boolean weeklyBossModeRequested;
    private static String selectedTheme = PREFERENCES.get(THEME_PREFERENCE_KEY, BASE_THEME_CLASS);

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
        weeklyBossModeRequested = false;
        navigate("review.fxml", "CodeFit - Review");
    }

    public static void showWeeklyBossBattle() {
        weeklyBossModeRequested = true;
        navigate("review.fxml", "CodeFit - Weekly Boss Battle");
    }

    public static boolean consumeWeeklyBossModeRequest() {
        boolean requested = weeklyBossModeRequested;
        weeklyBossModeRequested = false;
        return requested;
    }

    public static void showSyllabus() {
        navigate("syllabus.fxml", "CodeFit - Java Backend Syllabus");
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
            if (primaryScene == null) {
                primaryScene = new Scene(root, 1100, 720);
                primaryScene.getStylesheets().add(NavigationService.class.getResource("/css/app.css").toExternalForm());
                primaryScene.setFill(Color.web("#070b16"));
                primaryStage.setScene(primaryScene);
            } else {
                primaryScene.setRoot(root);
            }
            primaryStage.setTitle(title);
            primaryStage.setMinWidth(760);
            primaryStage.setMinHeight(560);
            primaryStage.show();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load screen: " + fxmlName, exception);
        }
    }

    public static List<String> getThemeDisplayNames() {
        return THEMES.stream().map(ThemeOption::displayName).toList();
    }

    public static String getCurrentThemeDisplayName() {
        return getThemeOption(selectedTheme).displayName();
    }

    public static String getThemeClassByDisplayName(String displayName) {
        return THEMES.stream()
                .filter(theme -> theme.displayName().equals(displayName))
                .findFirst()
                .orElse(getThemeOption(selectedTheme))
                .styleClass();
    }

    public static void setThemeByDisplayName(String displayName) {
        setTheme(getThemeClassByDisplayName(displayName));
    }

    public static void setTheme(String themeClass) {
        selectedTheme = getThemeOption(themeClass).styleClass();
        PREFERENCES.put(THEME_PREFERENCE_KEY, selectedTheme);

        if (primaryStage != null && primaryStage.getScene() != null) {
            applyTheme(primaryStage.getScene().getRoot());
        }
    }

    public static String getTheme() {
        return selectedTheme;
    }

    private static void applyTheme(Parent root) {
        root.getStyleClass().removeAll(THEMES.stream().map(ThemeOption::styleClass).toArray(String[]::new));

        // Always apply the complete base token set first; alternate themes then override the
        // full semantic checklist documented in app.css so every FXML screen inherits a stable
        // background/surface/text/status/focus palette without missing-token fallbacks.
        root.getStyleClass().add(BASE_THEME_CLASS);
        if (!selectedTheme.equals(BASE_THEME_CLASS)) {
            root.getStyleClass().add(selectedTheme);
        }
    }

    private static ThemeOption getThemeOption(String themeClass) {
        return THEMES.stream()
                .filter(theme -> theme.styleClass().equals(themeClass))
                .findFirst()
                .orElse(THEMES.getFirst());
    }

    private record ThemeOption(String displayName, String styleClass) {
    }
}

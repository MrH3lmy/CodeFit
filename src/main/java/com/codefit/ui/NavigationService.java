package com.codefit.ui;

import com.codefit.model.ReflectionType;
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
    /** Only Dark and Light are selectable. Legacy Ocean/Forest/Synthwave preferences are mapped
     *  onto Dark by {@link #sanitizeThemeClass} so old preference files never fail to start. */
    private static final List<ThemeOption> THEMES = List.of(
            new ThemeOption("Dark", "theme-dark"),
            new ThemeOption("Light", "theme-light")
    );
    private static final String BASE_THEME_CLASS = "theme-dark";
    private static final String THEME_PREFERENCE_KEY = "selectedTheme";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(NavigationService.class);
    static final String[] STYLESHEETS = {
            "/css/tokens.css",
            "/css/base.css",
            "/css/controls.css",
            "/css/shell.css",
            "/css/review.css",
            "/css/library.css",
            "/css/forms.css",
            "/css/progress.css",
            "/css/today.css"
    };

    private static Stage primaryStage;
    private static Scene primaryScene;
    private static AppShellController shellController;
    private static boolean weeklyBossModeRequested;
    private static Integer pendingSessionMinutes;
    private static ReflectionType pendingReflectionType;
    private static Long pendingEditCardId;
    private static String selectedTheme;

    static {
        String storedTheme = PREFERENCES.get(THEME_PREFERENCE_KEY, BASE_THEME_CLASS);
        selectedTheme = sanitizeThemeClass(storedTheme);
        if (!selectedTheme.equals(storedTheme)) {
            PREFERENCES.put(THEME_PREFERENCE_KEY, selectedTheme);
        }
    }

    private NavigationService() {
    }

    public static void setPrimaryStage(Stage stage) {
        primaryStage = stage;
    }

    public static void showDashboard() {
        navigate(Route.TODAY);
    }

    public static void showDecks() {
        navigate(Route.LIBRARY);
    }

    public static void showAddCard() {
        pendingEditCardId = null;
        navigate(Route.ADD_CARD);
    }

    /** Opens the reflection capture/preview screen (#102) pre-selected to the workflow the learner
     *  picked from the post-review reflection prompt. */
    public static void showReflectionCapture(ReflectionType reflectionType) {
        pendingReflectionType = reflectionType;
        navigate(Route.REFLECTION);
    }

    public static ReflectionType consumePendingReflectionType() {
        ReflectionType reflectionType = pendingReflectionType;
        pendingReflectionType = null;
        return reflectionType;
    }

    /** Opens the card composer in edit mode for an existing card. */
    public static void showEditCard(long cardId) {
        pendingEditCardId = cardId;
        navigate(Route.ADD_CARD);
    }

    public static Long consumePendingEditCardId() {
        Long cardId = pendingEditCardId;
        pendingEditCardId = null;
        return cardId;
    }

    public static void showReview() {
        weeklyBossModeRequested = false;
        pendingSessionMinutes = null;
        navigate(Route.REVIEW);
    }

    /** Starts a time-budgeted adaptive session instead of the card-count workload mode. */
    public static void showTimedReview(int minutes) {
        weeklyBossModeRequested = false;
        pendingSessionMinutes = minutes;
        navigate(Route.REVIEW);
    }

    public static void showWeeklyBossBattle() {
        weeklyBossModeRequested = true;
        pendingSessionMinutes = null;
        navigate(Route.REVIEW);
    }

    public static boolean consumeWeeklyBossModeRequest() {
        boolean requested = weeklyBossModeRequested;
        weeklyBossModeRequested = false;
        return requested;
    }

    /** Returns the requested session minutes, or null if the learner didn't pick a timed session. */
    public static Integer consumeSessionMinutesRequest() {
        Integer minutes = pendingSessionMinutes;
        pendingSessionMinutes = null;
        return minutes;
    }

    public static void showSyllabus() {
        navigate(Route.SYLLABUS);
    }

    /** A weekly transfer assessment is a distinct, separately tracked activity from normal review
     *  (#104), so it gets its own route rather than reusing the review screen's session state. */
    public static void showWeeklyAssessment() {
        navigate(Route.ASSESSMENT);
    }

    public static void showStats() {
        navigate(Route.PROGRESS);
    }

    public static void showSettings() {
        navigate(Route.SETTINGS);
    }

    /** Navigates to a route, building the persistent shell on first use. Only the shell's content
     *  host is swapped on subsequent calls, so the sidebar and top bar are never reconstructed. */
    public static void navigate(Route route) {
        if (primaryStage == null) {
            throw new IllegalStateException("Primary stage has not been configured.");
        }
        ensureShell();
        shellController.showRoute(route);
        primaryStage.setTitle(route.title());
        primaryStage.show();
    }

    private static void ensureShell() {
        if (primaryScene != null) {
            return;
        }
        try {
            URL shellResource = NavigationService.class.getResource("/fxml/app-shell.fxml");
            FXMLLoader loader = new FXMLLoader(shellResource);
            Parent shellRoot = loader.load();
            shellController = loader.getController();
            applyTheme(shellRoot);

            primaryScene = new Scene(shellRoot, 1100, 720);
            for (String stylesheet : STYLESHEETS) {
                primaryScene.getStylesheets().add(NavigationService.class.getResource(stylesheet).toExternalForm());
            }
            primaryScene.setFill(Color.web("#0B0D12"));
            primaryStage.setScene(primaryScene);
            primaryStage.setMinWidth(760);
            primaryStage.setMinHeight(560);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load application shell.", exception);
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

        // Always apply the complete base token set first; the selected theme then overrides the
        // eleven semantic tokens defined in tokens.css so every screen inherits a stable palette.
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

    /** Maps any unrecognized or legacy theme class (Ocean/Forest/Synthwave) onto Dark so an old
     *  preference file can never prevent startup. */
    static String sanitizeThemeClass(String themeClass) {
        return THEMES.stream()
                .filter(theme -> theme.styleClass().equals(themeClass))
                .findFirst()
                .map(ThemeOption::styleClass)
                .orElse(BASE_THEME_CLASS);
    }

    private record ThemeOption(String displayName, String styleClass) {
    }
}

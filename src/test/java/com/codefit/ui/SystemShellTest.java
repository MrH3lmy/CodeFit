package com.codefit.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.testsupport.IsolatedDatabaseExtension;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.concurrent.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class SystemShellTest {
    @BeforeAll
    static void toolkit() {
        Assumptions.assumeTrue(FxToolkitSupport.isAvailable());
    }

    @Test
    void shellSurvivesNavigationThemeChangeAndFocusMode() throws Exception {
        fx(
                () -> {
                    String theme = NavigationService.getTheme();
                    Stage stage = new Stage();
                    try {
                        NavigationService.setPrimaryStage(stage);
                        NavigationService.navigate(Route.TODAY);
                        Parent root = stage.getScene().getRoot();
                        var sidebar = root.lookup("#sidebar");
                        var topbar = root.lookup("#topBar");
                        root.applyCss();
                        root.layout();
                        assertNotNull(root.lookup(".system-hero"));
                        assertNotNull(root.lookup(".dashboard-learning-hero"));
                        assertTrue(root.getStyleClass().contains("app-shell"));
                        NavigationService.navigate(Route.SETTINGS);
                        root.applyCss();
                        root.layout();
                        assertSame(sidebar, root.lookup("#sidebar"));
                        assertTrue(
                                root.lookup("#settingsButton")
                                        .getStyleClass()
                                        .contains("nav-item-active"));
                        ((ChoiceBox<String>) root.lookup("#themeChoiceBox")).setValue("Light");
                        assertTrue(root.getStyleClass().contains("theme-light"));
                        NavigationService.showAddCard();
                        assertTrue(
                                root.lookupAll(".nav-item-active").isEmpty(),
                                "Global action must not claim another route is current");
                        NavigationService.navigate(Route.REVIEW);
                        assertFalse(sidebar.isVisible());
                        assertFalse(sidebar.isManaged());
                        assertFalse(topbar.isVisible());
                        NavigationService.navigate(Route.LIBRARY);
                        assertSame(sidebar, root.lookup("#sidebar"));
                        assertSame(topbar, root.lookup("#topBar"));
                        assertTrue(sidebar.isVisible());
                        assertTrue(topbar.isVisible());
                        assertTrue(
                                root.lookup("#libraryButton")
                                        .getStyleClass()
                                        .contains("nav-item-active"));
                        return null;
                    } finally {
                        NavigationService.setTheme(theme);
                        stage.hide();
                        for (String name :
                                List.of("primaryStage", "primaryScene", "shellController")) {
                            var field = NavigationService.class.getDeclaredField(name);
                            field.setAccessible(true);
                            field.set(null, null);
                        }
                    }
                });
    }

    @Test
    void compactShellRetainsLabelsAndScrollableNavigationAtMinimumSize() throws Exception {
        fx(
                () -> {
                    FXMLLoader loader =
                            new FXMLLoader(getClass().getResource("/fxml/app-shell.fxml"));
                    Parent root = loader.load();
                    Scene scene = themed(root, "theme-dark", 760, 560);
                    root.applyCss();
                    root.layout();
                    assertTrue(root.getStyleClass().contains("shell-narrow"));
                    Sidebar sidebar = (Sidebar) root.lookup("#sidebar");
                    assertEquals(168, sidebar.getWidth(), 1);
                    assertTrue(root.lookup("#contentHost").getBoundsInParent().getWidth() >= 590);
                    for (String id :
                            List.of(
                                    "today",
                                    "evolution",
                                    "review",
                                    "library",
                                    "problems",
                                    "interview",
                                    "progress",
                                    "peers",
                                    "settings")) {
                        Button button = (Button) root.lookup("#" + id + "Button");
                        assertFalse(button.getText().isBlank());
                        assertTrue(button.isFocusTraversable());
                        assertFalse(button.isDisabled());
                        assertTrue(button.getHeight() >= 40);
                        assertNotNull(button.getGraphic());
                        assertEquals(button.getText(), button.getAccessibleText());
                    }
                    ScrollPane nav = (ScrollPane) root.lookup(".sidebar-scroll");
                    assertTrue(nav.isFitToWidth());
                    assertTrue(
                            nav.getContent().getBoundsInLocal().getHeight()
                                    > nav.getViewportBounds().getHeight(),
                            "Short windows must scroll, not crush navigation");
                    // Reattach to a desktop scene: the old scene's width listener must be removed.
                    scene.setRoot(new VBox());
                    themed(root, "theme-dark", 1280, 900);
                    root.applyCss();
                    root.layout();
                    assertFalse(sidebar.getStyleClass().contains("sidebar-narrow"));
                    assertEquals(220, sidebar.getWidth(), 1);
                    return null;
                });
    }

    @Test
    void todayHeroAndActionsFitTheNarrowContentColumn() throws Exception {
        fx(
                () -> {
                    Parent dashboard =
                            new FXMLLoader(getClass().getResource("/fxml/dashboard.fxml")).load();
                    VBox root = new VBox(dashboard);
                    root.getStyleClass().add("shell-narrow");
                    VBox.setVgrow(dashboard, Priority.ALWAYS);
                    themed(root, "theme-dark", 592, 900);
                    root.applyCss();
                    root.layout();
                    assertNotNull(root.lookup(".system-hero"));
                    Button action = (Button) root.lookup("#primaryActionButton");
                    assertTrue(action.getStyleClass().contains("primary-button"));
                    for (String id :
                            List.of(
                                    "primaryActionButton",
                                    "reviewSessionMenuButton",
                                    "dueCountLabel")) {
                        var node = root.lookup("#" + id);
                        var bounds = node.localToScene(node.getBoundsInLocal());
                        assertTrue(
                                bounds.getMinX() >= 0 && bounds.getMaxX() <= 592,
                                id + " must fit at 760px shell width");
                    }
                    return null;
                });
    }

    @Test
    void sharedControlsHaveReadableContrastAndDistinctKeyboardFocusInBothThemes() throws Exception {
        for (String theme : List.of("theme-dark", "theme-light")) {
            fx(
                    () -> {
                        Button primary = new Button("Continue");
                        primary.getStyleClass().add("primary-button");
                        Label secondary = new Label("Evidence is not yet sufficient");
                        secondary.getStyleClass().add("muted");
                        Button inactive = new Button("Unavailable");
                        inactive.setDisable(true);
                        VBox root = new VBox(16, primary, secondary, inactive);
                        root.getStyleClass().add("app-root");
                        Stage stage = new Stage();
                        stage.setScene(themed(root, theme, 640, 300));
                        stage.show();
                        try {
                            root.applyCss();
                            root.layout();
                            assertTrue(
                                    contrast(
                                                    (Color) primary.getTextFill(),
                                                    (Color)
                                                            primary.getBackground()
                                                                    .getFills()
                                                                    .getFirst()
                                                                    .getFill())
                                            >= 4.5,
                                    theme + " primary ink");
                            assertTrue(
                                    contrast(
                                                    (Color) secondary.getTextFill(),
                                                    (Color)
                                                            root.getBackground()
                                                                    .getFills()
                                                                    .getFirst()
                                                                    .getFill())
                                            >= 4.5,
                                    theme + " secondary ink");
                            primary.requestFocus();
                            root.applyCss();
                            assertTrue(primary.isFocused());
                            assertEquals(
                                    2,
                                    primary.getBorder()
                                            .getStrokes()
                                            .getFirst()
                                            .getWidths()
                                            .getTop());
                            assertTrue(inactive.getOpacity() >= .75);
                            return null;
                        } finally {
                            stage.hide();
                        }
                    });
        }
    }

    @Test
    void statePanelsCommunicateWithoutMotionOrColorAlone() throws Exception {
        fx(
                () -> {
                    VBox root = new VBox();
                    for (var kind : SystemNotice.Kind.values())
                        root.getChildren()
                                .add(
                                        new SystemNotice(
                                                kind,
                                                "A specific state",
                                                "An explanation for the learner."));
                    TitledPane pane = new TitledPane("Inspect evidence", new Label("No evidence"));
                    root.getChildren().add(pane);
                    themed(root, "theme-dark", 760, 800);
                    root.applyCss();
                    root.layout();
                    assertFalse(
                            pane.isAnimated(),
                            "Shared expansion is reduced-motion safe by default");
                    assertEquals(5, root.lookupAll(".system-state").size());
                    for (var node : root.lookupAll(".system-state")) {
                        var labels =
                                ((VBox) node).getChildren().stream().map(n -> (Label) n).toList();
                        assertEquals(3, labels.size());
                        assertTrue(
                                labels.stream()
                                        .allMatch(l -> l.isWrapText() && !l.getText().isBlank()));
                    }
                    return null;
                });
    }

    static Scene themed(Parent root, String theme, double width, double height) {
        if (!root.getStyleClass().contains(theme)) root.getStyleClass().add(theme);
        Scene scene = new Scene(root, width, height);
        for (String sheet : NavigationService.STYLESHEETS)
            scene.getStylesheets().add(NavigationService.class.getResource(sheet).toExternalForm());
        return scene;
    }

    private static double contrast(Color a, Color b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    private static double luminance(Color c) {
        return .2126 * linear(c.getRed())
                + .7152 * linear(c.getGreen())
                + .0722 * linear(c.getBlue());
    }

    private static double linear(double v) {
        return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4);
    }

    static <T> T fx(Callable<T> work) throws Exception {
        FutureTask<T> task = new FutureTask<>(work);
        Platform.runLater(task);
        return task.get(30, TimeUnit.SECONDS);
    }
}

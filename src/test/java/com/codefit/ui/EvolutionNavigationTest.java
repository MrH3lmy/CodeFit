package com.codefit.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.testsupport.IsolatedDatabaseExtension;

import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.*;
import java.util.concurrent.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class EvolutionNavigationTest {
    @BeforeAll
    static void toolkit() {
        Assumptions.assumeTrue(FxToolkitSupport.isAvailable());
        Platform.setImplicitExit(false);
    }

    @Test
    void realShellSidebarOpensEvolutionThenReturnsToOrdinaryProblems() throws Exception {
        var oldTheme = NavigationService.getTheme();
        Stage stage =
                fx(
                        () -> {
                            var s = new Stage();
                            NavigationService.setPrimaryStage(s);
                            NavigationService.setTheme("theme-light");
                            NavigationService.navigate(Route.EVOLUTION);
                            s.setWidth(1280);
                            s.setHeight(900);
                            return s;
                        });
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (fx(
                    () -> ((ProgressIndicator) stage.getScene().lookup("#loading")).isVisible())) {
                assertTrue(
                        System.nanoTime() < deadline,
                        "Evolution route did not finish its bounded load");
                Thread.sleep(40);
            }
            fx(
                    () -> {
                        assertTrue(
                                stage.getScene()
                                        .lookup("#evolutionButton")
                                        .getStyleClass()
                                        .contains("nav-item-active"));
                        assertEquals(
                                "Evolution",
                                ((Label) stage.getScene().lookup("#topBarTitleLabel")).getText());
                        assertNotNull(stage.getScene().lookup("#sourceArea"));
                        for (String id :
                                java.util.List.of(
                                        "todayButton",
                                        "reviewButton",
                                        "libraryButton",
                                        "problemsButton",
                                        "interviewButton",
                                        "progressButton",
                                        "peersButton"))
                            assertFalse(stage.getScene().lookup("#" + id).isDisabled());
                        snapshot(stage.getScene().getRoot());
                        ((Button) stage.getScene().lookup("#problemsButton")).fire();
                        assertEquals(
                                "Problems",
                                ((Label) stage.getScene().lookup("#topBarTitleLabel")).getText());
                        assertNull(stage.getScene().lookup("#sourceArea"));
                        assertTrue(
                                stage.getScene()
                                        .lookup("#problemsButton")
                                        .getStyleClass()
                                        .contains("nav-item-active"));
                        return null;
                    });
        } finally {
            FxToolkitSupport.awaitProblemRefresh();
            fx(
                    () -> {
                        NavigationService.setTheme(oldTheme);
                        stage.hide();
                        for (String name :
                                java.util.List.of(
                                        "primaryStage", "primaryScene", "shellController")) {
                            var f = NavigationService.class.getDeclaredField(name);
                            f.setAccessible(true);
                            f.set(null, null);
                        }
                        return null;
                    });
        }
    }

    private static <T> T fx(Callable<T> operation) throws Exception {
        var task = new FutureTask<T>(operation);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void snapshot(Parent root) throws Exception {
        String dir = System.getProperty("codefit.visualDir");
        if (dir == null) return;
        root.applyCss();
        root.layout();
        var image = root.snapshot(new SnapshotParameters(), null);
        var png =
                new java.awt.image.BufferedImage(
                        (int) image.getWidth(),
                        (int) image.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++)
            for (int x = 0; x < png.getWidth(); x++)
                png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        var path = Path.of(dir, "evolution-shell-light.png");
        Files.createDirectories(path.getParent());
        javax.imageio.ImageIO.write(png, "png", path.toFile());
    }
}

package com.codefit.ui;

import com.codefit.CodeFitApplication;
import com.codefit.config.DatabaseConfig;

import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.transform.Scale;
import javafx.stage.Stage;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in visual smoke launcher, not a learner route. Starts the actual application on a temporary
 * SQLite file. Run in its own JVM with a temporary java.util.prefs.userRoot; see the visual spec.
 * Component samples are explicitly labelled and never enter learning/evaluation services.
 */
public final class SystemVisualCapture {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path database = Files.createTempDirectory("codefit-system-visual-").resolve("visual.db");
        DatabaseConfig.useDatabaseFile(database);
        if (!FxToolkitSupport.isAvailable())
            throw new IllegalStateException("A real JavaFX display is required");
        CodeFitApplication app = new CodeFitApplication();
        Stage stage =
                SystemShellTest.fx(
                        () -> {
                            Stage s = new Stage();
                            app.start(s);
                            return s;
                        });
        try {
            for (String theme : List.of("dark", "light")) {
                SystemShellTest.fx(
                        () -> {
                            NavigationService.setTheme("theme-" + theme);
                            return null;
                        });
                for (int width : new int[] {1280, 760}) {
                    SystemShellTest.fx(
                            () -> {
                                stage.setWidth(width);
                                stage.setHeight(900);
                                NavigationService.navigate(Route.TODAY);
                                return null;
                            });
                    Thread.sleep(250); // let native resize/CSS pulses settle before the snapshot
                    capture(stage, output.resolve("today-" + theme + "-" + width + ".png"));
                    SystemShellTest.fx(
                            () -> {
                                NavigationService.navigate(Route.EVOLUTION);
                                return null;
                            });
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (SystemShellTest.fx(
                            () -> stage.getScene().lookup("#loading").isVisible())) {
                        if (System.nanoTime() > until)
                            throw new IllegalStateException("Evolution failed to finish loading");
                        Thread.sleep(50);
                    }
                    capture(stage, output.resolve("evolution-" + theme + "-" + width + ".png"));
                    // Dispose the async Evolution owner through normal navigation before showing
                    // specimens.
                    SystemShellTest.fx(
                            () -> {
                                NavigationService.navigate(Route.TODAY);
                                ((StackPane) stage.getScene().lookup("#contentHost"))
                                        .getChildren()
                                        .setAll(gallery());
                                ((Label) stage.getScene().lookup("#topBarTitleLabel"))
                                        .setText("Component reference · preview only");
                                return null;
                            });
                    capture(stage, output.resolve("components-" + theme + "-" + width + ".png"));
                    if (width == 1280) {
                        SystemShellTest.fx(
                                () -> {
                                    ((ScrollPane)
                                                    ((StackPane)
                                                                    stage.getScene()
                                                                            .lookup("#contentHost"))
                                                            .getChildren()
                                                            .getFirst())
                                            .setVvalue(1);
                                    return null;
                                });
                        capture(stage, output.resolve("states-" + theme + ".png"));
                    }
                }
            }
            SystemShellTest.fx(
                    () -> {
                        NavigationService.setTheme("theme-light");
                        stage.setWidth(760);
                        stage.getScene().getRoot().applyCss();
                        stage.getScene().getRoot().layout();
                        enlarge(stage.getScene().getRoot());
                        return null;
                    });
            capture(stage, output.resolve("components-light-760-text125.png"));
            System.out.println(
                    "Actual JavaFX app captured; output scale="
                            + SystemShellTest.fx(stage::getOutputScaleX)
                            + "; isolated temporary DB="
                            + database);
        } finally {
            SystemShellTest.fx(
                    () -> {
                        stage.hide();
                        return null;
                    });
            app.stop();
            javafx.application.Platform.exit();
        }
    }

    private static Parent gallery() {
        VBox hero =
                new VBox(
                        8,
                        label("SYSTEM / SHARED COMPONENTS", "system-eyebrow"),
                        label("A foundation for meaningful progress", "system-title"),
                        label(
                                "Visual specimens only. No learner metrics, awards or challenge"
                                        + " content.",
                                "muted"));
        hero.getStyleClass().add("system-hero");
        Button primary = new Button("Primary action");
        primary.getStyleClass().add("primary-button");
        Button secondary = new Button("Secondary action");
        secondary.getStyleClass().add("ghost-button");
        Button disabled = new Button("Unavailable");
        disabled.setDisable(true);
        FlowPane actions = new FlowPane(10, 10, primary, secondary, disabled);
        FlowPane badges =
                new FlowPane(
                        8,
                        8,
                        badge("LOCAL", "system-badge-info"),
                        badge("REWARD · SAMPLE", "system-badge-reward"),
                        badge("CHECK COMPLETE", "system-badge-success"));
        Button skill = new Button("◇  Skill node · insufficient evidence");
        skill.getStyleClass().addAll("skill-node", "skill-node-unavailable");
        skill.setWrapText(true);
        ProgressBar progress = new ProgressBar(.6);
        progress.setMaxWidth(Double.MAX_VALUE);
        VBox card =
                new VBox(
                        10,
                        label("MISSION CARD / PREVIEW", "section-title"),
                        label("Investigate an engineering problem", "topbar-title"),
                        label(
                                "The owning workflow supplies the objective, authorization and"
                                        + " evidence. This card adds no learning behavior.",
                                "muted"),
                        badges,
                        actions,
                        skill,
                        label("Example operation progress · not a skill estimate", "helper-text"),
                        progress);
        card.getStyleClass().add("mission-card");
        VBox body =
                new VBox(
                        16,
                        hero,
                        card,
                        new SystemNotice(
                                SystemNotice.Kind.EMPTY,
                                "No evidence yet",
                                "Missing evidence remains unknown. Choose a supported activity when"
                                        + " you are ready."),
                        new SystemNotice(
                                SystemNotice.Kind.LOADING,
                                "Reading saved state",
                                "Static loading feedback; no motion is needed to understand this"
                                        + " state."),
                        new SystemNotice(
                                SystemNotice.Kind.ERROR,
                                "Saved operation needs attention",
                                "A specific explanation and recovery action belong here. This is a"
                                        + " component specimen."),
                        new SystemNotice(
                                SystemNotice.Kind.SUCCESS,
                                "Operation complete",
                                "Completion describes the operation, not skill mastery."));
        body.getStyleClass().add("content");
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("content-scroll");
        return scroll;
    }

    private static Label label(String text, String style) {
        Label l = new Label(text);
        l.getStyleClass().add(style);
        l.setWrapText(true);
        return l;
    }

    private static Label badge(String text, String style) {
        Label l = label(text, "system-badge");
        l.getStyleClass().add(style);
        return l;
    }

    private static void enlarge(Node node) {
        if (node instanceof Labeled l)
            l.setStyle(l.getStyle() + "; -fx-font-size: " + l.getFont().getSize() * 1.25 + "px;");
        if (node instanceof Parent p)
            p.getChildrenUnmodifiable().forEach(SystemVisualCapture::enlarge);
    }

    private static void capture(Stage stage, Path file) throws Exception {
        Thread.sleep(120);
        SystemShellTest.fx(
                () -> {
                    Parent root = stage.getScene().getRoot();
                    root.applyCss();
                    root.layout();
                    SnapshotParameters params = new SnapshotParameters();
                    params.setTransform(
                            new Scale(stage.getOutputScaleX(), stage.getOutputScaleY()));
                    var image = root.snapshot(params, null);
                    var png =
                            new java.awt.image.BufferedImage(
                                    (int) image.getWidth(),
                                    (int) image.getHeight(),
                                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for (int y = 0; y < png.getHeight(); y++)
                        for (int x = 0; x < png.getWidth(); x++)
                            png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                    javax.imageio.ImageIO.write(png, "png", file.toFile());
                    return null;
                });
    }
}

package com.codefit;

import com.codefit.config.DatabaseConfig;
import com.codefit.service.BackgroundImportExecutor;
import com.codefit.service.CompileOutcomeRegistry;
import com.codefit.ui.NavigationService;
import javafx.application.Application;
import javafx.stage.Stage;

import java.util.concurrent.TimeUnit;

public class CodeFitApplication extends Application {
    @Override
    public void start(Stage stage) {
        DatabaseConfig.initialize();
        NavigationService.setPrimaryStage(stage);
        NavigationService.showDashboard();
    }

    /**
     * Gracefully stops {@link BackgroundImportExecutor}'s worker thread on normal application exit
     * (#160) — its non-daemon thread would otherwise keep the JVM alive indefinitely if this were
     * skipped. {@code NavigationService}'s close-request confirmation is the user-facing warning; this
     * is the actual shutdown once the learner has decided to quit.
     *
     * <p>Also closes whatever {@link com.codefit.service.CompileOutcome} the Java runner last left
     * registered (#163) — quitting with a compiled-but-not-recompiled solution open would otherwise
     * leak its temporary work directory under the OS temp dir forever.
     */
    @Override
    public void stop() {
        BackgroundImportExecutor.shutdown(10, TimeUnit.SECONDS);
        CompileOutcomeRegistry.closeCurrent();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

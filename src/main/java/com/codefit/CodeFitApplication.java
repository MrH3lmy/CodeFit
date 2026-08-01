package com.codefit;

import com.codefit.config.DatabaseConfig;
import com.codefit.service.BackgroundImportExecutor;
import com.codefit.service.CompileOutcomeRegistry;
import com.codefit.service.JavaExecutionCoordinator;
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
     * Gracefully stops background work on normal application exit. Active Java executions are
     * cancelled and given a bounded period to release the compiled work directory before that
     * directory is closed; the import worker receives its existing graceful shutdown window.
     */
    @Override
    public void stop() {
        // Compilation itself is not cancellable and has a 15-second timeout, so this window must be
        // longer than that timeout before the compiled directory is allowed to close.
        JavaExecutionCoordinator.cancelActiveAndAwait(20, TimeUnit.SECONDS);
        BackgroundImportExecutor.shutdown(10, TimeUnit.SECONDS);
        CompileOutcomeRegistry.closeCurrent();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

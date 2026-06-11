package com.codefit;

import com.codefit.config.DatabaseConfig;
import com.codefit.ui.NavigationService;
import javafx.application.Application;
import javafx.stage.Stage;

public class CodeFitApplication extends Application {
    @Override
    public void start(Stage stage) {
        DatabaseConfig.initialize();
        NavigationService.setPrimaryStage(stage);
        NavigationService.showDashboard();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

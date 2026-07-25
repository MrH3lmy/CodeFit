package com.codefit.controller;

import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.UserProgress;
import com.codefit.service.FocusPreferenceService;
import com.codefit.service.ProgressService;
import com.codefit.ui.NavigationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;

import java.util.List;

public class SettingsController extends BaseController {
    private static final List<Integer> MATURE_INTERLEAVE_PERCENT_OPTIONS = List.of(0, 5, 10, 15, 20, 25, 30, 40, 50);

    @FXML private ChoiceBox<String> themeChoiceBox;
    @FXML private ChoiceBox<DailyWorkloadMode> workloadModeChoiceBox;
    @FXML private Label workloadModeDetailLabel;
    @FXML private ChoiceBox<Integer> matureInterleavePercentChoiceBox;
    @FXML private Label matureInterleavePercentDetailLabel;

    private final ProgressService progressService = new ProgressService();
    private final FocusPreferenceService focusPreferenceService = new FocusPreferenceService();

    @FXML
    public void initialize() {
        configureThemePreference();
        configureWorkloadPreference();
        configureMatureInterleavePreference();
    }

    private void configureMatureInterleavePreference() {
        UserProgress progress = focusPreferenceService.getPreference();
        matureInterleavePercentChoiceBox.setItems(FXCollections.observableArrayList(MATURE_INTERLEAVE_PERCENT_OPTIONS));
        int currentPercent = progress.getMatureInterleavePercent();
        matureInterleavePercentChoiceBox.setValue(
                MATURE_INTERLEAVE_PERCENT_OPTIONS.contains(currentPercent) ? currentPercent : UserProgress.DEFAULT_MATURE_INTERLEAVE_PERCENT);
        updateMatureInterleaveDetail(matureInterleavePercentChoiceBox.getValue());
        matureInterleavePercentChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldPercent, newPercent) -> {
            if (newPercent == null || newPercent.equals(oldPercent)) {
                return;
            }
            focusPreferenceService.setMatureInterleavePercent(newPercent);
            updateMatureInterleaveDetail(newPercent);
        });
    }

    private void updateMatureInterleaveDetail(int percent) {
        matureInterleavePercentDetailLabel.setText(percent + "% of leftover session time goes to mature cards from other modules.");
    }

    private void configureThemePreference() {
        themeChoiceBox.getItems().setAll(NavigationService.getThemeDisplayNames());
        themeChoiceBox.setValue(NavigationService.getCurrentThemeDisplayName());
        themeChoiceBox.valueProperty().addListener((observable, oldTheme, newTheme) -> {
            if (newTheme != null && !newTheme.equals(oldTheme)) {
                NavigationService.setThemeByDisplayName(newTheme);
            }
        });
    }

    private void configureWorkloadPreference() {
        UserProgress progress = progressService.getProgress();
        workloadModeChoiceBox.setItems(FXCollections.observableArrayList(DailyWorkloadMode.values()));
        workloadModeChoiceBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(DailyWorkloadMode mode) {
                return mode == null ? "Normal" : mode.getDisplayName();
            }

            @Override
            public DailyWorkloadMode fromString(String value) {
                return DailyWorkloadMode.fromDatabaseValue(value);
            }
        });
        workloadModeChoiceBox.setValue(progress.getDailyWorkloadMode());
        updateWorkloadModeDetail(progress.getDailyWorkloadMode());
        workloadModeChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldMode, newMode) -> {
            if (newMode == null || newMode == oldMode) {
                return;
            }
            UserProgress latestProgress = progressService.getProgress();
            latestProgress.setDailyWorkloadMode(newMode);
            progressService.saveProgress(latestProgress);
            updateWorkloadModeDetail(newMode);
        });
    }

    private void updateWorkloadModeDetail(DailyWorkloadMode mode) {
        workloadModeDetailLabel.setText(mode.getSummary() + " · quest target " + mode.getDueReviewQuestTarget() + " due reviews");
    }
}

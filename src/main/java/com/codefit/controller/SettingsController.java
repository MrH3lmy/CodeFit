package com.codefit.controller;

import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.UserProgress;
import com.codefit.service.FocusPreferenceService;
import com.codefit.service.GuidedTrainingService;
import com.codefit.service.ProgressService;
import com.codefit.service.TrainingSheetImportService;
import com.codefit.service.TrainingSheetImportSummary;
import com.codefit.service.WorkbookImportException;
import com.codefit.ui.NavigationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;

public class SettingsController extends BaseController {
    private static final List<Integer> MATURE_INTERLEAVE_PERCENT_OPTIONS = List.of(0, 5, 10, 15, 20, 25, 30, 40, 50);
    private static final List<Integer> GUIDED_SESSION_MINUTES_OPTIONS = List.of(5, 10, 15, 20, 30, 45);
    private static final List<Integer> DAILY_NEW_CARD_LIMIT_OPTIONS = List.of(0, 1, 2, 3, 4, 5, 8, 10);

    @FXML private ChoiceBox<String> themeChoiceBox;
    @FXML private ChoiceBox<DailyWorkloadMode> workloadModeChoiceBox;
    @FXML private Label workloadModeDetailLabel;
    @FXML private ChoiceBox<Integer> matureInterleavePercentChoiceBox;
    @FXML private Label matureInterleavePercentDetailLabel;
    @FXML private ChoiceBox<Integer> guidedSessionMinutesChoiceBox;
    @FXML private ChoiceBox<Integer> dailyNewCardLimitChoiceBox;
    @FXML private Label guidedRoutineDetailLabel;

    private final ProgressService progressService = new ProgressService();
    private final FocusPreferenceService focusPreferenceService = new FocusPreferenceService();
    private final GuidedTrainingService guidedTrainingService = new GuidedTrainingService();
    private final TrainingSheetImportService trainingSheetImportService = new TrainingSheetImportService();

    @FXML
    public void initialize() {
        configureThemePreference();
        configureWorkloadPreference();
        configureMatureInterleavePreference();
        configureGuidedRoutinePreference();
    }

    /**
     * Local-only workbook import (#143): the file never leaves the machine. Re-importing the same
     * workbook is always safe — {@link TrainingSheetImportService} never creates duplicate
     * problems/memberships and never downgrades progress the learner has already recorded.
     */
    @FXML
    public void importTrainingSheet() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Training Sheet");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel workbook", "*.xlsx"));
        File file = fileChooser.showOpenDialog(settingsWindow());
        if (file == null) {
            return;
        }

        try {
            TrainingSheetImportSummary summary = trainingSheetImportService.importWorkbook(file.toPath());
            showImportResult("Training Sheet Import", summary, Alert.AlertType.INFORMATION);
        } catch (WorkbookImportException exception) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Training Sheet Import");
            alert.setHeaderText("Import failed; no changes were made");
            alert.setContentText(exception.getMessage());
            alert.showAndWait();
        }
    }

    private void showImportResult(String title, TrainingSheetImportSummary summary, Alert.AlertType alertType) {
        Alert alert = new Alert(alertType);
        alert.setTitle(title);
        alert.setHeaderText(summary.dryRun() ? "Preview complete" : "Import complete");
        String details = summary.warnings().isEmpty()
                ? summary.message()
                : summary.message() + "\n\n" + String.join("\n", summary.warnings());
        alert.setContentText(details);
        alert.showAndWait();
    }

    private Window settingsWindow() {
        return themeChoiceBox.getScene() == null ? null : themeChoiceBox.getScene().getWindow();
    }

    /** Session length and new-card cap for the guided daily routine (#111) — the same knobs
     *  {@link com.codefit.service.ReviewService} and {@link GuidedTrainingService} already read,
     *  exposed here instead of a second preferences system. */
    private void configureGuidedRoutinePreference() {
        guidedSessionMinutesChoiceBox.setItems(FXCollections.observableArrayList(GUIDED_SESSION_MINUTES_OPTIONS));
        int currentMinutes = guidedTrainingService.getPreferredSessionMinutes();
        guidedSessionMinutesChoiceBox.setValue(
                GUIDED_SESSION_MINUTES_OPTIONS.contains(currentMinutes) ? currentMinutes : UserProgress.DEFAULT_GUIDED_SESSION_MINUTES);

        dailyNewCardLimitChoiceBox.setItems(FXCollections.observableArrayList(DAILY_NEW_CARD_LIMIT_OPTIONS));
        int currentLimit = guidedTrainingService.getPreferredDailyNewCardLimit();
        dailyNewCardLimitChoiceBox.setValue(
                DAILY_NEW_CARD_LIMIT_OPTIONS.contains(currentLimit) ? currentLimit : UserProgress.DEFAULT_DAILY_NEW_CARD_LIMIT);

        updateGuidedRoutineDetail();
        guidedSessionMinutesChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.equals(oldValue)) {
                return;
            }
            guidedTrainingService.setPreferredSessionMinutes(newValue);
            updateGuidedRoutineDetail();
        });
        dailyNewCardLimitChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue == null || newValue.equals(oldValue)) {
                return;
            }
            guidedTrainingService.setPreferredDailyNewCardLimit(newValue);
            updateGuidedRoutineDetail();
        });
    }

    private void updateGuidedRoutineDetail() {
        guidedRoutineDetailLabel.setText("Start Today's Training runs a " + guidedSessionMinutesChoiceBox.getValue()
                + "-minute session and introduces at most " + dailyNewCardLimitChoiceBox.getValue()
                + " new " + (dailyNewCardLimitChoiceBox.getValue() == 1 ? "card" : "cards") + " per day.");
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

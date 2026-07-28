package com.codefit.controller;

import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.ImportBatch;
import com.codefit.model.UserProgress;
import com.codefit.service.FocusPreferenceService;
import com.codefit.service.GuidedTrainingService;
import com.codefit.service.ImportSourceMetadata;
import com.codefit.service.ProgressService;
import com.codefit.service.TrainingSheetImportService;
import com.codefit.service.TrainingSheetImportSummary;
import com.codefit.service.WorkbookImportException;
import com.codefit.service.WorkbookPreviewReportFormatter;
import com.codefit.service.WorkbookValidationResult;
import com.codefit.ui.NavigationService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.time.format.DateTimeFormatter;
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
    @FXML private TextField importSourceNameField;
    @FXML private TextField importSourceUrlField;
    @FXML private TextField importAuthorField;
    @FXML private TextField importVersionField;
    @FXML private Button importTrainingSheetButton;
    @FXML private Label importStatusLabel;
    @FXML private Label importBatchesEmptyLabel;
    @FXML private VBox importBatchesBox;

    private static final DateTimeFormatter IMPORT_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy");

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
        refreshImportBatches();
    }

    private static final ButtonType IMPORT_NOW_BUTTON = new ButtonType("Import Now", ButtonBar.ButtonData.OK_DONE);
    private static final ButtonType COPY_REPORT_BUTTON = new ButtonType("Copy Report", ButtonBar.ButtonData.LEFT);

    /**
     * Local-only workbook import (#159/#160): the file never leaves the machine. Selecting a workbook
     * only ever analyzes it — see {@link #analyzeWorkbookInBackground} — a real import only happens if
     * the learner explicitly confirms after reviewing the preview report. Re-importing the same
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
        analyzeWorkbookInBackground(file);
    }

    /**
     * Step 1, "Analyze workbook": runs the exact same row-by-row logic a real import would (see
     * {@link TrainingSheetImportService#preview}), rolling back every write. The real workbook has
     * ~926 rows across seven sheets, so parsing and analyzing it runs on a background thread —
     * blocking the JavaFX Application Thread here would freeze the whole UI for the duration — with
     * the result marshalled back via {@link Platform#runLater}. Step 2, "review and confirm", happens
     * on {@link #onWorkbookAnalyzed}: the learner reads the report and explicitly clicks "Import Now"
     * before anything is written — cancelling (closing the dialog any other way) leaves the database
     * exactly as it was.
     */
    private void analyzeWorkbookInBackground(File file) {
        setImportBusy(true, "Analyzing \"" + file.getName() + "\"… this can take a few seconds for a large workbook.");
        Thread analyzeThread = new Thread(() -> {
            TrainingSheetImportSummary preview = null;
            WorkbookImportException failure = null;
            WorkbookValidationResult validation = null;
            try {
                preview = trainingSheetImportService.preview(file.toPath());
            } catch (WorkbookImportException exception) {
                failure = exception;
                validation = safeValidate(file);
            } catch (RuntimeException exception) {
                failure = new WorkbookImportException(exception.getMessage(), exception);
            }
            TrainingSheetImportSummary finalPreview = preview;
            WorkbookImportException finalFailure = failure;
            WorkbookValidationResult finalValidation = validation;
            Platform.runLater(() -> {
                setImportBusy(false, null);
                if (finalFailure != null) {
                    showAnalysisFailedAlert(finalFailure, finalValidation);
                } else {
                    onWorkbookAnalyzed(file, finalPreview);
                }
            });
        }, "training-sheet-analyze");
        analyzeThread.setDaemon(true);
        analyzeThread.start();
    }

    private void showAnalysisFailedAlert(WorkbookImportException exception, WorkbookValidationResult validation) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Training Sheet Import");
        alert.setHeaderText("This workbook can't be analyzed; no changes were made");
        String detail = validation == null
                ? exception.getMessage()
                : exception.getMessage() + "\n\n" + String.join("\n", validation.structuralWarnings());
        alert.setContentText(detail);
        alert.showAndWait();
    }

    /** Shows the review-and-confirm dialog for a completed analysis, then — only if the learner
     *  explicitly clicks "Import Now" — runs the real import, also off the JavaFX Application Thread. */
    private void onWorkbookAnalyzed(File file, TrainingSheetImportSummary preview) {
        if (!showPreviewReportDialog(file.getName(), preview)) {
            return; // cancelled, or blocked by errors: the preview above already rolled back, nothing to undo
        }
        importWorkbookInBackground(file);
    }

    private void importWorkbookInBackground(File file) {
        setImportBusy(true, "Importing \"" + file.getName() + "\"…");
        ImportSourceMetadata sourceMetadata = new ImportSourceMetadata(blankToNull(importSourceNameField.getText()),
                blankToNull(importSourceUrlField.getText()), blankToNull(importAuthorField.getText()), blankToNull(importVersionField.getText()));
        Thread importThread = new Thread(() -> {
            TrainingSheetImportSummary summary = null;
            WorkbookImportException failure = null;
            try {
                summary = trainingSheetImportService.importWorkbook(file.toPath(), sourceMetadata);
            } catch (WorkbookImportException exception) {
                failure = exception;
            }
            TrainingSheetImportSummary finalSummary = summary;
            WorkbookImportException finalFailure = failure;
            Platform.runLater(() -> {
                setImportBusy(false, null);
                if (finalFailure != null) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Training Sheet Import");
                    alert.setHeaderText("Import failed; no changes were made");
                    alert.setContentText(finalFailure.getMessage());
                    alert.showAndWait();
                } else {
                    showImportCompleteDialog(file.getName(), finalSummary);
                    refreshImportBatches();
                }
            });
        }, "training-sheet-import");
        importThread.setDaemon(true);
        importThread.start();
    }

    private void setImportBusy(boolean busy, String statusText) {
        importTrainingSheetButton.setDisable(busy);
        importStatusLabel.setText(statusText == null ? "" : statusText);
        importStatusLabel.setVisible(statusText != null);
        importStatusLabel.setManaged(statusText != null);
    }

    private WorkbookValidationResult safeValidate(File file) {
        try {
            return trainingSheetImportService.validate(file.toPath());
        } catch (RuntimeException validationAlsoFailed) {
            return null;
        }
    }

    /**
     * The "Preview & Diagnostics" screen (#160): the plain-text report plus every diagnostic's
     * severity. "Import Now" is disabled whenever the workbook has a BLOCKING diagnostic (nothing
     * importable at all) — the learner can still read the report and copy it, but there's nothing to
     * confirm.
     *
     * @return {@code true} if the learner clicked "Import Now", {@code false} for cancel/close/blocked.
     */
    private boolean showPreviewReportDialog(String workbookFileName, TrainingSheetImportSummary preview) {
        String reportText = WorkbookPreviewReportFormatter.format(workbookFileName, preview);
        boolean blocked = preview.hasBlockingDiagnostics();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Training Sheet Import — Preview");
        dialog.setHeaderText(blocked
                ? "\"" + workbookFileName + "\" has blocking errors and can't be imported — nothing has been written."
                : "Review \"" + workbookFileName + "\" before importing — nothing has been written yet.");

        TextArea reportArea = new TextArea(reportText);
        reportArea.setEditable(false);
        reportArea.setWrapText(false);
        reportArea.setPrefColumnCount(72);
        reportArea.setPrefRowCount(24);

        DialogPane pane = dialog.getDialogPane();
        pane.setContent(reportArea);
        pane.getButtonTypes().addAll(COPY_REPORT_BUTTON, ButtonType.CANCEL, IMPORT_NOW_BUTTON);

        Button copyButton = (Button) pane.lookupButton(COPY_REPORT_BUTTON);
        copyButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(reportText);
            Clipboard.getSystemClipboard().setContent(content);
            event.consume(); // stay open after copying
        });

        Button importButton = (Button) pane.lookupButton(IMPORT_NOW_BUTTON);
        importButton.setDisable(blocked);

        return dialog.showAndWait().filter(button -> button == IMPORT_NOW_BUTTON).isPresent();
    }

    /** Links directly to the Problem Library so a learner can see what just landed in it. */
    private void showImportCompleteDialog(String workbookFileName, TrainingSheetImportSummary summary) {
        String reportText = WorkbookPreviewReportFormatter.format(workbookFileName, summary);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Training Sheet Import — Complete");
        dialog.setHeaderText("Imported \"" + workbookFileName + "\".");

        TextArea reportArea = new TextArea(reportText);
        reportArea.setEditable(false);
        reportArea.setPrefColumnCount(72);
        reportArea.setPrefRowCount(20);

        ButtonType goToLibraryButton = new ButtonType("Go to Problem Library", ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.setContent(reportArea);
        pane.getButtonTypes().addAll(ButtonType.CLOSE, goToLibraryButton);

        if (dialog.showAndWait().filter(button -> button == goToLibraryButton).isPresent()) {
            NavigationService.showProblems();
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    /** Lists every import batch (#149) so a learner can see where their roadmap data came from and,
     *  if needed, remove one — deleting only its roadmap positions, never progress or flashcards. */
    private void refreshImportBatches() {
        importBatchesBox.getChildren().clear();
        List<ImportBatch> batches = trainingSheetImportService.listImportBatches();
        importBatchesEmptyLabel.setVisible(batches.isEmpty());
        importBatchesEmptyLabel.setManaged(batches.isEmpty());
        batches.forEach(batch -> importBatchesBox.getChildren().add(createImportBatchRow(batch)));
    }

    private HBox createImportBatchRow(ImportBatch batch) {
        StringBuilder subtitle = new StringBuilder(batch.getImportedAt().format(IMPORT_DATE_FORMAT));
        if (batch.getAuthor() != null && !batch.getAuthor().isBlank()) {
            subtitle.append(" • ").append(batch.getAuthor());
        }
        if (batch.getVersion() != null && !batch.getVersion().isBlank()) {
            subtitle.append(" • v").append(batch.getVersion());
        }

        Label nameLabel = new Label(batch.getSourceName());
        nameLabel.getStyleClass().add("problem-row-title");
        nameLabel.setWrapText(true);
        Label subtitleLabel = new Label(subtitle.toString());
        subtitleLabel.getStyleClass().add("problem-row-subtitle");
        subtitleLabel.setWrapText(true);
        VBox textColumn = new VBox(2, nameLabel, subtitleLabel);
        textColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textColumn, Priority.ALWAYS);

        Button deleteButton = new Button("Delete Roadmap…");
        deleteButton.getStyleClass().add("ghost-button");
        deleteButton.setOnAction(event -> confirmAndDeleteImportBatch(batch));

        HBox row = new HBox(10, textColumn, deleteButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("deck-row");
        return row;
    }

    private void confirmAndDeleteImportBatch(ImportBatch batch) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Imported Roadmap");
        confirm.setHeaderText("Delete the roadmap positions imported from \"" + batch.getSourceName() + "\"?");
        confirm.setContentText("Solved/in-progress records and any flashcards you've created are never affected — "
                + "only this import's roadmap positions are removed.");
        if (confirm.showAndWait().filter(button -> button == ButtonType.OK).isEmpty()) {
            return;
        }
        int removed = trainingSheetImportService.deleteImportBatch(batch.getId());
        refreshImportBatches();
        Alert result = new Alert(Alert.AlertType.INFORMATION);
        result.setTitle("Delete Imported Roadmap");
        result.setHeaderText("Removed " + removed + " roadmap " + (removed == 1 ? "position" : "positions") + ".");
        result.showAndWait();
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

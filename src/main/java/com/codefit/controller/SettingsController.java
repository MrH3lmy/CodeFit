package com.codefit.controller;

import com.codefit.model.DailyWorkloadMode;
import com.codefit.model.ImportBatch;
import com.codefit.model.UserProgress;
import com.codefit.service.AnalyzedTrainingWorkbook;
import com.codefit.service.BackgroundImportExecutor;
import com.codefit.service.FocusPreferenceService;
import com.codefit.service.GuidedTrainingService;
import com.codefit.service.ImportSourceMetadata;
import com.codefit.service.ProgressService;
import com.codefit.service.TrainingSheetDiagnostic;
import com.codefit.service.TrainingSheetDiagnosticSeverity;
import com.codefit.service.TrainingSheetImportService;
import com.codefit.service.TrainingSheetImportSummary;
import com.codefit.service.TrainingSheetStageSummary;
import com.codefit.service.WorkbookImportException;
import com.codefit.service.WorkbookPreviewDetails;
import com.codefit.service.WorkbookPreviewReportFormatter;
import com.codefit.ui.NavigationService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /** Application-wide guard from workbook selection through the end of the analyze/review/import
     * cycle. Settings navigation recreates controller instances, so this must be static: a newly loaded
     * Settings screen must not bypass an import already owned by an earlier controller instance. */
    private static final AtomicBoolean importBusy = new AtomicBoolean(false);

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
     * only ever analyzes it — see {@link #analyzeWorkbook} — a real import only happens if the learner
     * explicitly confirms after reviewing the preview. Re-importing the same workbook is always safe —
     * {@link TrainingSheetImportService} never creates duplicate problems/memberships and never
     * downgrades progress the learner has already recorded.
     */
    @FXML
    public void importTrainingSheet() {
        if (importBusy.get()) {
            return; // an application-wide analyze/import cycle is already running
        }
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import Training Sheet");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Excel workbook", "*.xlsx"));
        File file = fileChooser.showOpenDialog(settingsWindow());
        if (file == null) {
            return;
        }
        analyzeWorkbook(file);
    }

    /**
     * Step 1, "Analyze workbook" (#160): {@link TrainingSheetImportService#analyze} parses and
     * evaluates the workbook entirely in memory — no database connection is ever opened for this. The
     * real workbook has ~926 rows across seven sheets, so this runs on {@link BackgroundImportExecutor},
     * not the JavaFX Application Thread, with the "Import Training Sheet…" button disabled and a status
     * label shown while it runs. {@link Task#setOnFailed} covers every failure mode, including an
     * unexpected {@link RuntimeException} the try/catch in an earlier version of this flow could have
     * missed, so the busy state is always cleared. Analysis is read-only and bounded, so unlike the
     * real import below, it never marks {@link BackgroundImportExecutor#markImportActive}.
     */
    private void analyzeWorkbook(File file) {
        setImportBusy(true, "Analyzing \"" + file.getName() + "\"… this can take a few seconds for a large workbook.");
        Task<AnalyzedTrainingWorkbook> task = new Task<>() {
            @Override
            protected AnalyzedTrainingWorkbook call() {
                return trainingSheetImportService.analyze(file.toPath());
            }
        };
        task.setOnSucceeded(event -> onWorkbookAnalyzed(file, task.getValue()));
        task.setOnFailed(event -> {
            setImportBusy(false, null);
            showAnalysisFailedAlert(task.getException());
        });
        task.setOnCancelled(event -> setImportBusy(false, null));
        BackgroundImportExecutor.submit(task);
    }

    private void showAnalysisFailedAlert(Throwable exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Training Sheet Import");
        alert.setHeaderText("This workbook can't be analyzed; no changes were made");
        alert.setContentText(exception instanceof WorkbookImportException workbookImportException
                ? workbookImportException.getMessage()
                : String.valueOf(exception.getMessage()));
        alert.showAndWait();
    }

    /**
     * Step 2, "Preview & Diagnostics, then confirm" (#160): shows the analyzed workbook — the exact
     * object step 3 will import unchanged, never re-read from disk — and, only if the learner
     * explicitly clicks "Import Now", proceeds to {@link #importAnalyzedWorkbook}.
     */
    private void onWorkbookAnalyzed(File file, AnalyzedTrainingWorkbook analyzed) {
        setImportBusy(false, null);
        if (!showPreviewDialog(file.getName(), analyzed)) {
            return; // cancelled, or blocked by errors: analysis never touched the database, nothing to undo
        }
        importAnalyzedWorkbook(file, analyzed);
    }

    /**
     * Step 3, "Transactional import" (#160): applies the exact {@link AnalyzedTrainingWorkbook} the
     * learner just reviewed — the source file is never re-read or re-parsed, so nothing that happened
     * to the file after analysis (edited, replaced, deleted) can change what gets imported. Also runs
     * on {@link BackgroundImportExecutor}'s non-daemon worker, since a real 926-row import is real
     * database work — {@link BackgroundImportExecutor#markImportActive} brackets the whole task so the
     * primary window's close handler can warn the learner instead of silently losing an in-flight write.
     */
    private void importAnalyzedWorkbook(File file, AnalyzedTrainingWorkbook analyzed) {
        setImportBusy(true, "Importing \"" + file.getName() + "\"…");
        BackgroundImportExecutor.markImportActive(true);
        ImportSourceMetadata sourceMetadata = new ImportSourceMetadata(blankToNull(importSourceNameField.getText()),
                blankToNull(importSourceUrlField.getText()), blankToNull(importAuthorField.getText()), blankToNull(importVersionField.getText()));
        Task<TrainingSheetImportSummary> task = new Task<>() {
            @Override
            protected TrainingSheetImportSummary call() {
                return trainingSheetImportService.importAnalyzed(analyzed, sourceMetadata);
            }
        };
        task.setOnSucceeded(event -> {
            BackgroundImportExecutor.markImportActive(false);
            setImportBusy(false, null);
            showImportCompleteDialog(file.getName(), task.getValue());
            refreshImportBatches();
        });
        task.setOnFailed(event -> {
            BackgroundImportExecutor.markImportActive(false);
            setImportBusy(false, null);
            Throwable exception = task.getException();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Training Sheet Import");
            alert.setHeaderText("Import failed; no changes were made");
            alert.setContentText(exception instanceof WorkbookImportException workbookImportException
                    ? workbookImportException.getMessage()
                    : String.valueOf(exception.getMessage()));
            alert.showAndWait();
        });
        task.setOnCancelled(event -> {
            BackgroundImportExecutor.markImportActive(false);
            setImportBusy(false, null);
        });
        BackgroundImportExecutor.submit(task);
    }

    /** The single place busy state changes: always keeps {@link #importBusy}, the button, and the
     *  status label in lockstep, so every completion path (success, expected failure, or an unexpected
     *  exception via {@link Task#setOnFailed}) restores the UI the same way. */
    private void setImportBusy(boolean busy, String statusText) {
        importBusy.set(busy);
        importTrainingSheetButton.setDisable(busy);
        importStatusLabel.setText(statusText == null ? "" : statusText);
        importStatusLabel.setVisible(statusText != null);
        importStatusLabel.setManaged(statusText != null);
    }

    /**
     * The structured "Preview & Diagnostics" screen (#160): summary cards, a stage table (all seven
     * stages, including zero-count ones), a progress summary, a metadata summary, and a diagnostics
     * table (severity/sheet/row/column/reason) — plus the plain-text report underneath "Copy Report"
     * for exporting. "Import Now" is disabled whenever the workbook has a BLOCKING diagnostic (nothing
     * importable at all); warning-only diagnostics never disable it.
     *
     * @return {@code true} if the learner clicked "Import Now", {@code false} for cancel/close/blocked.
     */
    private boolean showPreviewDialog(String workbookFileName, AnalyzedTrainingWorkbook analyzed) {
        boolean blocked = analyzed.hasBlockingDiagnostics();
        WorkbookPreviewDetails details = analyzed.details();
        String reportText = WorkbookPreviewReportFormatter.format(workbookFileName, trainingSheetImportService.previewOf(analyzed));

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Training Sheet Import — Preview");
        dialog.setHeaderText(blocked
                ? "\"" + workbookFileName + "\" has blocking errors and can't be imported — nothing has been written."
                : "Review \"" + workbookFileName + "\" before importing — nothing has been written yet.");

        VBox content = new VBox(14,
                buildSummarySection(details, analyzed.diagnostics()),
                buildStageTableSection(details),
                buildProgressSection(details),
                buildMetadataSection(details),
                buildDiagnosticsSection(analyzed.diagnostics()));
        content.setPadding(new Insets(4));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefSize(720, 540);

        DialogPane pane = dialog.getDialogPane();
        pane.setContent(scrollPane);
        pane.setPrefSize(760, 620);
        pane.getButtonTypes().addAll(COPY_REPORT_BUTTON, ButtonType.CANCEL, IMPORT_NOW_BUTTON);

        Button copyButton = (Button) pane.lookupButton(COPY_REPORT_BUTTON);
        copyButton.addEventFilter(ActionEvent.ACTION, event -> {
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(reportText);
            Clipboard.getSystemClipboard().setContent(clipboardContent);
            event.consume(); // stay open after copying
        });

        Button importButton = (Button) pane.lookupButton(IMPORT_NOW_BUTTON);
        importButton.setDisable(blocked);

        return dialog.showAndWait().filter(button -> button == IMPORT_NOW_BUTTON).isPresent();
    }

    private Node buildSummarySection(WorkbookPreviewDetails details, List<TrainingSheetDiagnostic> diagnostics) {
        long blockingCount = diagnostics.stream().filter(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.BLOCKING).count();
        long warningCount = diagnostics.stream().filter(diagnostic -> diagnostic.severity() == TrainingSheetDiagnosticSeverity.WARNING).count();
        return sectionBox("Summary", labeledGrid(
                "Profile", details.profile().name(),
                "Version", details.profile().version(),
                "Unique problems", String.valueOf(details.uniqueProblemCount()),
                "Roadmap memberships", String.valueOf(details.roadmapMembershipCount()),
                "Blocking errors", String.valueOf(blockingCount),
                "Warnings", String.valueOf(warningCount)));
    }

    /** Detected/valid/skipped rows per stage (#160) - a membership count alone can't distinguish "this
     *  sheet only has 5 rows" from "this sheet has 900 rows and 895 were instructional/duplicate/invalid". */
    private Node buildStageTableSection(WorkbookPreviewDetails details) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(4);
        grid.addRow(0, boldLabel("Stage"), boldLabel("Detected"), boldLabel("Valid"), boldLabel("Skipped"));
        int row = 1;
        for (TrainingSheetStageSummary stageSummary : details.stageSummaries()) {
            grid.addRow(row++, new Label(stageSummary.stage().name()), new Label(String.valueOf(stageSummary.detectedRows())),
                    new Label(String.valueOf(stageSummary.validRows())), new Label(String.valueOf(stageSummary.skippedRows())));
        }
        return sectionBox("Stage breakdown", grid);
    }

    private Label boldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dashboard-card-helper");
        return label;
    }

    private Node buildProgressSection(WorkbookPreviewDetails details) {
        return sectionBox("Progress", labeledGrid(
                "Solved", String.valueOf(details.solvedCount()),
                "In Progress", String.valueOf(details.inProgressCount()),
                "Needs Revisit", String.valueOf(details.revisitCount()),
                "Not Started", String.valueOf(details.notStartedCount())));
    }

    private Node buildMetadataSection(WorkbookPreviewDetails details) {
        int topicCoverage = details.topicCounts().values().stream().mapToInt(Integer::intValue).sum();
        return sectionBox("Metadata", labeledGrid(
                "Hyperlinks found", String.valueOf(details.hyperlinksFound()),
                "Hyperlinks missing", String.valueOf(details.hyperlinksMissing()),
                "Explicit platforms", String.valueOf(details.explicitPlatformCount()),
                "Inferred platforms", String.valueOf(details.inferredPlatformCount()),
                "Unknown platforms", String.valueOf(details.unknownPlatformCount()),
                "Topic coverage", topicCoverage + " row(s)",
                "Suggested-level coverage", details.suggestedLevelMetadataCount() + " row(s)",
                "Quality coverage", details.qualityMetadataCount() + " row(s)",
                "Assistance/independence coverage", details.assistanceMetadataCount() + " row(s)",
                "Attempt snapshots found", details.attemptSnapshotsFound() + " problem(s) in the workbook",
                "Reflection metadata found", details.problemsWithReflectionMetadata() + " problem(s) in the workbook"));
    }

    private Node buildDiagnosticsSection(List<TrainingSheetDiagnostic> diagnostics) {
        TableView<TrainingSheetDiagnostic> table = new TableView<>(FXCollections.observableArrayList(diagnostics));

        TableColumn<TrainingSheetDiagnostic, String> severityColumn = new TableColumn<>("Severity");
        severityColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().severity().name()));
        TableColumn<TrainingSheetDiagnostic, String> sheetColumn = new TableColumn<>("Sheet");
        sheetColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(nullToDash(data.getValue().sheet())));
        TableColumn<TrainingSheetDiagnostic, String> rowColumn = new TableColumn<>("Row");
        rowColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(
                data.getValue().row() == null ? "-" : String.valueOf(data.getValue().row())));
        TableColumn<TrainingSheetDiagnostic, String> columnColumn = new TableColumn<>("Column");
        columnColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(nullToDash(data.getValue().column())));
        TableColumn<TrainingSheetDiagnostic, String> reasonColumn = new TableColumn<>("Reason");
        reasonColumn.setCellValueFactory(data -> new ReadOnlyStringWrapper(data.getValue().reason()));
        reasonColumn.setPrefWidth(320);

        table.getColumns().addAll(List.of(severityColumn, sheetColumn, rowColumn, columnColumn, reasonColumn));
        table.setPrefHeight(220);
        table.setPlaceholder(new Label("No diagnostics — the workbook is clean."));
        return sectionBox("Diagnostics (" + diagnostics.size() + ")", table);
    }

    private Node sectionBox(String title, Node body) {
        Label heading = new Label(title);
        heading.getStyleClass().add("section-title");
        return new VBox(6, heading, body);
    }

    private GridPane labeledGrid(String... labelsAndValues) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(4);
        for (int i = 0; i < labelsAndValues.length; i += 2) {
            Label label = new Label(labelsAndValues[i]);
            label.getStyleClass().add("dashboard-card-helper");
            grid.add(label, 0, i / 2);
            grid.add(new Label(labelsAndValues[i + 1]), 1, i / 2);
        }
        return grid;
    }

    private String nullToDash(String value) {
        return value == null ? "-" : value;
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

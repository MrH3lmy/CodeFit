package com.codefit.controller;

import com.codefit.model.ComplexityClass;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.FinalCategory;
import com.codefit.model.GuidanceSource;
import com.codefit.model.HintLevel;
import com.codefit.model.JavaSolutionDraft;
import com.codefit.model.JavaTestCase;
import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemGuidance;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.ReflectionCardSource;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SolvedWith;
import com.codefit.model.SolvingPhase;
import com.codefit.model.SubmissionResult;
import com.codefit.service.CompileDiagnostic;
import com.codefit.service.CompileOutcome;
import com.codefit.service.CompileOutcomeRegistry;
import com.codefit.service.JavaSolutionWorkspaceService;
import com.codefit.service.ProblemFlashcardService;
import com.codefit.service.ProblemGuidanceService;
import com.codefit.service.ProblemLibraryEntry;
import com.codefit.service.ProblemLibraryService;
import com.codefit.service.ProblemReflection;
import com.codefit.service.ProblemSolvingWorkspaceService;
import com.codefit.service.RunCancellationToken;
import com.codefit.service.RunLimits;
import com.codefit.service.RunResult;
import com.codefit.service.SolvingCheckpointPreferenceService;
import com.codefit.ui.NavigationService;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The structured solving workspace (#145): start/pause/resume/reset a persistent
 * {@link ProblemSolvingSession}, switch between the four solving phases while each keeps its own
 * accumulated time, and finish the session as Submitted/Accepted/Could Not Solve/Abandoned. All
 * timer/session logic lives in {@link ProblemSolvingWorkspaceService}; this controller only renders
 * whatever session state that service returns and forwards user actions to it.
 *
 * <p>Also hosts the post-solve reflection form (#146): every field is optional and editable at any
 * time, entirely independent of the timer/finish controls above it — saving a reflection never
 * changes the problem's workflow state, and finishing a session never touches a reflection already
 * recorded.
 *
 * <p>The timer advances via a one-second {@link Timeline} that persists elapsed time in one-second
 * increments (see {@code ProblemSolvingSessionService#recordElapsedTime}), so at most a couple of
 * seconds can ever be lost to an unclean shutdown, and a paused session simply stops ticking rather
 * than needing timestamp-diffing logic that would have to account for the app being closed entirely.
 */
public class ProblemSolvingWorkspaceController extends BaseController {

    @FXML private Label titleLabel;
    @FXML private Label subtitleLabel;
    @FXML private Label messageLabel;
    @FXML private Label checkpointBanner;
    @FXML private Label progressStateLabel;
    @FXML private Label currentPhaseLabel;
    @FXML private Label totalElapsedLabel;
    @FXML private Label readingTimeLabel;
    @FXML private Label thinkingTimeLabel;
    @FXML private Label codingTimeLabel;
    @FXML private Label debuggingTimeLabel;
    @FXML private Button readingPhaseButton;
    @FXML private Button thinkingPhaseButton;
    @FXML private Button codingPhaseButton;
    @FXML private Button debuggingPhaseButton;
    @FXML private Button startButton;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Button resetButton;
    @FXML private MenuButton verdictMenuButton;
    @FXML private TextArea notesArea;

    @FXML private MenuButton difficultyRatingMenuButton;
    @FXML private MenuButton solvedWithMenuButton;
    @FXML private MenuButton finalCategoryMenuButton;
    @FXML private MenuButton timeComplexityMenuButton;
    @FXML private MenuButton spaceComplexityMenuButton;
    @FXML private TextField actualTopicField;
    @FXML private TextArea approachNotesArea;
    @FXML private TextArea mistakeNotesArea;
    @FXML private TextArea importantObservationArea;
    @FXML private TextArea lessonLearnedArea;
    @FXML private CheckBox editorialUnderstoodCheckBox;
    @FXML private CheckBox otherSolutionsReviewedCheckBox;
    @FXML private CheckBox simplerImplementationCheckBox;
    @FXML private CheckBox betterComplexityCheckBox;

    @FXML private MenuButton flashcardSourceMenuButton;
    @FXML private Label flashcardDraftEmptyLabel;
    @FXML private javafx.scene.layout.VBox flashcardDraftBox;
    @FXML private TextField flashcardFrontField;
    @FXML private TextArea flashcardBackArea;
    @FXML private MenuButton flashcardDeckMenuButton;

    @FXML private Label prerequisitesLabel;
    @FXML private Label referenceLinksLabel;
    @FXML private Label guidanceSourceLabel;
    @FXML private VBox revealedHintsBox;
    @FXML private Label hintsExhaustedLabel;
    @FXML private Button revealHintButton;
    @FXML private VBox guidanceEditorBox;
    @FXML private TextArea clarifyEditArea;
    @FXML private TextArea observationEditArea;
    @FXML private TextArea approachEditArea;
    @FXML private TextArea explanationEditArea;
    @FXML private TextArea pseudocodeEditArea;
    @FXML private TextArea complexityEditArea;
    @FXML private TextArea commonMistakesEditArea;

    @FXML private Label javaRunnerUnavailableLabel;
    @FXML private TextField javaClassNameField;
    @FXML private ChoiceBox<Integer> javaTimeoutChoiceBox;
    @FXML private TextArea javaSourceArea;
    @FXML private TextArea javaStdinArea;
    @FXML private TextArea javaExpectedOutputArea;
    @FXML private Button javaCompileButton;
    @FXML private Button javaRunButton;
    @FXML private Button javaCancelButton;
    @FXML private VBox javaDiagnosticsBox;
    @FXML private Label javaRunStatusLabel;
    @FXML private TextArea javaOutputArea;
    @FXML private VBox javaTestCasesBox;

    @FXML private HBox nextProblemRow;
    @FXML private Label nextRecommendedInfoLabel;
    @FXML private Button nextProblemButton;

    private final ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
    private final SolvingCheckpointPreferenceService checkpointPreferenceService = new SolvingCheckpointPreferenceService();
    private final ProblemFlashcardService problemFlashcardService = new ProblemFlashcardService();
    private final ProblemGuidanceService guidanceService = new ProblemGuidanceService();
    private final JavaSolutionWorkspaceService javaWorkspaceService = new JavaSolutionWorkspaceService();
    private final ProblemLibraryService problemLibraryService = new ProblemLibraryService();

    private CompileOutcome currentCompileOutcome;
    private RunCancellationToken currentCancellationToken;
    private PauseTransition javaAutosaveDebounce;
    private boolean loadingJavaDraft;

    private Long problemId;
    private String problemUrl;
    private ProblemSolvingSession currentSession;
    private SubmissionResult selectedVerdict = SubmissionResult.AC;
    private Integer selectedDifficultyRating;
    private SolvedWith selectedSolvedWith;
    private FinalCategory selectedFinalCategory;
    private ComplexityClass selectedTimeComplexity;
    private ComplexityClass selectedSpaceComplexity;
    private ReflectionCardSource selectedFlashcardSource = ReflectionCardSource.LESSON_LEARNED;
    private Long selectedFlashcardDeckId;
    private Long nextRecommendedProblemId;
    private Timeline timer;

    @FXML
    public void initialize() {
        problemId = NavigationService.consumePendingWorkspaceProblemId();
        configureVerdictMenu();
        configureReflectionMenus();
        configureFlashcardMenus();
        if (problemId == null) {
            setStatus(messageLabel, "No problem selected. Return to Problems and choose one to work on.");
            setControlsDisabled(true);
            renderSession();
            return;
        }
        loadProblemContext();
        loadReflection();
        currentSession = workspaceService.loadWorkspace(problemId).session().orElse(null);
        renderSession();
        loadGuidance();
        loadJavaDraft();
        updateNextRecommendedAvailability();
        startTimerLoop();
    }

    /**
     * Surfaces "move to the next curriculum problem" as a direct one-click action from inside the
     * workspace itself (#161), instead of only being reachable by navigating back to Problems and
     * re-reading the Today card. Recomputed after every {@link #finish}, so it reflects whatever the
     * attempt just changed — the exact same {@link ProblemLibraryService#getNextRecommendedProblem()}
     * the Today card uses, so the two screens can never disagree on what's next.
     */
    private void updateNextRecommendedAvailability() {
        Optional<ProblemLibraryEntry> next = problemLibraryService.getNextRecommendedProblem();
        boolean hasDifferentNext = next.isPresent() && next.get().problem().getId() != problemId;
        nextRecommendedProblemId = hasDifferentNext ? next.get().problem().getId() : null;
        if (nextRecommendedProblemId == null) {
            setVisible(nextProblemRow, false);
            return;
        }
        Problem nextProblem = next.get().problem();
        nextRecommendedInfoLabel.setText("Next: [" + nextProblem.getExternalCode() + "] " + nextProblem.getTitle());
        setVisible(nextProblemRow, true);
    }

    /** Leaving the workspace for Problems — the only navigation-away action this screen exposes — is
     *  exactly when a compiled outcome that's never getting recompiled needs its temp directory
     *  cleaned up (#163); {@code CodeFitApplication#stop()} covers the "quit instead" path. */
    @Override
    public void goProblems() {
        CompileOutcomeRegistry.closeCurrent();
        currentCompileOutcome = null;
        super.goProblems();
    }

    @FXML
    public void goToNextRecommended() {
        if (nextRecommendedProblemId == null) {
            return;
        }
        CompileOutcomeRegistry.closeCurrent();
        currentCompileOutcome = null;
        workspaceService.start(nextRecommendedProblemId);
        NavigationService.showSolvingWorkspace(nextRecommendedProblemId);
    }

    // ---- Java Runner (#163) --------------------------------------------------------------------

    private void loadJavaDraft() {
        if (!javaWorkspaceService.isRunnerAvailable()) {
            setStatus(javaRunnerUnavailableLabel, javaWorkspaceService.getRunnerUnavailabilityReason());
            javaCompileButton.setDisable(true);
            javaRunButton.setDisable(true);
        }
        loadingJavaDraft = true;
        JavaSolutionDraft draft = javaWorkspaceService.loadDraft(problemId);
        javaClassNameField.setText(draft.getMainClassName());
        javaSourceArea.setText(draft.getSourceCode() == null ? "" : draft.getSourceCode());
        javaStdinArea.setText(draft.getStdin() == null ? "" : draft.getStdin());
        javaExpectedOutputArea.setText(draft.getExpectedOutput() == null ? "" : draft.getExpectedOutput());
        loadingJavaDraft = false;

        javaAutosaveDebounce = new PauseTransition(Duration.millis(800));
        javaAutosaveDebounce.setOnFinished(event -> autosaveJavaDraft());
        javaClassNameField.textProperty().addListener((observable, oldValue, newValue) -> scheduleJavaAutosave());
        javaSourceArea.textProperty().addListener((observable, oldValue, newValue) -> scheduleJavaAutosave());
        javaStdinArea.textProperty().addListener((observable, oldValue, newValue) -> scheduleJavaAutosave());
        javaExpectedOutputArea.textProperty().addListener((observable, oldValue, newValue) -> scheduleJavaAutosave());

        javaDiagnosticsBox.getChildren().clear();
        setStatus(javaRunStatusLabel, "");
        javaOutputArea.clear();
        javaRunButton.setDisable(true);

        configureTimeoutChoiceBox();
        renderTestCases();
    }

    private static final List<Integer> TIMEOUT_OPTIONS_SECONDS = List.of(5, 10, 15, 30, 60);

    /** "Infinite loops are terminated by a configurable timeout" (#163): the timeout mechanism itself
     *  always existed, but nothing in the UI could ever change it from the fixed default — this is
     *  the missing UI half, backed by {@link JavaSolutionWorkspaceService#getRunTimeoutSeconds()}. */
    private void configureTimeoutChoiceBox() {
        javaTimeoutChoiceBox.setItems(FXCollections.observableArrayList(TIMEOUT_OPTIONS_SECONDS));
        javaTimeoutChoiceBox.getSelectionModel().select(Integer.valueOf(javaWorkspaceService.getRunTimeoutSeconds()));
        javaTimeoutChoiceBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer seconds) {
                return seconds == null ? "" : seconds + "s";
            }

            @Override
            public Integer fromString(String string) {
                return null;
            }
        });
        javaTimeoutChoiceBox.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(oldValue)) {
                javaWorkspaceService.setRunTimeoutSeconds(newValue);
            }
        });
    }

    private void scheduleJavaAutosave() {
        if (loadingJavaDraft || problemId == null) {
            return;
        }
        javaAutosaveDebounce.stop();
        javaAutosaveDebounce.playFromStart();
    }

    private void autosaveJavaDraft() {
        if (problemId == null) {
            return;
        }
        javaWorkspaceService.saveDraft(problemId, javaClassNameField.getText(), javaSourceArea.getText(),
                javaStdinArea.getText(), javaExpectedOutputArea.getText());
    }

    @FXML
    public void compileJavaSolution() {
        if (problemId == null || !javaWorkspaceService.isRunnerAvailable()) {
            return;
        }
        autosaveJavaDraft();
        String source = javaSourceArea.getText();
        String className = blankToNull(javaClassNameField.getText()) == null ? "Solution" : javaClassNameField.getText().strip();
        javaCompileButton.setDisable(true);
        javaRunButton.setDisable(true);
        setStatus(javaRunStatusLabel, "Compiling…");
        javaDiagnosticsBox.getChildren().clear();

        Thread thread = new Thread(() -> {
            CompileOutcome outcome = javaWorkspaceService.compile(source, className);
            Platform.runLater(() -> onCompileFinished(outcome));
        }, "java-runner-compile");
        thread.setDaemon(true);
        thread.start();
    }

    private void onCompileFinished(CompileOutcome outcome) {
        // Registering here (rather than closing currentCompileOutcome directly) means the outcome's
        // temp directory still gets cleaned up even if the learner never compiles again before
        // leaving this problem — see CompileOutcomeRegistry and this controller's goProblems()/
        // goToNextRecommended() overrides, and CodeFitApplication#stop() for the app-exit path.
        CompileOutcomeRegistry.replace(outcome);
        currentCompileOutcome = outcome;
        javaCompileButton.setDisable(false);
        javaDiagnosticsBox.getChildren().clear();

        if (outcome.success()) {
            setStatus(javaRunStatusLabel, "Compiled successfully.");
            javaRunButton.setDisable(!javaWorkspaceService.isRunnerAvailable());
            return;
        }
        setStatus(javaRunStatusLabel, "Compilation failed — see diagnostics below.");
        javaRunButton.setDisable(true);
        for (CompileDiagnostic diagnostic : outcome.diagnostics()) {
            javaDiagnosticsBox.getChildren().add(diagnosticRow(diagnostic));
        }
    }

    private javafx.scene.Node diagnosticRow(CompileDiagnostic diagnostic) {
        String locationText = diagnostic.file() + ":" + diagnostic.line()
                + (diagnostic.column() == null ? "" : ":" + diagnostic.column());
        Button jumpButton = new Button((diagnostic.error() ? "Error " : "Warning ") + locationText + " — " + diagnostic.message());
        jumpButton.getStyleClass().add("ghost-button");
        jumpButton.setWrapText(true);
        jumpButton.setMaxWidth(Double.MAX_VALUE);
        jumpButton.setOnAction(event -> jumpSourceCaretToLine(diagnostic.line(), diagnostic.column()));
        return jumpButton;
    }

    /** "Compiler diagnostics link to the corresponding editor line" (#163): moves the source editor's
     *  caret to the diagnostic's reported line/column and selects that whole line, so it's visually
     *  highlighted (scrolled into view by virtue of the caret/selection landing there) rather than
     *  just silently repositioning an invisible caret. */
    private void jumpSourceCaretToLine(int line, Integer column) {
        String text = javaSourceArea.getText();
        String[] lines = text.split("\\R", -1);
        int lineStart = 0;
        for (int i = 0; i < line - 1 && i < lines.length; i++) {
            lineStart += lines[i].length() + 1;
        }
        int lineIndex = Math.min(Math.max(line - 1, 0), lines.length - 1);
        int lineLength = lineIndex >= 0 && lineIndex < lines.length ? lines[lineIndex].length() : 0;
        int lineEnd = Math.min(lineStart + lineLength, text.length());

        // selectRange's second argument is the resulting caret position, so this both scrolls the
        // caret to the diagnostic's line and leaves the whole line selected/highlighted — a plain
        // positionCaret() call afterward would collapse the selection right back to an invisible caret.
        javaSourceArea.requestFocus();
        javaSourceArea.selectRange(Math.min(lineStart, text.length()), lineEnd);
    }

    @FXML
    public void runJavaSolution() {
        if (currentCompileOutcome == null || !currentCompileOutcome.success()) {
            return;
        }
        String stdin = javaStdinArea.getText();
        String expectedOutput = blankToNull(javaExpectedOutputArea.getText());
        currentCancellationToken = new RunCancellationToken();
        javaRunButton.setDisable(true);
        javaCompileButton.setDisable(true);
        setVisible(javaCancelButton, true);
        setStatus(javaRunStatusLabel, "Running…");
        javaOutputArea.clear();

        CompileOutcome compiledForThisRun = currentCompileOutcome;
        RunCancellationToken tokenForThisRun = currentCancellationToken;
        RunLimits limits = javaWorkspaceService.currentRunLimits();
        Thread thread = new Thread(() -> {
            RunResult result = javaWorkspaceService.run(compiledForThisRun, stdin, limits, tokenForThisRun);
            Platform.runLater(() -> onRunFinished(result, expectedOutput));
        }, "java-runner-run");
        thread.setDaemon(true);
        thread.start();
    }

    private void onRunFinished(RunResult result, String expectedOutput) {
        javaRunButton.setDisable(currentCompileOutcome == null || !currentCompileOutcome.success());
        javaCompileButton.setDisable(false);
        setVisible(javaCancelButton, false);

        StringBuilder output = new StringBuilder();
        output.append("stdout:\n").append(result.stdout()).append('\n');
        if (result.stderr() != null && !result.stderr().isBlank()) {
            output.append("\nstderr:\n").append(result.stderr()).append('\n');
        }
        javaOutputArea.setText(output.toString());

        StringBuilder status = new StringBuilder();
        if (result.cancelled()) {
            status.append("Cancelled.");
        } else if (result.timedOut()) {
            status.append("Timed out (possible infinite loop).");
        } else {
            status.append("Exit status ").append(result.exitCode());
            if (expectedOutput != null) {
                status.append(result.matchesExpectedOutput(expectedOutput) ? " — matches expected output." : " — does not match expected output.");
            }
        }
        status.append(" (").append(result.elapsedMillis()).append(" ms)");
        if (result.outputTruncated()) {
            status.append(" Output was truncated at the size limit.");
        }
        setStatus(javaRunStatusLabel, status.toString());
    }

    @FXML
    public void cancelJavaRun() {
        if (currentCancellationToken != null) {
            currentCancellationToken.cancel();
        }
    }

    @FXML
    public void copyJavaSolution() {
        ClipboardContent content = new ClipboardContent();
        content.putString(javaSourceArea.getText());
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(javaRunStatusLabel, "Solution copied — paste it into the external judge to submit.");
    }

    // ---- Multiple local test cases (#163) --------------------------------------------------------

    private void renderTestCases() {
        javaTestCasesBox.getChildren().clear();
        for (JavaTestCase testCase : javaWorkspaceService.listTestCases(problemId)) {
            javaTestCasesBox.getChildren().add(testCaseRow(testCase));
        }
    }

    private javafx.scene.Node testCaseRow(JavaTestCase testCase) {
        TextArea stdinArea = new TextArea(testCase.getStdin() == null ? "" : testCase.getStdin());
        stdinArea.setPromptText("Standard input");
        stdinArea.setPrefRowCount(2);
        stdinArea.setWrapText(true);
        TextArea expectedArea = new TextArea(testCase.getExpectedOutput() == null ? "" : testCase.getExpectedOutput());
        expectedArea.setPromptText("Expected output (optional)");
        expectedArea.setPrefRowCount(2);
        expectedArea.setWrapText(true);

        Label resultLabel = new Label("Not run yet.");
        resultLabel.setWrapText(true);

        Runnable persistEdits = () -> javaWorkspaceService.updateTestCase(testCase.getId(), testCase.getProblemId(),
                testCase.getPosition(), stdinArea.getText(), expectedArea.getText());
        stdinArea.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused) persistEdits.run();
        });
        expectedArea.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (!isFocused) persistEdits.run();
        });

        Button runButton = new Button("Run");
        runButton.getStyleClass().add("action-button");
        runButton.setOnAction(event -> {
            persistEdits.run();
            runSingleTestCase(stdinArea.getText(), expectedArea.getText(), resultLabel);
        });

        Button removeButton = new Button("Remove");
        removeButton.getStyleClass().add("ghost-button");
        removeButton.setOnAction(event -> {
            javaWorkspaceService.removeTestCase(testCase.getId());
            renderTestCases();
        });

        VBox stdinColumn = new VBox(2, new Label("Input"), stdinArea);
        stdinColumn.setMaxWidth(Double.MAX_VALUE);
        VBox expectedColumn = new VBox(2, new Label("Expected"), expectedArea);
        expectedColumn.setMaxWidth(Double.MAX_VALUE);
        HBox inputsRow = new HBox(8, stdinColumn, expectedColumn);
        HBox.setHgrow(stdinColumn, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(expectedColumn, javafx.scene.layout.Priority.ALWAYS);

        HBox actionsRow = new HBox(8, runButton, removeButton);
        actionsRow.setAlignment(Pos.CENTER_LEFT);

        VBox row = new VBox(6, inputsRow, actionsRow, resultLabel);
        row.getStyleClass().add("panel");
        return row;
    }

    @FXML
    public void addJavaTestCase() {
        if (problemId == null) {
            return;
        }
        javaWorkspaceService.addTestCase(problemId);
        renderTestCases();
    }

    /** Runs one test case against whatever's currently compiled — a separate background run from the
     *  quick-run Compile/Run pair above, so a slow test case never disables those controls. */
    private void runSingleTestCase(String stdin, String expectedOutput, Label resultLabel) {
        if (currentCompileOutcome == null || !currentCompileOutcome.success()) {
            resultLabel.setText("Compile the solution first.");
            return;
        }
        resultLabel.setText("Running…");
        CompileOutcome compiledForThisRun = currentCompileOutcome;
        RunLimits limits = javaWorkspaceService.currentRunLimits();
        Thread thread = new Thread(() -> {
            RunResult result = javaWorkspaceService.run(compiledForThisRun, stdin, limits, new RunCancellationToken());
            Platform.runLater(() -> resultLabel.setText(describeTestCaseResult(result, expectedOutput)));
        }, "java-runner-test-case");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    public void runAllJavaTestCases() {
        if (currentCompileOutcome == null || !currentCompileOutcome.success()) {
            setStatus(javaRunStatusLabel, "Compile the solution before running test cases.");
            return;
        }
        List<JavaTestCase> testCases = javaWorkspaceService.listTestCases(problemId);
        if (testCases.isEmpty()) {
            return;
        }
        List<javafx.scene.Node> rows = javaTestCasesBox.getChildren().stream().toList();
        CompileOutcome compiledForThisRun = currentCompileOutcome;
        RunLimits limits = javaWorkspaceService.currentRunLimits();
        setStatus(javaRunStatusLabel, "Running " + testCases.size() + " test case(s)…");

        Thread thread = new Thread(() -> {
            for (int i = 0; i < testCases.size() && i < rows.size(); i++) {
                JavaTestCase testCase = testCases.get(i);
                Label resultLabel = testCaseResultLabel(rows.get(i));
                if (resultLabel != null) {
                    Platform.runLater(() -> resultLabel.setText("Running…"));
                }
                RunResult result = javaWorkspaceService.runTestCase(compiledForThisRun, testCase, limits, new RunCancellationToken());
                String description = describeTestCaseResult(result, testCase.getExpectedOutput());
                if (resultLabel != null) {
                    Platform.runLater(() -> resultLabel.setText(description));
                }
            }
            Platform.runLater(() -> setStatus(javaRunStatusLabel, "Finished running " + testCases.size() + " test case(s)."));
        }, "java-runner-test-case-all");
        thread.setDaemon(true);
        thread.start();
    }

    /** The last child of a {@link #testCaseRow} {@code VBox} is always its result {@link Label}. */
    private Label testCaseResultLabel(javafx.scene.Node row) {
        if (!(row instanceof VBox box) || box.getChildren().isEmpty()) {
            return null;
        }
        javafx.scene.Node last = box.getChildren().get(box.getChildren().size() - 1);
        return last instanceof Label label ? label : null;
    }

    private String describeTestCaseResult(RunResult result, String expectedOutput) {
        if (result.cancelled()) {
            return "Cancelled. (" + result.elapsedMillis() + " ms)";
        }
        if (result.timedOut()) {
            return "Timed out (possible infinite loop). (" + result.elapsedMillis() + " ms)";
        }
        StringBuilder description = new StringBuilder("Exit status ").append(result.exitCode());
        Boolean matches = expectedOutput == null || expectedOutput.isBlank() ? null
                : result.matchesExpectedOutput(expectedOutput);
        if (matches != null) {
            description.append(matches ? " — PASS" : " — FAIL");
        }
        description.append(" (").append(result.elapsedMillis()).append(" ms)");
        if (result.outputTruncated()) {
            description.append(" Output truncated.");
        }
        description.append("\n").append(result.stdout() == null ? "" : result.stdout());
        return description.toString();
    }

    /** Prerequisites/reference links (static, from the authored guidance) plus every hint level
     *  already opened this attempt (from the current session), rendered in ladder order (#162). */
    private void loadGuidance() {
        List<String> prerequisites = guidanceService.getPrerequisites(problemId);
        setStatus(prerequisitesLabel, prerequisites.isEmpty() ? "" : "Prerequisites: " + String.join(", ", prerequisites));

        List<String> referenceLinks = guidanceService.getReferenceLinks(problemId);
        setStatus(referenceLinksLabel, referenceLinks.isEmpty() ? "" : "References: " + String.join(", ", referenceLinks));

        // #162: the architecture stores where guidance came from (learner/CodeFit/imported/provider);
        // showing it here is what actually makes that provenance visible to the learner reading it,
        // rather than only ever being readable from the database.
        setStatus(guidanceSourceLabel, guidanceService.getGuidance(problemId)
                .map(guidance -> "Source: " + capitalize(guidance.getSource().name()))
                .orElse(""));

        revealedHintsBox.getChildren().clear();
        Optional<HintLevel> openedLevel = guidanceService.getOpenedLevel(problemId);
        openedLevel.ifPresent(level -> {
            for (HintLevel each : HintLevel.values()) {
                revealedHintsBox.getChildren().add(hintLevelRow(guidanceService.revealLevel(problemId, each)));
                if (each == level) {
                    break;
                }
            }
        });
        updateHintButtonState(openedLevel.orElse(null));
    }

    private javafx.scene.Node hintLevelRow(ProblemGuidanceService.HintReveal reveal) {
        Label levelLabel = new Label(capitalize(reveal.level().name()));
        levelLabel.getStyleClass().add("problem-row-subtitle");
        Label textLabel = new Label(reveal.hasContent() ? reveal.text() : "No guidance authored yet for this level.");
        textLabel.setWrapText(true);
        return new VBox(2, levelLabel, textLabel);
    }

    private void updateHintButtonState(HintLevel openedLevel) {
        boolean atMax = openedLevel == HintLevel.EXPLANATION;
        setVisible(hintsExhaustedLabel, atMax);
        revealHintButton.setDisable(atMax);
    }

    @FXML
    public void revealNextHint() {
        if (problemId == null) {
            return;
        }
        guidanceService.openNextHintLevel(problemId);
        loadGuidance();
    }

    @FXML
    public void toggleGuidanceEditor() {
        boolean showing = !guidanceEditorBox.isVisible();
        if (showing) {
            ProblemGuidance guidance = guidanceService.getGuidance(problemId).orElse(null);
            clarifyEditArea.setText(guidance == null || guidance.getClarifyText() == null ? "" : guidance.getClarifyText());
            observationEditArea.setText(guidance == null || guidance.getObservationText() == null ? "" : guidance.getObservationText());
            approachEditArea.setText(guidance == null || guidance.getApproachText() == null ? "" : guidance.getApproachText());
            explanationEditArea.setText(guidance == null || guidance.getExplanationText() == null ? "" : guidance.getExplanationText());
            pseudocodeEditArea.setText(guidance == null || guidance.getPseudocodeText() == null ? "" : guidance.getPseudocodeText());
            complexityEditArea.setText(guidance == null || guidance.getComplexityNotes() == null ? "" : guidance.getComplexityNotes());
            commonMistakesEditArea.setText(guidance == null || guidance.getCommonMistakesText() == null ? "" : guidance.getCommonMistakesText());
        }
        setVisible(guidanceEditorBox, showing);
    }

    @FXML
    public void saveGuidanceEdits() {
        if (problemId == null) {
            return;
        }
        guidanceService.saveGuidance(problemId, GuidanceSource.LEARNER, blankToNull(clarifyEditArea.getText()),
                blankToNull(observationEditArea.getText()), blankToNull(approachEditArea.getText()),
                blankToNull(explanationEditArea.getText()), blankToNull(pseudocodeEditArea.getText()),
                blankToNull(complexityEditArea.getText()), blankToNull(commonMistakesEditArea.getText()), null, null);
        setStatus(messageLabel, "Guidance saved.");
        setVisible(guidanceEditorBox, false);
        loadGuidance();
    }

    private void loadProblemContext() {
        ProblemSolvingWorkspaceService.WorkspaceView view = workspaceService.loadWorkspace(problemId);
        Problem problem = view.problem();
        RoadmapEntry entry = view.roadmapEntry();
        problemUrl = problem.getUrl();
        titleLabel.setText("[" + problem.getExternalCode() + "] " + problem.getTitle());
        StringBuilder subtitle = new StringBuilder(problem.getPlatform());
        if (entry != null) {
            subtitle.append(" • Stage ").append(entry.getStage());
            if (entry.getSetNumber() != null) {
                subtitle.append(" Set ").append(entry.getSetNumber());
            }
        }
        subtitleLabel.setText(subtitle.toString());
        progressStateLabel.setText("Status: " + capitalize(view.progress().getState().name()));
    }

    private void loadReflection() {
        ProblemProgress progress = workspaceService.loadWorkspace(problemId).progress();
        selectedDifficultyRating = progress.getPerceivedDifficultyRating();
        difficultyRatingMenuButton.setText(selectedDifficultyRating == null ? "Not rated" : selectedDifficultyRating + "/10");
        selectedSolvedWith = progress.getSolvedWith();
        solvedWithMenuButton.setText(selectedSolvedWith == null ? "Not set" : capitalize(selectedSolvedWith.name()));
        selectedFinalCategory = progress.getFinalCategory();
        finalCategoryMenuButton.setText(selectedFinalCategory == null ? "Not set" : capitalize(selectedFinalCategory.name()));
        selectedTimeComplexity = progress.getTimeComplexity();
        timeComplexityMenuButton.setText(selectedTimeComplexity == null ? "Not set" : displayComplexity(selectedTimeComplexity));
        selectedSpaceComplexity = progress.getSpaceComplexity();
        spaceComplexityMenuButton.setText(selectedSpaceComplexity == null ? "Not set" : displayComplexity(selectedSpaceComplexity));
        actualTopicField.setText(progress.getActualTopic() == null ? "" : progress.getActualTopic());
        approachNotesArea.setText(progress.getApproachNotes() == null ? "" : progress.getApproachNotes());
        mistakeNotesArea.setText(progress.getMistakeNotes() == null ? "" : progress.getMistakeNotes());
        importantObservationArea.setText(progress.getImportantObservation() == null ? "" : progress.getImportantObservation());
        lessonLearnedArea.setText(progress.getLessonLearned() == null ? "" : progress.getLessonLearned());
        editorialUnderstoodCheckBox.setSelected(progress.isEditorialUnderstood());
        otherSolutionsReviewedCheckBox.setSelected(progress.isOtherSolutionsReviewed());
        simplerImplementationCheckBox.setSelected(progress.isSimplerImplementationConsidered());
        betterComplexityCheckBox.setSelected(progress.isBetterComplexityConsidered());
    }

    private void configureReflectionMenus() {
        for (int rating = 1; rating <= 10; rating++) {
            int value = rating;
            MenuItem item = new MenuItem(value + "/10");
            item.setOnAction(event -> {
                selectedDifficultyRating = value;
                difficultyRatingMenuButton.setText(value + "/10");
            });
            difficultyRatingMenuButton.getItems().add(item);
        }
        for (SolvedWith solvedWith : SolvedWith.values()) {
            MenuItem item = new MenuItem(capitalize(solvedWith.name()));
            item.setOnAction(event -> {
                selectedSolvedWith = solvedWith;
                solvedWithMenuButton.setText(capitalize(solvedWith.name()));
            });
            solvedWithMenuButton.getItems().add(item);
        }
        for (FinalCategory finalCategory : FinalCategory.values()) {
            MenuItem item = new MenuItem(capitalize(finalCategory.name()));
            item.setOnAction(event -> {
                selectedFinalCategory = finalCategory;
                finalCategoryMenuButton.setText(capitalize(finalCategory.name()));
            });
            finalCategoryMenuButton.getItems().add(item);
        }
        for (ComplexityClass complexityClass : ComplexityClass.values()) {
            MenuItem timeItem = new MenuItem(displayComplexity(complexityClass));
            timeItem.setOnAction(event -> {
                selectedTimeComplexity = complexityClass;
                timeComplexityMenuButton.setText(displayComplexity(complexityClass));
            });
            timeComplexityMenuButton.getItems().add(timeItem);

            MenuItem spaceItem = new MenuItem(displayComplexity(complexityClass));
            spaceItem.setOnAction(event -> {
                selectedSpaceComplexity = complexityClass;
                spaceComplexityMenuButton.setText(displayComplexity(complexityClass));
            });
            spaceComplexityMenuButton.getItems().add(spaceItem);
        }
    }

    private String displayComplexity(ComplexityClass complexityClass) {
        return switch (complexityClass) {
            case O_1 -> "O(1)";
            case O_LOG_N -> "O(log n)";
            case O_N -> "O(n)";
            case O_N_LOG_N -> "O(n log n)";
            case O_N_SQUARED -> "O(n²)";
            case O_N_CUBED -> "O(n³)";
            case O_EXPONENTIAL -> "O(2ⁿ)";
            case O_FACTORIAL -> "O(n!)";
            case OTHER -> "Other";
        };
    }

    private void configureFlashcardMenus() {
        flashcardSourceMenuButton.getItems().setAll(java.util.Arrays.stream(ReflectionCardSource.values())
                .map(source -> {
                    MenuItem item = new MenuItem(capitalize(source.name()));
                    item.setOnAction(event -> {
                        selectedFlashcardSource = source;
                        flashcardSourceMenuButton.setText(capitalize(source.name()));
                        setVisible(flashcardDraftBox, false);
                        setVisible(flashcardDraftEmptyLabel, true);
                    });
                    return item;
                }).toList());
        refreshDeckMenu();
    }

    private void refreshDeckMenu() {
        flashcardDeckMenuButton.getItems().clear();
        MenuItem lessonsDeckItem = new MenuItem(ProblemFlashcardService.LESSONS_DECK_NAME);
        lessonsDeckItem.setOnAction(event -> {
            selectedFlashcardDeckId = null;
            flashcardDeckMenuButton.setText(ProblemFlashcardService.LESSONS_DECK_NAME);
        });
        flashcardDeckMenuButton.getItems().add(lessonsDeckItem);
        for (Deck deck : problemFlashcardService.getAvailableDecks()) {
            if (deck.getName().equalsIgnoreCase(ProblemFlashcardService.LESSONS_DECK_NAME)) {
                continue;
            }
            MenuItem item = new MenuItem(deck.getName());
            item.setOnAction(event -> {
                selectedFlashcardDeckId = deck.getId();
                flashcardDeckMenuButton.setText(deck.getName());
            });
            flashcardDeckMenuButton.getItems().add(item);
        }
        selectedFlashcardDeckId = null;
        flashcardDeckMenuButton.setText(ProblemFlashcardService.LESSONS_DECK_NAME);
    }

    @FXML
    public void generateFlashcardDraft() {
        if (problemId == null) {
            return;
        }
        ProblemFlashcardService.ProblemFlashcardDraft draft = problemFlashcardService.buildDraft(problemId, selectedFlashcardSource);
        flashcardFrontField.setText(draft.front());
        flashcardBackArea.setText(draft.back());
        setVisible(flashcardDraftEmptyLabel, false);
        setVisible(flashcardDraftBox, true);
    }

    @FXML
    public void saveFlashcard() {
        if (problemId == null) {
            return;
        }
        String front = flashcardFrontField.getText();
        String back = flashcardBackArea.getText();
        if (front == null || front.isBlank() || back == null || back.isBlank()) {
            setStatus(messageLabel, "Both the card prompt and answer are required.");
            return;
        }

        long deckId = selectedFlashcardDeckId != null ? selectedFlashcardDeckId : problemFlashcardService.resolveLessonsDeckId();
        boolean allowDuplicate = false;
        Optional<Flashcard> existing = problemFlashcardService.findExistingLinkedCard(problemId, selectedFlashcardSource);
        if (existing.isPresent()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Flashcard already exists");
            alert.setHeaderText("A flashcard was already created from this reflection field.");
            alert.setContentText("Existing prompt: " + existing.get().getFront() + "\n\nCreate another one anyway?");
            allowDuplicate = alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
            if (!allowDuplicate) {
                setStatus(messageLabel, "Kept the existing linked flashcard — nothing new was created.");
                return;
            }
        }

        problemFlashcardService.createCard(deckId, problemId, selectedFlashcardSource, front, back, allowDuplicate);
        setStatus(messageLabel, "Flashcard saved to " + flashcardDeckMenuButton.getText() + ".");
        setVisible(flashcardDraftBox, false);
        setVisible(flashcardDraftEmptyLabel, true);
        refreshDeckMenu();
    }

    @FXML
    public void saveReflection() {
        if (problemId == null) {
            return;
        }
        ProblemReflection reflection = new ProblemReflection(selectedDifficultyRating, selectedSolvedWith, selectedFinalCategory,
                blankToNull(approachNotesArea.getText()), blankToNull(mistakeNotesArea.getText()),
                blankToNull(importantObservationArea.getText()), selectedTimeComplexity, selectedSpaceComplexity,
                blankToNull(lessonLearnedArea.getText()), blankToNull(actualTopicField.getText()),
                editorialUnderstoodCheckBox.isSelected(), otherSolutionsReviewedCheckBox.isSelected(),
                simplerImplementationCheckBox.isSelected(), betterComplexityCheckBox.isSelected());
        workspaceService.updateReflection(problemId, reflection);
        setStatus(messageLabel, "Reflection saved.");
    }

    @FXML
    public void markPreviouslySolved() {
        if (problemId == null) {
            return;
        }
        ProblemAttempt attempt = workspaceService.markPreviouslySolved(problemId, notesArea.getText());
        currentSession = null;
        setStatus(messageLabel, "Recorded attempt #" + attempt.attemptNumber() + " as previously solved (ACX).");
        notesArea.clear();
        renderSession();
        loadProblemContext();
        loadReflection();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private void configureVerdictMenu() {
        for (SubmissionResult result : SubmissionResult.values()) {
            MenuItem item = new MenuItem(result.name());
            item.setOnAction(event -> {
                selectedVerdict = result;
                verdictMenuButton.setText("Verdict: " + result.name());
            });
            verdictMenuButton.getItems().add(item);
        }
    }

    private void startTimerLoop() {
        if (timer != null) {
            return;
        }
        timer = new Timeline(new KeyFrame(Duration.seconds(1), event -> onTick()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    private void onTick() {
        if (problemId == null || currentSession == null || !currentSession.isActive() || currentSession.isPaused()) {
            return;
        }
        int previousTotal = currentSession.getTotalSecondsElapsed();
        currentSession = workspaceService.tick(problemId, currentSession.getPhase(), 1);
        renderSession();
        checkpointPreferenceService.findNewlyCrossedCheckpoint(previousTotal, currentSession.getTotalSecondsElapsed())
                .ifPresent(this::showCheckpointReminder);
    }

    private void showCheckpointReminder(int minutes) {
        setStatus(checkpointBanner, "Checkpoint: " + minutes + " minutes on this problem so far. "
                + "This is just a reminder — keep going if you're making progress.");
    }

    @FXML
    public void startSession() {
        currentSession = workspaceService.start(problemId);
        renderSession();
    }

    @FXML
    public void pauseSession() {
        currentSession = workspaceService.pause(problemId);
        renderSession();
    }

    @FXML
    public void resumeSession() {
        currentSession = workspaceService.resume(problemId);
        renderSession();
    }

    @FXML
    public void resetSession() {
        workspaceService.reset(problemId);
        currentSession = null;
        setStatus(checkpointBanner, "");
        renderSession();
    }

    @FXML
    public void switchToReading() {
        switchPhase(SolvingPhase.READING);
    }

    @FXML
    public void switchToThinking() {
        switchPhase(SolvingPhase.THINKING);
    }

    @FXML
    public void switchToCoding() {
        switchPhase(SolvingPhase.CODING);
    }

    @FXML
    public void switchToDebugging() {
        switchPhase(SolvingPhase.DEBUGGING);
    }

    private void switchPhase(SolvingPhase phase) {
        if (problemId == null) {
            return;
        }
        currentSession = workspaceService.switchPhase(problemId, phase);
        renderSession();
    }

    @FXML
    public void finishAsSubmitted() {
        finish(SessionFinishOutcome.SUBMITTED);
    }

    @FXML
    public void finishAsAccepted() {
        finish(SessionFinishOutcome.ACCEPTED);
    }

    @FXML
    public void finishAsCouldNotSolve() {
        finish(SessionFinishOutcome.COULD_NOT_SOLVE);
    }

    @FXML
    public void finishAsAbandoned() {
        finish(SessionFinishOutcome.ABANDONED);
    }

    private void finish(SessionFinishOutcome outcome) {
        if (problemId == null) {
            return;
        }
        Optional<ProblemAttempt> attempt = workspaceService.finish(problemId, outcome, selectedVerdict, notesArea.getText());
        currentSession = null;
        String message = attempt
                .map(recorded -> "Recorded attempt #" + recorded.attemptNumber() + " (" + recorded.submissionResult() + ").")
                .orElse("Session ended without recording an attempt.");
        setStatus(messageLabel, message);
        notesArea.clear();
        renderSession();
        loadProblemContext();
        loadGuidance();
        updateNextRecommendedAvailability();
        if (outcome == SessionFinishOutcome.ACCEPTED) {
            showExplanationAfterAccepted();
        }
    }

    /** "Show the concept explanation after AC" (#162): the full explanation is shown directly here
     *  regardless of how far up the ladder the learner got this attempt — once solved, there's no
     *  remaining pedagogical reason to keep it behind further clicks. */
    private void showExplanationAfterAccepted() {
        ProblemGuidanceService.HintReveal explanation = guidanceService.revealLevel(problemId, HintLevel.EXPLANATION);
        if (!explanation.hasContent()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Explanation");
        alert.setHeaderText("Now that you've solved it — here's the full explanation.");
        alert.setContentText(explanation.text());
        alert.showAndWait();
    }

    @FXML
    public void openProblem() {
        if (problemUrl == null || problemUrl.isBlank()) {
            setStatus(messageLabel, "This problem has no link yet.");
            return;
        }
        try {
            URI uri = new URI(problemUrl);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                setStatus(messageLabel, "Refusing to open a non-http(s) link.");
                return;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
            } else {
                setStatus(messageLabel, "Unable to open a browser on this system.");
            }
        } catch (Exception exception) {
            setStatus(messageLabel, "Unable to open link: " + exception.getMessage());
        }
    }

    private void renderSession() {
        boolean hasSession = currentSession != null;
        boolean running = hasSession && currentSession.isActive() && !currentSession.isPaused();
        boolean paused = hasSession && currentSession.isPaused();

        setVisible(startButton, !hasSession);
        setVisible(pauseButton, running);
        setVisible(resumeButton, paused || (hasSession && !currentSession.isActive()));
        setVisible(resetButton, hasSession);

        currentPhaseLabel.setText(hasSession ? capitalize(currentSession.getPhase().name()) : "Not started");
        totalElapsedLabel.setText(formatDuration(hasSession ? currentSession.getTotalSecondsElapsed() : 0));
        readingTimeLabel.setText(formatDuration(hasSession ? currentSession.getReadingSecondsElapsed() : 0));
        thinkingTimeLabel.setText(formatDuration(hasSession ? currentSession.getThinkingSecondsElapsed() : 0));
        codingTimeLabel.setText(formatDuration(hasSession ? currentSession.getCodingSecondsElapsed() : 0));
        debuggingTimeLabel.setText(formatDuration(hasSession ? currentSession.getDebuggingSecondsElapsed() : 0));

        highlightActivePhase(hasSession ? currentSession.getPhase() : null);
    }

    private void highlightActivePhase(SolvingPhase phase) {
        setPhaseButtonActive(readingPhaseButton, phase == SolvingPhase.READING);
        setPhaseButtonActive(thinkingPhaseButton, phase == SolvingPhase.THINKING);
        setPhaseButtonActive(codingPhaseButton, phase == SolvingPhase.CODING);
        setPhaseButtonActive(debuggingPhaseButton, phase == SolvingPhase.DEBUGGING);
    }

    private void setPhaseButtonActive(Button button, boolean active) {
        button.getStyleClass().setAll(active ? "primary-button" : "ghost-button");
    }

    private void setControlsDisabled(boolean disabled) {
        readingPhaseButton.setDisable(disabled);
        thinkingPhaseButton.setDisable(disabled);
        codingPhaseButton.setDisable(disabled);
        debuggingPhaseButton.setDisable(disabled);
        startButton.setDisable(disabled);
        pauseButton.setDisable(disabled);
        resumeButton.setDisable(disabled);
        resetButton.setDisable(disabled);
    }

    private String formatDuration(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%d:%02d", minutes, seconds);
    }

    private String capitalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void setVisible(javafx.scene.Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}

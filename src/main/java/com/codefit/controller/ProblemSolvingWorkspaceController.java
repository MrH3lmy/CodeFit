package com.codefit.controller;

import com.codefit.model.Problem;
import com.codefit.model.ProblemAttempt;
import com.codefit.model.ProblemSolvingSession;
import com.codefit.model.RoadmapEntry;
import com.codefit.model.SessionFinishOutcome;
import com.codefit.model.SolvingPhase;
import com.codefit.model.SubmissionResult;
import com.codefit.service.ProblemSolvingWorkspaceService;
import com.codefit.service.SolvingCheckpointPreferenceService;
import com.codefit.ui.NavigationService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * The structured solving workspace (#145): start/pause/resume/reset a persistent
 * {@link ProblemSolvingSession}, switch between the four solving phases while each keeps its own
 * accumulated time, and finish the session as Submitted/Accepted/Could Not Solve/Abandoned. All
 * timer/session logic lives in {@link ProblemSolvingWorkspaceService}; this controller only renders
 * whatever session state that service returns and forwards user actions to it.
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

    private final ProblemSolvingWorkspaceService workspaceService = new ProblemSolvingWorkspaceService();
    private final SolvingCheckpointPreferenceService checkpointPreferenceService = new SolvingCheckpointPreferenceService();

    private Long problemId;
    private String problemUrl;
    private ProblemSolvingSession currentSession;
    private SubmissionResult selectedVerdict = SubmissionResult.AC;
    private Timeline timer;

    @FXML
    public void initialize() {
        problemId = NavigationService.consumePendingWorkspaceProblemId();
        configureVerdictMenu();
        if (problemId == null) {
            setStatus(messageLabel, "No problem selected. Return to Problems and choose one to work on.");
            setControlsDisabled(true);
            renderSession();
            return;
        }
        loadProblemContext();
        currentSession = workspaceService.loadWorkspace(problemId).session().orElse(null);
        renderSession();
        startTimerLoop();
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

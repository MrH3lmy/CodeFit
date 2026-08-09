package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.repository.InterviewMockRepository;
import com.codefit.service.DeckService;
import com.codefit.service.InterviewDomainReadiness;
import com.codefit.service.InterviewMockService;
import com.codefit.service.InterviewProfileService;
import com.codefit.service.InterviewReadinessResult;
import com.codefit.service.InterviewReadinessService;
import com.codefit.service.InterviewWorkout;
import com.codefit.service.InterviewWorkoutService;
import com.codefit.service.RevolutInterviewContentPackService;
import com.codefit.ui.NavigationService;
import com.codefit.ui.Route;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only interview-preparation dashboard over the existing readiness/workout/mock engines. The
 * only write exposed here is the explicit idempotent installation of the bundled RJ content pack.
 */
public class InterviewPrepController extends BaseController {
    private static final DateTimeFormatter MOCK_DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d · HH:mm");

    @FXML private Label profileTitleLabel;
    @FXML private Label overallStatusLabel;
    @FXML private Label readinessScoreLabel;
    @FXML private Label coverageLabel;
    @FXML private Label criticalSummaryLabel;
    @FXML private Label blockersLabel;
    @FXML private Label contentStatusLabel;
    @FXML private Label actionStatusLabel;
    @FXML private VBox criticalGatesList;
    @FXML private VBox domainList;
    @FXML private Label workoutTotalLabel;
    @FXML private Label reviewBlockTitleLabel;
    @FXML private Label reviewBlockDetailLabel;
    @FXML private Button reviewBlockButton;
    @FXML private Label codingBlockTitleLabel;
    @FXML private Label codingBlockDetailLabel;
    @FXML private Button codingBlockButton;
    @FXML private Label technicalBlockTitleLabel;
    @FXML private Label technicalBlockDetailLabel;
    @FXML private Label scenarioBlockTitleLabel;
    @FXML private Label scenarioBlockDetailLabel;
    @FXML private Label reflectionBlockDetailLabel;
    @FXML private VBox recentMocksList;
    @FXML private Button installContentButton;

    private final InterviewProfileService profileService = new InterviewProfileService();
    private final InterviewReadinessService readinessService = new InterviewReadinessService();
    private final InterviewWorkoutService workoutService = new InterviewWorkoutService();
    private final InterviewMockService mockService = new InterviewMockService();
    private final RevolutInterviewContentPackService contentPackService = new RevolutInterviewContentPackService();
    private final DeckService deckService = new DeckService();

    private InterviewPreparationProfile profile;
    private InterviewWorkout workout;

    @FXML
    public void initialize() {
        refreshDashboard();
    }

    private void refreshDashboard() {
        try {
            profile = profileService.getRevolutJavaProfile();
            InterviewReadinessResult readiness = readinessService.calculate(profile);
            workout = workoutService.build(profile.getId()).orElseThrow();

            profileTitleLabel.setText(profile.getTitle());
            readinessScoreLabel.setText(readiness.overallReadinessPercent() == null
                    ? "—" : readiness.overallReadinessPercent() + "%");
            coverageLabel.setText(readiness.coveragePercent() + "%");
            overallStatusLabel.setText(displayEnum(readiness.status().name()));
            applyStatusStyle(overallStatusLabel, readiness.status().name());

            long passedCritical = readiness.domains().stream()
                    .filter(InterviewDomainReadiness::criticalGate)
                    .filter(domain -> domain.status().name().equals("PASS"))
                    .count();
            long totalCritical = readiness.domains().stream().filter(InterviewDomainReadiness::criticalGate).count();
            criticalSummaryLabel.setText(passedCritical + "/" + totalCritical + " passing");
            blockersLabel.setText(readiness.blockingCriticalDomainIds().isEmpty()
                    ? "All critical gates currently pass."
                    : "Blocking gates: " + readiness.blockingCriticalDomainIds().stream()
                    .map(this::domainTitle)
                    .collect(Collectors.joining(" · ")));

            populateCriticalGates(readiness);
            populateDomains(readiness);
            configureContentPackState();
            configureWorkout(workout);
            populateRecentMocks();
        } catch (RuntimeException exception) {
            setStatus(actionStatusLabel, exception.getMessage() == null
                    ? "Interview dashboard could not be loaded." : exception.getMessage());
        }
    }

    private void populateCriticalGates(InterviewReadinessResult readiness) {
        criticalGatesList.getChildren().clear();
        readiness.domains().stream()
                .filter(InterviewDomainReadiness::criticalGate)
                .map(domain -> createDomainRow(domain, true))
                .forEach(node -> criticalGatesList.getChildren().add(node));
    }

    private void populateDomains(InterviewReadinessResult readiness) {
        domainList.getChildren().clear();
        readiness.domains().stream()
                .map(domain -> createDomainRow(domain, false))
                .forEach(node -> domainList.getChildren().add(node));
    }

    private VBox createDomainRow(InterviewDomainReadiness domain, boolean compact) {
        VBox row = new VBox(compact ? 6 : 8);
        row.getStyleClass().add("interview-domain-row");

        HBox heading = new HBox(10);
        heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label title = new Label(domain.domainTitle());
        title.setWrapText(true);
        title.getStyleClass().add("interview-domain-title");
        HBox.setHgrow(title, Priority.ALWAYS);

        Label score = new Label(domain.scorePercent() == null ? "—" : domain.scorePercent() + "%");
        score.getStyleClass().add("interview-domain-score");

        Label status = new Label(displayEnum(domain.status().name()));
        status.getStyleClass().add("interview-status-badge");
        applyStatusStyle(status, domain.status().name());
        heading.getChildren().addAll(title, score, status);

        ProgressBar progress = new ProgressBar(domain.scorePercent() == null ? 0.0 : domain.scorePercent() / 100.0);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.getStyleClass().add("interview-progress");

        String detailText = "Coverage " + domain.coveragePercent() + "% · "
                + domain.measuredRequirementCount() + "/" + domain.totalRequirementCount() + " evidence sources measured";
        if (domain.criticalGate() && domain.minimumReadinessThresholdPercent() != null) {
            detailText += " · gate " + domain.minimumReadinessThresholdPercent() + "%";
        }
        Label detail = new Label(detailText);
        detail.setWrapText(true);
        detail.getStyleClass().add("interview-domain-detail");

        row.getChildren().addAll(heading, progress, detail);
        return row;
    }

    private void configureContentPackState() {
        Set<String> existingDeckNames = deckService.getDecks().stream()
                .map(Deck::getName)
                .map(name -> name.strip().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<String> rjDeckNames = contentPackService.deckNames();
        long present = rjDeckNames.stream()
                .map(name -> name.strip().toLowerCase(Locale.ROOT))
                .filter(existingDeckNames::contains)
                .count();

        contentStatusLabel.setText(present + "/" + rjDeckNames.size()
                + " RJ decks present. Syncing is idempotent and restores any missing bundled cards.");
        installContentButton.setText(present == 0 ? "Install RJ Content" : "Sync RJ Content");
    }

    private void configureWorkout(InterviewWorkout currentWorkout) {
        workoutTotalLabel.setText("~" + currentWorkout.totalTargetMinutes() + " min");

        reviewBlockTitleLabel.setText("Spaced repetition · " + currentWorkout.reviewSessionMinutes() + " min");
        reviewBlockDetailLabel.setText(currentWorkout.hasReviewWork()
                ? currentWorkout.reviewCardCount() + " adaptive review cards are queued from existing CodeFit mastery."
                : "No review cards are due in the adaptive queue right now.");
        reviewBlockButton.setDisable(!currentWorkout.hasReviewWork());

        if (currentWorkout.codingProblem().isPresent()) {
            var problem = currentWorkout.codingProblem().orElseThrow().problem();
            codingBlockTitleLabel.setText("Live coding · " + currentWorkout.codingTargetMinutes() + " min · " + problem.getTitle());
            codingBlockDetailLabel.setText(currentWorkout.codingReason());
            codingBlockButton.setText("Open Problem");
        } else {
            codingBlockTitleLabel.setText("Live coding · " + currentWorkout.codingTargetMinutes() + " min");
            codingBlockDetailLabel.setText(currentWorkout.codingReason());
            codingBlockButton.setText("Open Problems");
        }

        technicalBlockTitleLabel.setText(currentWorkout.technicalDeepDive().title()
                + " · " + currentWorkout.technicalDeepDive().targetMinutes() + " min");
        technicalBlockDetailLabel.setText(currentWorkout.technicalDeepDive().instruction());

        scenarioBlockTitleLabel.setText(currentWorkout.scenarioDrill().title()
                + " · " + currentWorkout.scenarioDrill().targetMinutes() + " min");
        scenarioBlockDetailLabel.setText(currentWorkout.scenarioDrill().instruction());
        reflectionBlockDetailLabel.setText(currentWorkout.reflection().instruction());
    }

    private void populateRecentMocks() {
        recentMocksList.getChildren().clear();
        List<InterviewMockRepository.StoredRun> runs = mockService.recentRuns(profile.getId(), 5);
        if (runs.isEmpty()) {
            Label empty = new Label("No scored mock interviews yet. Run one when you want direct interview-performance evidence.");
            empty.setWrapText(true);
            empty.getStyleClass().add("dashboard-card-helper");
            recentMocksList.getChildren().add(empty);
            return;
        }
        runs.forEach(run -> recentMocksList.getChildren().add(createMockRow(run)));
    }

    private HBox createMockRow(InterviewMockRepository.StoredRun run) {
        HBox row = new HBox(12);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("interview-mock-row");

        VBox text = new VBox(2);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label title = new Label(displayEnum(run.mode().name()));
        title.getStyleClass().add("interview-domain-title");
        Label detail = new Label(run.completedAt().format(MOCK_DATE_FORMAT));
        detail.getStyleClass().add("interview-domain-detail");
        text.getChildren().addAll(title, detail);

        Label score = new Label(run.overallScorePercent() + "%");
        score.getStyleClass().addAll("interview-status-badge",
                run.overallScorePercent() >= 75 ? "interview-status-pass" : "interview-status-fail");
        row.getChildren().addAll(text, score);
        return row;
    }

    @FXML
    public void startTodayWorkout() {
        if (workout == null) {
            return;
        }
        if (workout.hasReviewWork()) {
            startReviewBlock();
        } else {
            startCodingBlock();
        }
    }

    @FXML
    public void startReviewBlock() {
        if (workout != null && workout.hasReviewWork()) {
            NavigationService.showTimedReview(workout.reviewSessionMinutes());
        }
    }

    @FXML
    public void startCodingBlock() {
        if (workout == null) {
            return;
        }
        workout.codingProblem().ifPresentOrElse(
                entry -> NavigationService.showSolvingWorkspace(entry.problem().getId()),
                NavigationService::showProblems);
    }

    @FXML
    public void openMockInterview() {
        NavigationService.navigate(Route.INTERVIEW_MOCK);
    }

    @FXML
    public void openLibrary() {
        NavigationService.showDecks();
    }

    @FXML
    public void installInterviewContent() {
        try {
            RevolutInterviewContentPackService.InstallSummary summary = contentPackService.install();
            refreshDashboard();
            setStatus(actionStatusLabel, summary.message());
        } catch (RuntimeException exception) {
            setStatus(actionStatusLabel, exception.getMessage() == null
                    ? "RJ content could not be installed." : exception.getMessage());
        }
    }

    private String domainTitle(String domainId) {
        return profile.findDomainById(domainId).map(domain -> domain.getTitle()).orElse(domainId);
    }

    static String displayEnum(String value) {
        if (value == null || value.isBlank()) {
            return "—";
        }
        String[] words = value.toLowerCase(Locale.ROOT).split("_");
        return java.util.Arrays.stream(words)
                .map(word -> Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .collect(Collectors.joining(" "));
    }

    private void applyStatusStyle(Label label, String status) {
        label.getStyleClass().removeAll("interview-status-pass", "interview-status-fail",
                "interview-status-warning", "interview-status-neutral");
        String style = switch (status) {
            case "READY", "PASS", "MEASURED" -> "interview-status-pass";
            case "NOT_READY", "FAIL" -> "interview-status-fail";
            case "INSUFFICIENT_DATA", "PARTIAL" -> "interview-status-warning";
            default -> "interview-status-neutral";
        };
        label.getStyleClass().add(style);
    }
}

package com.codefit.controller;

import com.codefit.model.InterviewPreparationProfile;
import com.codefit.service.InterviewMockEvaluation;
import com.codefit.service.InterviewMockMode;
import com.codefit.service.InterviewMockPlan;
import com.codefit.service.InterviewMockService;
import com.codefit.service.InterviewProfileService;
import com.codefit.ui.NavigationService;
import com.codefit.ui.Route;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Interactive scorer for the mock plans produced by {@link InterviewMockService}. It never invents
 * a criterion score: every rubric field starts blank and completion is rejected until all criteria
 * contain an explicit 0-100 value.
 */
public class InterviewMockController extends BaseController {
    @FXML private ComboBox<InterviewMockMode> modeComboBox;
    @FXML private Label totalTargetLabel;
    @FXML private Label resultLabel;
    @FXML private VBox stagesContainer;
    @FXML private TextArea notesArea;
    @FXML private Button completeButton;

    private final InterviewProfileService profileService = new InterviewProfileService();
    private final InterviewMockService mockService = new InterviewMockService();
    private final Map<String, TextField> scoreFields = new LinkedHashMap<>();

    private InterviewPreparationProfile profile;
    private InterviewMockPlan plan;

    @FXML
    public void initialize() {
        profile = profileService.getRevolutJavaProfile();
        modeComboBox.setItems(FXCollections.observableArrayList(InterviewMockMode.values()));
        modeComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(InterviewMockMode mode) {
                return mode == null ? "" : displayMode(mode);
            }

            @Override
            public InterviewMockMode fromString(String string) {
                return modeComboBox.getValue();
            }
        });
        modeComboBox.valueProperty().addListener((observable, oldMode, newMode) -> {
            if (newMode != null && newMode != oldMode) {
                buildPlan(newMode);
            }
        });
        modeComboBox.setValue(InterviewMockMode.FULL_INTERVIEW_LOOP);
    }

    private void buildPlan(InterviewMockMode mode) {
        try {
            plan = mockService.build(profile.getId(), mode).orElseThrow();
            totalTargetLabel.setText(plan.totalTargetMinutes() + " min target · " + plan.stages().size()
                    + (plan.stages().size() == 1 ? " stage" : " stages"));
            setStatus(resultLabel, "");
            notesArea.clear();
            scoreFields.clear();
            stagesContainer.getChildren().clear();
            plan.stages().forEach(stage -> stagesContainer.getChildren().add(createStageCard(stage)));
            completeButton.setDisable(false);
        } catch (RuntimeException exception) {
            plan = null;
            completeButton.setDisable(true);
            setStatus(resultLabel, exception.getMessage() == null ? "Mock interview could not be generated." : exception.getMessage());
        }
    }

    private VBox createStageCard(InterviewMockPlan.Stage stage) {
        VBox card = new VBox(12);
        card.getStyleClass().add("interview-stage-card");

        HBox heading = new HBox(12);
        heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox titleBox = new VBox(2);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        Label title = new Label(stage.title());
        title.getStyleClass().add("interview-stage-title");
        Label meta = new Label(displayStageType(stage.type()) + " · " + stage.targetMinutes() + " min · "
                + stage.weightPercent() + "% of mock");
        meta.getStyleClass().add("interview-domain-detail");
        titleBox.getChildren().addAll(title, meta);
        heading.getChildren().add(titleBox);

        if (stage.codingProblem().isPresent()) {
            Button problemButton = new Button("Open Problem");
            problemButton.getStyleClass().add("ghost-button");
            long problemId = stage.codingProblem().orElseThrow().problem().getId();
            problemButton.setOnAction(event -> NavigationService.showSolvingWorkspace(problemId));
            heading.getChildren().add(problemButton);
        }

        Label prompt = new Label(stage.prompt());
        prompt.setWrapText(true);
        prompt.getStyleClass().add("interview-prompt-text");

        Label rubricHeading = new Label("SCORE AFTER THE STAGE");
        rubricHeading.getStyleClass().add("section-title");

        VBox rubric = new VBox(8);
        stage.rubric().forEach(criterion -> rubric.getChildren().add(createCriterionRow(criterion)));

        card.getChildren().addAll(heading, prompt, rubricHeading, rubric);
        return card;
    }

    private HBox createCriterionRow(InterviewMockPlan.RubricCriterion criterion) {
        HBox row = new HBox(12);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("interview-rubric-row");

        VBox copy = new VBox(2);
        HBox.setHgrow(copy, Priority.ALWAYS);
        Label title = new Label(criterion.title() + " · " + criterion.weightPercent() + "%");
        title.setWrapText(true);
        title.getStyleClass().add("interview-domain-title");
        Label description = new Label(criterion.description());
        description.setWrapText(true);
        description.getStyleClass().add("interview-domain-detail");
        copy.getChildren().addAll(title, description);

        TextField score = new TextField();
        score.setPromptText("0-100");
        score.setPrefWidth(88);
        score.setMaxWidth(88);
        score.getStyleClass().add("interview-score-field");
        score.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,3}") ? change : null));
        scoreFields.put(criterion.id(), score);

        row.getChildren().addAll(copy, score);
        return row;
    }

    @FXML
    public void completeMock() {
        if (plan == null) {
            return;
        }
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, TextField> entry : scoreFields.entrySet()) {
            OptionalInt parsed = parseScore(entry.getValue().getText());
            if (parsed.isEmpty()) {
                setStatus(resultLabel, "Enter an explicit score from 0 to 100 for every rubric criterion before saving.");
                entry.getValue().requestFocus();
                return;
            }
            scores.put(entry.getKey(), parsed.getAsInt());
        }

        try {
            InterviewMockEvaluation evaluation = mockService.complete(plan, scores, notesArea.getText());
            setStatus(resultLabel, "Mock saved · " + displayMode(evaluation.mode()) + " · "
                    + evaluation.overallScorePercent() + "% overall. This run is now available to the readiness engine.");
            completeButton.setDisable(true);
        } catch (RuntimeException exception) {
            setStatus(resultLabel, exception.getMessage() == null ? "Mock result could not be saved." : exception.getMessage());
        }
    }

    @FXML
    public void resetMock() {
        InterviewMockMode mode = modeComboBox.getValue();
        if (mode != null) {
            buildPlan(mode);
        }
    }

    @FXML
    public void backToInterviewDashboard() {
        NavigationService.navigate(Route.INTERVIEW);
    }

    static OptionalInt parseScore(String raw) {
        if (raw == null || raw.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            int value = Integer.parseInt(raw.strip());
            return value >= 0 && value <= 100 ? OptionalInt.of(value) : OptionalInt.empty();
        } catch (NumberFormatException exception) {
            return OptionalInt.empty();
        }
    }

    static String displayMode(InterviewMockMode mode) {
        return titleCase(mode.name());
    }

    private static String displayStageType(InterviewMockPlan.StageType type) {
        return titleCase(type.name());
    }

    private static String titleCase(String raw) {
        String[] words = raw.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }
}

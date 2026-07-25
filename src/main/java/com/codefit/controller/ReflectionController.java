package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.model.GeneratedCard;
import com.codefit.model.ReflectionDraft;
import com.codefit.model.ReflectionType;
import com.codefit.service.DeckService;
import com.codefit.service.GuidedStage;
import com.codefit.service.ReflectionSaveResult;
import com.codefit.service.ReflectionService;
import com.codefit.ui.NavigationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Captures one work reflection (bug fixed, command gotten wrong, or concept missed), splits it into
 * atomic cards, and lets the learner edit, remove, or merge those cards before anything is saved
 * (#102). The screen has two states toggled by {@link #showCapture()}/{@link #showPreview()}:
 * capture (workflow fields) and preview (the generated card list), the same pattern
 * {@code DecksController} uses for its overview/detail panels.
 */
public class ReflectionController extends BaseController {
    @FXML private VBox captureRoot;
    @FXML private VBox previewRoot;
    @FXML private ComboBox<Deck> deckComboBox;
    @FXML private ComboBox<ReflectionType> workflowComboBox;
    @FXML private Label field1Label;
    @FXML private Label field2Label;
    @FXML private Label field3Label;
    @FXML private Label field4Label;
    @FXML private TextArea field1Area;
    @FXML private TextArea field2Area;
    @FXML private TextArea field3Area;
    @FXML private TextArea field4Area;
    @FXML private Label messageLabel;
    @FXML private Label previewSummaryLabel;
    @FXML private VBox generatedCardsList;
    @FXML private Button saveButton;
    @FXML private Button continueTrainingButton;

    private final DeckService deckService = new DeckService();
    private final ReflectionService reflectionService = new ReflectionService();

    private ReflectionDraft currentDraft;

    @FXML
    public void initialize() {
        deckComboBox.setItems(FXCollections.observableArrayList(deckService.getDecks()));
        workflowComboBox.setItems(FXCollections.observableArrayList(ReflectionType.values()));
        workflowComboBox.valueProperty().addListener((observable, oldValue, newValue) -> applyWorkflowLabels(newValue));

        ReflectionType requestedType = NavigationService.consumePendingReflectionType();
        workflowComboBox.getSelectionModel().select(requestedType == null ? ReflectionType.BUG : requestedType);
        applyWorkflowLabels(workflowComboBox.getValue());

        boolean hasNoDecks = deckComboBox.getItems().isEmpty();
        deckComboBox.setDisable(hasNoDecks);
        if (hasNoDecks) {
            setStatus(messageLabel, "No decks available. Create a deck before adding reflection cards.");
        } else {
            deckComboBox.getSelectionModel().selectFirst();
        }

        // Only shown mid-guided-routine (#111): lets the learner move on to the next stage without
        // saving anything, or right after saving, without losing the review work already recorded.
        boolean guided = NavigationService.isGuidedTrainingActive();
        continueTrainingButton.setVisible(guided);
        continueTrainingButton.setManaged(guided);

        showCapture();
    }

    @FXML
    public void continueGuidedTraining() {
        NavigationService.markGuidedStageDone(GuidedStage.REFLECTION);
        NavigationService.resumeGuidedTraining();
    }

    @FXML
    public void generateCards() {
        Deck deck = deckComboBox.getValue();
        if (deck == null) {
            setStatus(messageLabel, "Choose a deck before generating cards.");
            return;
        }

        try {
            currentDraft = switch (workflowComboBox.getValue()) {
                case BUG -> reflectionService.generateBugReflection(
                        field1Area.getText(), field2Area.getText(), field3Area.getText(), field4Area.getText());
                case COMMAND -> reflectionService.generateCommandReflection(
                        field1Area.getText(), field2Area.getText(), field3Area.getText(), field4Area.getText());
                case MISSED_CONCEPT -> reflectionService.generateMissedConceptReflection(
                        field1Area.getText(), field2Area.getText(), field3Area.getText(), field4Area.getText());
            };
            setStatus(messageLabel, "");
            renderPreview();
            showPreview();
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    @FXML
    public void backToCapture() {
        currentDraft = null;
        showCapture();
    }

    @FXML
    public void saveGeneratedCards() {
        Deck deck = deckComboBox.getValue();
        if (currentDraft == null || currentDraft.size() == 0 || deck == null) {
            setStatus(messageLabel, "Nothing to save yet.");
            return;
        }

        try {
            ReflectionSaveResult result = reflectionService.saveReflection(deck.getId(), currentDraft);
            currentDraft = null;
            clearComposerFields();
            showCapture();
            setStatus(messageLabel, formatSaveSummary(result));
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    private String formatSaveSummary(ReflectionSaveResult result) {
        int savedCount = result.savedCards().size();
        StringBuilder summary = new StringBuilder(savedCount + " reflection " + pluralize(savedCount, "card")
                + " added and scheduled for today.");
        if (result.xpAwarded() > 0) {
            summary.append(" +").append(result.xpAwarded()).append(" XP.");
        } else {
            summary.append(" Daily reflection XP cap reached.");
        }
        int skippedCount = result.skippedDuplicates().size();
        if (skippedCount > 0) {
            summary.append(" Skipped ").append(skippedCount).append(" duplicate ")
                    .append(pluralize(skippedCount, "card")).append(" already in this deck.");
        }
        return summary.toString();
    }

    private String pluralize(int count, String singular) {
        return count == 1 ? singular : singular + "s";
    }

    private void applyWorkflowLabels(ReflectionType type) {
        ReflectionType resolved = type == null ? ReflectionType.BUG : type;
        switch (resolved) {
            case BUG -> configureLabels(
                    "Symptom", "What did you observe that indicated something was wrong?",
                    "Root cause", "What actually caused the bug?",
                    "Fix", "What change fixed it?",
                    "Prevention", "What test, constraint, log, or checklist prevents recurrence?");
            case COMMAND -> configureLabels(
                    "What you tried", "The command or usage that didn't do what you expected.",
                    "Why it was wrong", "What was wrong about it (bad flag, wrong subcommand, wrong assumption)?",
                    "Correct command", "The command you should have used.",
                    "Usage reminder", "When should you reach for this, or what will remind you next time?");
            case MISSED_CONCEPT -> configureLabels(
                    "Concept", "Name the concept you missed.",
                    "Plain-English explanation", "Explain it simply, as if teaching it.",
                    "What confused you", "What signal showed you that you didn't know this?",
                    "Next cue", "What should remind you of this next time you see it?");
        }
    }

    private void configureLabels(String label1, String prompt1, String label2, String prompt2,
                                  String label3, String prompt3, String label4, String prompt4) {
        field1Label.setText(label1);
        field1Area.setPromptText(prompt1);
        field2Label.setText(label2);
        field2Area.setPromptText(prompt2);
        field3Label.setText(label3);
        field3Area.setPromptText(prompt3);
        field4Label.setText(label4);
        field4Area.setPromptText(prompt4);
    }

    private void clearComposerFields() {
        field1Area.clear();
        field2Area.clear();
        field3Area.clear();
        field4Area.clear();
    }

    private void showCapture() {
        setVisible(captureRoot, true);
        setVisible(previewRoot, false);
    }

    private void showPreview() {
        setVisible(captureRoot, false);
        setVisible(previewRoot, true);
    }

    private void setVisible(VBox node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void renderPreview() {
        generatedCardsList.getChildren().clear();
        List<GeneratedCard> cards = currentDraft.getCards();
        for (GeneratedCard card : cards) {
            generatedCardsList.getChildren().add(createGeneratedCardRow(card));
        }

        Deck deck = deckComboBox.getValue();
        long duplicateCount = deck == null ? 0 : reflectionService.findDuplicates(deck.getId(), currentDraft).size();
        String summary = cards.size() + " atomic " + pluralize(cards.size(), "card") + " generated.";
        if (duplicateCount > 0) {
            summary += " " + duplicateCount + " " + (duplicateCount == 1 ? "looks" : "look")
                    + " like a duplicate already in this deck.";
        }
        previewSummaryLabel.setText(summary);
        saveButton.setDisable(cards.isEmpty());
    }

    /** Each row edits the {@code card} it was built for by object identity, looked up fresh on every
     *  keystroke/click — this stays correct even after a remove or merge shifts every other card's
     *  position, without needing to track a row's original index. */
    private VBox createGeneratedCardRow(GeneratedCard card) {
        TextArea frontArea = new TextArea(card.getFront());
        frontArea.setWrapText(true);
        frontArea.setPrefRowCount(2);

        TextArea backArea = new TextArea(card.getBack());
        backArea.setWrapText(true);
        backArea.setPrefRowCount(3);

        Runnable applyEdit = () -> {
            int index = currentDraft.getCards().indexOf(card);
            if (index >= 0) {
                currentDraft.editCard(index, frontArea.getText(), backArea.getText());
            }
        };
        frontArea.textProperty().addListener((observable, oldValue, newValue) -> applyEdit.run());
        backArea.textProperty().addListener((observable, oldValue, newValue) -> applyEdit.run());

        Button removeButton = new Button("Remove");
        removeButton.getStyleClass().add("ghost-button");
        removeButton.setOnAction(event -> {
            int index = currentDraft.getCards().indexOf(card);
            if (index >= 0) {
                currentDraft.removeCard(index);
                renderPreview();
            }
        });

        Button mergeButton = new Button("Merge with next");
        mergeButton.getStyleClass().add("ghost-button");
        int index = currentDraft.getCards().indexOf(card);
        mergeButton.setDisable(index < 0 || index + 1 >= currentDraft.size());
        mergeButton.setOnAction(event -> {
            int currentIndex = currentDraft.getCards().indexOf(card);
            if (currentIndex >= 0 && currentIndex + 1 < currentDraft.size()) {
                currentDraft.mergeCards(currentIndex, currentIndex + 1);
                renderPreview();
            }
        });

        HBox actions = new HBox(8, removeButton, mergeButton);

        Label frontLabel = new Label("Prompt");
        frontLabel.getStyleClass().add("section-title");
        Label backLabel = new Label("Answer");
        backLabel.getStyleClass().add("section-title");

        VBox row = new VBox(8, frontLabel, frontArea, backLabel, backArea, actions);
        row.getStyleClass().add("panel");
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }
}

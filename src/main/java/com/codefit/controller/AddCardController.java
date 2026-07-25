package com.codefit.controller;

import com.codefit.model.CardType;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;
import com.codefit.service.AcceptedAnswerCodec;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import com.codefit.ui.NavigationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

public class AddCardController extends BaseController {
    private static final String DEFAULT_JAVA_BE_SKILL_CATEGORY = "Spring REST";
    private static final String[] JAVA_BE_SKILL_CATEGORIES = {
            "Spring REST", "JPA", "SQL", "Testing", "Security"
    };

    @FXML private ComboBox<Deck> deckComboBox;
    @FXML private ComboBox<CardType> cardTypeComboBox;
    @FXML private ComboBox<ValidationMode> validationModeComboBox;
    @FXML private Spinner<Integer> timeLimitSpinner;
    @FXML private TextField skillCategoryField;
    @FXML private TextArea frontArea;
    @FXML private TextArea backArea;
    @FXML private TextArea hintArea;
    @FXML private TextArea acceptedAnswersArea;
    @FXML private TextArea simulatedOutputArea;
    @FXML private Label messageLabel;
    @FXML private Label frontLabel;
    @FXML private Label backLabel;
    @FXML private Label acceptedAnswersLabel;
    @FXML private Label simulatedOutputLabel;
    @FXML private VBox validationField;
    @FXML private VBox timeLimitField;
    @FXML private VBox frontField;
    @FXML private VBox backField;
    @FXML private VBox hintField;
    @FXML private VBox acceptedAnswersField;
    @FXML private VBox simulatedOutputField;
    @FXML private Label pageTitleLabel;
    @FXML private CheckBox addAnotherCheckBox;
    @FXML private Button saveButton;
    @FXML private Button createDeckButton;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();
    private Long editingCardId;

    @FXML
    public void initialize() {
        deckComboBox.setItems(FXCollections.observableArrayList(deckService.getDecks()));
        cardTypeComboBox.setItems(FXCollections.observableArrayList(CardType.values()));
        cardTypeComboBox.getSelectionModel().select(CardType.RECALL);
        validationModeComboBox.setItems(FXCollections.observableArrayList(ValidationMode.values()));
        validationModeComboBox.getSelectionModel().select(ValidationMode.CASE_INSENSITIVE);
        timeLimitSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 3600, 0, 5));
        cardTypeComboBox.valueProperty().addListener((observable, oldValue, newValue) -> applyTemplate(newValue));
        deckComboBox.valueProperty().addListener((observable, oldValue, newValue) -> suggestJavaBeSkillCategory(newValue));
        applyTemplate(cardTypeComboBox.getValue());
        Long editCardId = NavigationService.consumePendingEditCardId();

        boolean hasNoDecks = deckComboBox.getItems().isEmpty();
        deckComboBox.setDisable(hasNoDecks);
        frontArea.setDisable(hasNoDecks);
        backArea.setDisable(hasNoDecks);
        hintArea.setDisable(hasNoDecks);
        cardTypeComboBox.setDisable(hasNoDecks);
        validationModeComboBox.setDisable(hasNoDecks);
        acceptedAnswersArea.setDisable(hasNoDecks);
        simulatedOutputArea.setDisable(hasNoDecks);
        timeLimitSpinner.setDisable(hasNoDecks);
        skillCategoryField.setDisable(hasNoDecks);
        saveButton.setDisable(hasNoDecks);
        createDeckButton.setVisible(hasNoDecks);
        createDeckButton.setManaged(hasNoDecks);

        if (hasNoDecks) {
            setStatus(messageLabel, "No decks available. Create a deck before adding cards.");
        } else if (editCardId != null) {
            loadCardForEditing(editCardId);
        } else {
            deckComboBox.getSelectionModel().selectFirst();
            suggestJavaBeSkillCategory(deckComboBox.getValue());
        }
    }

    private void loadCardForEditing(long cardId) {
        Flashcard card = flashcardService.getCardById(cardId).orElse(null);
        if (card == null) {
            setStatus(messageLabel, "That card could not be found. It may have been deleted.");
            deckComboBox.getSelectionModel().selectFirst();
            return;
        }

        editingCardId = cardId;
        pageTitleLabel.setText("Edit Card");
        addAnotherCheckBox.setSelected(false);
        addAnotherCheckBox.setVisible(false);
        addAnotherCheckBox.setManaged(false);
        saveButton.setText("Save Changes");

        deckComboBox.getItems().stream()
                .filter(deck -> deck.getId() == card.getDeckId())
                .findFirst()
                .ifPresent(deck -> deckComboBox.getSelectionModel().select(deck));
        cardTypeComboBox.getSelectionModel().select(card.getCardType());
        applyTemplate(card.getCardType());
        frontArea.setText(card.getFront());
        backArea.setText(card.getBack());
        hintArea.setText(card.getHint() == null ? "" : card.getHint());
        acceptedAnswersArea.setText(String.join("\n", AcceptedAnswerCodec.decode(card.getAcceptedAnswers())));
        simulatedOutputArea.setText(card.getSimulatedOutput() == null ? "" : card.getSimulatedOutput());
        skillCategoryField.setText(card.getSkillCategory() == null ? "" : card.getSkillCategory());
        timeLimitSpinner.getValueFactory().setValue(card.getTimeLimitSeconds() == null ? 0 : card.getTimeLimitSeconds());
        validationModeComboBox.getSelectionModel().select(card.getValidationMode());
        setStatus(messageLabel, "Editing an existing card. Changes are saved to its current deck.");
    }

    @FXML
    public void saveCard() {
        persistCard(editingCardId != null || !addAnotherCheckBox.isSelected());
    }

    private void persistCard(boolean closeAfterSave) {
        Deck deck = deckComboBox.getValue();
        if (deck == null) {
            setStatus(messageLabel, "Choose or create a deck before saving a card.");
            return;
        }

        try {
            if (editingCardId != null) {
                flashcardService.updateCard(editingCardId, frontArea.getText(), backArea.getText(),
                        cardTypeComboBox.getValue(), acceptedAnswersArea.getText(), validationModeComboBox.getValue(),
                        simulatedOutputArea.getText(), hintArea.getText(), getTimeLimitSeconds(), skillCategoryField.getText());
                setStatus(messageLabel, "Card updated.");
                NavigationService.showDecks();
                return;
            }

            flashcardService.addCard(deck.getId(), frontArea.getText(), backArea.getText(),
                    cardTypeComboBox.getValue(), acceptedAnswersArea.getText(), validationModeComboBox.getValue(),
                    simulatedOutputArea.getText(), hintArea.getText(), getTimeLimitSeconds(), skillCategoryField.getText());
            clearComposerFields();
            setStatus(messageLabel, "Card added and scheduled for today.");
            if (closeAfterSave) {
                NavigationService.showDecks();
            }
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    private void clearComposerFields() {
        frontArea.clear();
        backArea.clear();
        hintArea.clear();
        acceptedAnswersArea.clear();
        simulatedOutputArea.clear();
        timeLimitSpinner.getValueFactory().setValue(0);
        skillCategoryField.clear();
    }

    private Integer getTimeLimitSeconds() {
        Integer value = timeLimitSpinner.getValue();
        return value == null || value <= 0 ? null : value;
    }

    private void applyTemplate(CardType selectedTemplate) {
        CardType template = selectedTemplate == null ? CardType.RECALL : selectedTemplate;
        boolean command = template.isCommandTemplate();
        boolean codeOutput = template == CardType.CODE_OUTPUT;
        boolean regex = template == CardType.REGEX_PATTERN;
        boolean sql = template == CardType.SQL_QUERY;

        boolean hasPracticeSettings = command || regex || sql || codeOutput;
        setVisible(validationField, hasPracticeSettings);
        setVisible(timeLimitField, command || codeOutput);
        setVisible(acceptedAnswersField, command || regex || sql);
        setVisible(simulatedOutputField, command || codeOutput);
        setVisible(hintField, !regex);

        if (command) {
            validationModeComboBox.getSelectionModel().select(ValidationMode.COMMAND_NORMALIZED);
        } else if (regex) {
            validationModeComboBox.getSelectionModel().select(ValidationMode.REGEX_EXAMPLES);
        } else if (sql || codeOutput) {
            validationModeComboBox.getSelectionModel().select(ValidationMode.NORMALIZED_SPACING);
        } else {
            validationModeComboBox.getSelectionModel().select(ValidationMode.CASE_INSENSITIVE);
        }

        switch (template) {
            case LINUX_COMMAND -> configureCopy(
                    "Task", "Describe the Linux task to complete (for example, list hidden files).",
                    "Canonical command", "Enter the preferred command and a short explanation.",
                    "Accepted command variants", "Accepted Linux commands, one per line (for example: ls -la\nls -al)",
                    "Simulated terminal output", "Optional terminal output shown after reveal");
            case GIT_COMMAND -> configureCopy(
                    "Git task", "Describe the Git operation to perform (for example, undo staged changes).",
                    "Canonical Git command", "Enter the preferred Git command and why it works.",
                    "Accepted Git variants", "Accepted Git commands, one per line",
                    "Simulated Git output", "Optional Git output shown after reveal");
            case SQL_QUERY -> configureCopy(
                    "SQL query prompt", "Describe the table schema and the query result learners should produce.",
                    "Expected query or result", "Enter the expected query, query result, and a brief explanation of important clauses.",
                    "Fixture grading config", "Paste a SqlCardSpecCodec-encoded fixture config (schema/seed/reference "
                            + "query or expected error); attempts are executed against it, not text-matched.",
                    "", "");
            case REGEX_PATTERN -> configureCopy(
                    "Matching requirement", "Describe strings that should match and not match.",
                    "Regex explanation", "Explain the pattern and any flags or anchors.",
                    "Regex grading config", "Single-line JSON: {\"mustMatch\":[\"555-1234\"],\"mustNotMatch\":[\"abc\"],"
                            + "\"flags\":[\"CASE_INSENSITIVE\"],\"matchMode\":\"FULL_MATCH\"} "
                            + "(flags: CASE_INSENSITIVE, MULTILINE, DOTALL; matchMode: FULL_MATCH or FIND). "
                            + "The learner's submitted pattern is compiled and run against these examples, never text-compared.",
                    "", "");
            case CODE_OUTPUT -> configureCopy(
                    "Java BE scenario", "Describe REST endpoint behavior, a Spring annotation, SQL query, or exception scenario.",
                    "Expected behavior", "Enter the expected HTTP status, annotation purpose, query result, or short explanation.",
                    "", "",
                    "Runtime output", "Optional runtime output shown after reveal");
            default -> configureCopy(
                    "Prompt", "Use Java BE prompts such as REST endpoint behavior, a SQL query task, a Spring annotation question, or an exception scenario.",
                    "Answer", "Answer with the expected HTTP status, annotation purpose, query result, or a short explanation.",
                    "Accepted command answers", "Accepted command answers, one per line",
                    "Simulated output", "Optional simulated output shown after reveal");
        }
    }

    private void suggestJavaBeSkillCategory(Deck deck) {
        if (deck == null || deck.getName() == null || !deck.getName().startsWith("Java BE")) {
            return;
        }
        if (skillCategoryField.getText() == null || skillCategoryField.getText().isBlank()) {
            skillCategoryField.setText(javaBeSkillCategoryFor(deck.getName()));
        }
    }

    private String javaBeSkillCategoryFor(String deckName) {
        for (String category : JAVA_BE_SKILL_CATEGORIES) {
            if (deckName.toLowerCase().contains(category.toLowerCase())) {
                return category;
            }
        }
        return DEFAULT_JAVA_BE_SKILL_CATEGORY;
    }

    private void configureCopy(String frontTitle, String frontHelp, String backTitle, String backHelp,
                               String acceptedTitle, String acceptedPrompt, String outputTitle, String outputPrompt) {
        frontLabel.setText(frontTitle);
        frontArea.setPromptText(frontHelp);
        backLabel.setText(backTitle);
        backArea.setPromptText(backHelp);
        acceptedAnswersLabel.setText(acceptedTitle);
        acceptedAnswersArea.setPromptText(acceptedPrompt);
        simulatedOutputLabel.setText(outputTitle);
        simulatedOutputArea.setPromptText(outputPrompt);
    }

    private void setVisible(VBox field, boolean visible) {
        field.setVisible(visible);
        field.setManaged(visible);
    }
}

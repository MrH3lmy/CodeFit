package com.codefit.controller;

import com.codefit.model.CardType;
import com.codefit.model.Deck;
import com.codefit.model.ValidationMode;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;

public class AddCardController extends BaseController {
    private static final String FRONT_PREVIEW_FALLBACK = "Your prompt preview will appear here.";
    private static final String BACK_PREVIEW_FALLBACK = "Your answer preview will appear here.";
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
    @FXML private Label templateHelpLabel;
    @FXML private Label templateExampleLabel;
    @FXML private Label frontLabel;
    @FXML private Label frontHelpLabel;
    @FXML private Label backLabel;
    @FXML private Label backHelpLabel;
    @FXML private Label acceptedAnswersLabel;
    @FXML private Label simulatedOutputLabel;
    @FXML private VBox validationField;
    @FXML private VBox timeLimitField;
    @FXML private VBox frontField;
    @FXML private VBox backField;
    @FXML private VBox hintField;
    @FXML private VBox acceptedAnswersField;
    @FXML private VBox simulatedOutputField;
    @FXML private TitledPane practiceSettingsSection;
    @FXML private Label frontPreviewLabel;
    @FXML private Label backPreviewLabel;
    @FXML private Button saveCardButton;
    @FXML private Button createDeckButton;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();

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
        frontPreviewLabel.setText(previewText(frontArea.getText(), FRONT_PREVIEW_FALLBACK));
        backPreviewLabel.setText(previewText(backArea.getText(), BACK_PREVIEW_FALLBACK));
        frontArea.textProperty().addListener((observable, oldValue, newValue) ->
                frontPreviewLabel.setText(previewText(newValue, FRONT_PREVIEW_FALLBACK)));
        backArea.textProperty().addListener((observable, oldValue, newValue) ->
                backPreviewLabel.setText(previewText(newValue, BACK_PREVIEW_FALLBACK)));
        applyTemplate(cardTypeComboBox.getValue());

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
        saveCardButton.setDisable(hasNoDecks);
        createDeckButton.setVisible(hasNoDecks);
        createDeckButton.setManaged(hasNoDecks);

        if (hasNoDecks) {
            setStatus(messageLabel, "No decks available. Create a deck before adding cards.");
        } else {
            deckComboBox.getSelectionModel().selectFirst();
            suggestJavaBeSkillCategory(deckComboBox.getValue());
            setStatus(messageLabel, "");
        }
    }

    @FXML
    public void saveCard() {
        Deck deck = deckComboBox.getValue();
        if (deck == null) {
            setStatus(messageLabel, "Choose or create a deck before saving a card.");
            return;
        }

        try {
            flashcardService.addCard(deck.getId(), frontArea.getText(), backArea.getText(),
                    cardTypeComboBox.getValue(), acceptedAnswersArea.getText(), validationModeComboBox.getValue(),
                    simulatedOutputArea.getText(), hintArea.getText(), getTimeLimitSeconds(), skillCategoryField.getText());
            frontArea.clear();
            backArea.clear();
            hintArea.clear();
            acceptedAnswersArea.clear();
            simulatedOutputArea.clear();
            timeLimitSpinner.getValueFactory().setValue(0);
            skillCategoryField.clear();
            setStatus(messageLabel, "Card added and scheduled for today.");
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
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
        boolean concept = template == CardType.RECALL || template == CardType.CONCEPT;

        boolean hasPracticeSettings = command || regex || sql || codeOutput;
        setVisible(validationField, hasPracticeSettings);
        setVisible(timeLimitField, command || codeOutput);
        setVisible(acceptedAnswersField, command || regex || sql);
        setVisible(simulatedOutputField, command || codeOutput);
        setVisible(hintField, !regex);
        practiceSettingsSection.setVisible(hasPracticeSettings);
        practiceSettingsSection.setManaged(hasPracticeSettings);

        if (command) {
            validationModeComboBox.getSelectionModel().select(ValidationMode.COMMAND_NORMALIZED);
        } else if (regex || sql || codeOutput) {
            validationModeComboBox.getSelectionModel().select(ValidationMode.NORMALIZED_SPACING);
        } else {
            validationModeComboBox.getSelectionModel().select(ValidationMode.CASE_INSENSITIVE);
        }

        switch (template) {
            case LINUX_COMMAND -> configureCopy(
                    "Linux command template: practice terminal syntax, aliases, and expected output.",
                    "Example: Prompt: Compress logs older than 7 days. Answer: find ./logs -mtime +7 -name \"*.log\" -exec gzip {} \\;",
                    "Task", "Describe the Linux task to complete (for example, list hidden files).",
                    "Canonical command", "Enter the preferred command and a short explanation.",
                    "Accepted command variants", "Accepted Linux commands, one per line (for example: ls -la\nls -al)",
                    "Simulated terminal output", "Optional terminal output shown after reveal");
            case GIT_COMMAND -> configureCopy(
                    "Git command template: capture repository tasks and valid command variants.",
                    "Example: Prompt: Undo staged changes. Answer: git restore --staged .",
                    "Git task", "Describe the Git operation to perform (for example, undo staged changes).",
                    "Canonical Git command", "Enter the preferred Git command and why it works.",
                    "Accepted Git variants", "Accepted Git commands, one per line",
                    "Simulated Git output", "Optional Git output shown after reveal");
            case SQL_QUERY -> configureCopy(
                    "SQL query template: focus on schema, expected query, and equivalent answers.",
                    "Example: Prompt: Given orders(id, status), count shipped orders. Answer: SELECT COUNT(*) FROM orders WHERE status = 'SHIPPED';",
                    "SQL query prompt", "Describe the table schema and the query result learners should produce.",
                    "Expected query or result", "Enter the expected query, query result, and a brief explanation of important clauses.",
                    "Accepted query variants", "Accepted SQL queries, one per line",
                    "", "");
            case REGEX_PATTERN -> configureCopy(
                    "Regex template: define matching requirements and accepted patterns.",
                    "Example: Prompt: Match a 5-digit ZIP code only. Answer: ^\\d{5}$",
                    "Matching requirement", "Describe strings that should match and not match.",
                    "Regex explanation", "Explain the pattern and any flags or anchors.",
                    "Accepted regex patterns", "Accepted regex patterns, one per line",
                    "", "");
            case CODE_OUTPUT -> configureCopy(
                    "Code output template: ask learners to predict Java BE behavior or output.",
                    "Example: Prompt: What happens when a @Transactional service throws a RuntimeException? Answer: The transaction rolls back by default.",
                    "Java BE scenario", "Describe REST endpoint behavior, a Spring annotation, SQL query, or exception scenario.",
                    "Expected behavior", "Enter the expected HTTP status, annotation purpose, query result, or short explanation.",
                    "", "",
                    "Runtime output", "Optional runtime output shown after reveal");
            default -> configureCopy(
                    concept ? "Java BE concept template: capture one focused REST, Spring, SQL, testing, or security idea." : "Command template: practice command syntax and output safely.",
                    command ? "Example: Prompt: Show disk usage for this folder. Answer: du -sh ." : "Example: Prompt: Which annotation maps a controller method to POST /orders? Answer: @PostMapping(\"/orders\") declares the POST route.",
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

    private void configureCopy(String templateHelp, String templateExample, String frontTitle, String frontHelp, String backTitle, String backHelp,
                               String acceptedTitle, String acceptedPrompt, String outputTitle, String outputPrompt) {
        templateHelpLabel.setText(templateHelp);
        templateExampleLabel.setText(templateExample);
        boolean hasExample = templateExample != null && !templateExample.isBlank();
        templateExampleLabel.setVisible(hasExample);
        templateExampleLabel.setManaged(hasExample);
        frontLabel.setText(frontTitle);
        frontHelpLabel.setText(frontHelp);
        backLabel.setText(backTitle);
        backHelpLabel.setText(backHelp);
        acceptedAnswersLabel.setText(acceptedTitle);
        acceptedAnswersArea.setPromptText(acceptedPrompt);
        simulatedOutputLabel.setText(outputTitle);
        simulatedOutputArea.setPromptText(outputPrompt);
    }

    private void setVisible(VBox field, boolean visible) {
        field.setVisible(visible);
        field.setManaged(visible);
    }

    private String previewText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip();
    }
}

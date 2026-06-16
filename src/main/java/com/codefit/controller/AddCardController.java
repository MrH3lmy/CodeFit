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

public class AddCardController extends BaseController {
    private static final String FRONT_PREVIEW_FALLBACK = "Your prompt preview will appear here.";
    private static final String BACK_PREVIEW_FALLBACK = "Your answer preview will appear here.";

    @FXML private ComboBox<Deck> deckComboBox;
    @FXML private ComboBox<CardType> cardTypeComboBox;
    @FXML private ComboBox<ValidationMode> validationModeComboBox;
    @FXML private Spinner<Integer> timeLimitSpinner;
    @FXML private TextArea frontArea;
    @FXML private TextArea backArea;
    @FXML private TextArea hintArea;
    @FXML private TextArea acceptedAnswersArea;
    @FXML private TextArea simulatedOutputArea;
    @FXML private Label messageLabel;
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
        cardTypeComboBox.valueProperty().addListener((observable, oldValue, newValue) -> updateCommandFields());
        frontPreviewLabel.setText(previewText(frontArea.getText(), FRONT_PREVIEW_FALLBACK));
        backPreviewLabel.setText(previewText(backArea.getText(), BACK_PREVIEW_FALLBACK));
        frontArea.textProperty().addListener((observable, oldValue, newValue) ->
                frontPreviewLabel.setText(previewText(newValue, FRONT_PREVIEW_FALLBACK)));
        backArea.textProperty().addListener((observable, oldValue, newValue) ->
                backPreviewLabel.setText(previewText(newValue, BACK_PREVIEW_FALLBACK)));
        updateCommandFields();

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
        saveCardButton.setDisable(hasNoDecks);
        createDeckButton.setVisible(hasNoDecks);
        createDeckButton.setManaged(hasNoDecks);

        if (hasNoDecks) {
            setStatus(messageLabel, "No decks available. Create a deck before adding cards.");
        } else {
            deckComboBox.getSelectionModel().selectFirst();
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
                    simulatedOutputArea.getText(), hintArea.getText(), getTimeLimitSeconds());
            frontArea.clear();
            backArea.clear();
            hintArea.clear();
            acceptedAnswersArea.clear();
            simulatedOutputArea.clear();
            timeLimitSpinner.getValueFactory().setValue(0);
            setStatus(messageLabel, "Card added and scheduled for today.");
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    private Integer getTimeLimitSeconds() {
        Integer value = timeLimitSpinner.getValue();
        return value == null || value <= 0 ? null : value;
    }

    private void updateCommandFields() {
        boolean command = cardTypeComboBox.getValue() == CardType.COMMAND;
        acceptedAnswersArea.setVisible(command);
        acceptedAnswersArea.setManaged(command);
        simulatedOutputArea.setVisible(command);
        simulatedOutputArea.setManaged(command);
        validationModeComboBox.getSelectionModel().select(command ? ValidationMode.COMMAND_NORMALIZED : ValidationMode.CASE_INSENSITIVE);
    }

    private String previewText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.strip();
    }
}

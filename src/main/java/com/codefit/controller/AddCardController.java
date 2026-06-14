package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

public class AddCardController extends BaseController {
    @FXML private ComboBox<Deck> deckComboBox;
    @FXML private TextArea frontArea;
    @FXML private TextArea backArea;
    @FXML private Label messageLabel;
    @FXML private Button saveCardButton;
    @FXML private Button createDeckButton;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();

    @FXML
    public void initialize() {
        deckComboBox.setItems(FXCollections.observableArrayList(deckService.getDecks()));
        boolean hasNoDecks = deckComboBox.getItems().isEmpty();
        deckComboBox.setDisable(hasNoDecks);
        frontArea.setDisable(hasNoDecks);
        backArea.setDisable(hasNoDecks);
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
            flashcardService.addCard(deck.getId(), frontArea.getText(), backArea.getText());
            frontArea.clear();
            backArea.clear();
            setStatus(messageLabel, "Card added and scheduled for today.");
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }
}

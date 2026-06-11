package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;

public class AddCardController extends BaseController {
    @FXML private ComboBox<Deck> deckComboBox;
    @FXML private TextArea frontArea;
    @FXML private TextArea backArea;
    @FXML private Label messageLabel;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();

    @FXML
    public void initialize() {
        deckComboBox.setItems(FXCollections.observableArrayList(deckService.getDecks()));
        if (deckComboBox.getItems().isEmpty()) {
            messageLabel.setText("No decks available. Create a deck before adding cards.");
        } else {
            deckComboBox.getSelectionModel().selectFirst();
            messageLabel.setText("Create a new training card.");
        }
    }

    @FXML
    public void saveCard() {
        Deck deck = deckComboBox.getValue();
        try {
            flashcardService.addCard(deck == null ? 0 : deck.getId(), frontArea.getText(), backArea.getText());
            frontArea.clear();
            backArea.clear();
            messageLabel.setText("Card added and scheduled for today.");
        } catch (RuntimeException exception) {
            messageLabel.setText(exception.getMessage());
        }
    }
}

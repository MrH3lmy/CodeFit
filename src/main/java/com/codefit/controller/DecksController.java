package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;

public class DecksController extends BaseController {
    @FXML private ListView<Deck> deckListView;
    @FXML private ListView<String> cardListView;
    @FXML private TextField deckNameField;
    @FXML private TextArea deckDescriptionArea;
    @FXML private Label messageLabel;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();

    @FXML
    public void initialize() {
        loadDecks();
        deckListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, deck) -> loadCards(deck));
    }

    @FXML
    public void createDeck() {
        try {
            deckService.createDeck(deckNameField.getText(), deckDescriptionArea.getText());
            deckNameField.clear();
            deckDescriptionArea.clear();
            messageLabel.setText("Deck created.");
            loadDecks();
        } catch (RuntimeException exception) {
            messageLabel.setText(exception.getMessage());
        }
    }

    private void loadDecks() {
        deckListView.setItems(FXCollections.observableArrayList(deckService.getDecks()));
        if (deckListView.getItems().isEmpty()) {
            messageLabel.setText("No decks yet. Build your first training deck.");
            cardListView.setItems(FXCollections.observableArrayList("Select or create a deck to inspect cards."));
        } else {
            deckListView.getSelectionModel().selectFirst();
        }
    }

    private void loadCards(Deck deck) {
        if (deck == null) {
            cardListView.setItems(FXCollections.observableArrayList("No deck selected."));
            return;
        }
        var cards = flashcardService.getCardsForDeck(deck.getId()).stream()
                .map(this::formatCard)
                .toList();
        cardListView.setItems(cards.isEmpty()
                ? FXCollections.observableArrayList("No cards in this deck yet.")
                : FXCollections.observableArrayList(cards));
    }

    private String formatCard(Flashcard card) {
        return card.getFront() + "  →  " + card.getBack() + "  • due " + card.getDueDate();
    }
}

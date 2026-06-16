package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class DecksController extends BaseController {
    private static final DateTimeFormatter DUE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d");

    @FXML private ListView<Deck> deckListView;
    @FXML private ListView<Flashcard> cardListView;
    @FXML private TextField deckNameField;
    @FXML private TextArea deckDescriptionArea;
    @FXML private Label messageLabel;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();

    @FXML
    public void initialize() {
        deckListView.setCellFactory(listView -> new DeckCell());
        cardListView.setCellFactory(listView -> new FlashcardCell());
        deckListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, deck) -> loadCards(deck));
        loadDecks();
    }

    @FXML
    public void createDeck() {
        try {
            deckService.createDeck(deckNameField.getText(), deckDescriptionArea.getText());
            deckNameField.clear();
            deckDescriptionArea.clear();
            setStatus(messageLabel, "Deck created.");
            loadDecks();
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    private void loadDecks() {
        deckListView.setItems(FXCollections.observableArrayList(deckService.getDecks()));
        if (deckListView.getItems().isEmpty()) {
            setStatus(messageLabel, "No decks yet. Build your first training deck.");
            deckListView.setPlaceholder(new Label("Create a deck to start organizing cards."));
            cardListView.setPlaceholder(new Label("Select or create a deck to inspect cards."));
            cardListView.getItems().clear();
        } else {
            deckListView.getSelectionModel().selectFirst();
        }
    }

    private void loadCards(Deck deck) {
        if (deck == null) {
            cardListView.setPlaceholder(new Label("No deck selected."));
            cardListView.getItems().clear();
            return;
        }

        var cards = FXCollections.observableArrayList(flashcardService.getCardsForDeck(deck.getId()));
        cardListView.setPlaceholder(new Label("No cards in this deck yet."));
        cardListView.setItems(cards);
    }

    private static String displayText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private final class DeckCell extends ListCell<Deck> {
        private final Label nameLabel = new Label();
        private final Label descriptionLabel = new Label();
        private final Label metadataLabel = new Label();
        private final VBox content = new VBox(4, nameLabel, descriptionLabel, metadataLabel);

        private DeckCell() {
            nameLabel.getStyleClass().add("deck-row-title");
            nameLabel.setWrapText(true);
            nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            nameLabel.maxWidthProperty().bind(widthProperty().subtract(28));

            descriptionLabel.getStyleClass().add("deck-row-description");
            descriptionLabel.setWrapText(true);
            descriptionLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            descriptionLabel.maxWidthProperty().bind(widthProperty().subtract(28));

            metadataLabel.getStyleClass().add("deck-row-stats");
            metadataLabel.setWrapText(true);
            metadataLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            metadataLabel.maxWidthProperty().bind(widthProperty().subtract(28));

            content.getStyleClass().add("deck-row");
            content.setMaxWidth(Double.MAX_VALUE);

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(Deck deck, boolean empty) {
            super.updateItem(deck, empty);
            if (empty || deck == null) {
                setGraphic(null);
                return;
            }

            List<Flashcard> cards = flashcardService.getCardsForDeck(deck.getId());
            long totalCards = cards.size();
            long dueCards = countDueCards(cards);
            long reviewedCards = cards.stream()
                    .filter(card -> card.getReviewCount() > 0)
                    .count();
            long reviewedPercent = totalCards == 0 ? 0 : Math.round(reviewedCards * 100.0 / totalCards);

            nameLabel.setText(displayText(deck.getName(), "Untitled deck"));
            descriptionLabel.setText(displayText(deck.getDescription(), "No description yet."));
            metadataLabel.setText(String.format(
                    "%d %s • %d due • %d%% reviewed",
                    totalCards,
                    totalCards == 1 ? "card" : "cards",
                    dueCards,
                    reviewedPercent));
            setGraphic(content);
        }

        private long countDueCards(List<Flashcard> cards) {
            LocalDate today = LocalDate.now();
            return cards.stream()
                    .filter(card -> card.getDueDate() != null && !card.getDueDate().isAfter(today))
                    .count();
        }
    }

    private static final class FlashcardCell extends ListCell<Flashcard> {
        private final Label promptLabel = new Label();
        private final Label answerLabel = new Label();
        private final Label dueBadge = new Label();
        private final VBox textBlock = new VBox(5, promptLabel, answerLabel);
        private final HBox content = new HBox(12, textBlock, dueBadge);

        private FlashcardCell() {
            promptLabel.getStyleClass().add("card-row-prompt");
            promptLabel.setWrapText(true);
            promptLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            promptLabel.maxWidthProperty().bind(widthProperty().subtract(132));

            answerLabel.getStyleClass().add("card-row-answer");
            answerLabel.setWrapText(true);
            answerLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            answerLabel.maxWidthProperty().bind(widthProperty().subtract(132));

            dueBadge.getStyleClass().add("due-badge");
            dueBadge.setMinWidth(76);
            dueBadge.setAlignment(Pos.CENTER);

            textBlock.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(textBlock, Priority.ALWAYS);

            content.getStyleClass().add("card-row");
            content.setAlignment(Pos.TOP_LEFT);
            content.setMaxWidth(Double.MAX_VALUE);

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(Flashcard card, boolean empty) {
            super.updateItem(card, empty);
            if (empty || card == null) {
                setGraphic(null);
                return;
            }

            promptLabel.setText(displayText(card.getFront(), "Untitled prompt"));
            answerLabel.setText(displayText(card.getBack(), "No answer yet."));
            dueBadge.setText(card.getDueDate() == null ? "No due" : "Due " + DUE_DATE_FORMATTER.format(card.getDueDate()));
            setGraphic(content);
        }
    }
}

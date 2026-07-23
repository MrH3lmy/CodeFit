package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardImportExportService;
import com.codefit.service.FlashcardImportExportService.ImportExportException;
import com.codefit.service.FlashcardImportExportService.ImportSummary;
import com.codefit.service.FlashcardService;
import com.codefit.service.MasteryService;
import com.codefit.service.SyllabusService;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class DecksController extends BaseController {
    private static final DateTimeFormatter DUE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d");
    private static final Pattern JAVA_BE_MODULE_PATTERN = Pattern.compile("^\\s*Java\\s+BE\\s+(\\d{1,2})\\b.*", Pattern.CASE_INSENSITIVE);

    @FXML private ListView<Deck> deckListView;
    @FXML private ListView<Flashcard> cardListView;
    @FXML private TextField deckNameField;
    @FXML private TextArea deckDescriptionArea;
    @FXML private Label messageLabel;
    @FXML private Label deckEmptyGuidanceLabel;
    @FXML private Label cardEmptyGuidanceLabel;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();
    private final MasteryService masteryService = new MasteryService();
    private final SyllabusService syllabusService = new SyllabusService();
    private final FlashcardImportExportService importExportService = new FlashcardImportExportService(flashcardService);

    @FXML
    public void initialize() {
        deckListView.setCellFactory(listView -> new DeckCell());
        cardListView.setCellFactory(listView -> new FlashcardCell());
        deckListView.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, deck) -> loadCards(deck));
        loadDecks();
    }


    @FXML
    public void importCards() {
        Deck deck = selectedDeckOrWarn();
        if (deck == null) {
            return;
        }
        File file = tsvFileChooser("Import Cards").showOpenDialog(window());
        if (file == null) {
            return;
        }

        try {
            ImportSummary summary = importExportService.importAnkiTsv(deck.getId(), file.toPath());
            setStatus(messageLabel, summary.message());
            loadCards(deck);
            deckListView.refresh();
        } catch (ImportExportException exception) {
            ImportSummary summary = exception.getSummary();
            String prefix = summary == null ? "Import completed with errors." : summary.message();
            setStatus(messageLabel, prefix + " " + String.join(" ", exception.getRowErrors()));
            loadCards(deck);
            deckListView.refresh();
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    @FXML
    public void exportCards() {
        Deck deck = selectedDeckOrWarn();
        if (deck == null) {
            return;
        }
        FileChooser fileChooser = tsvFileChooser("Export Cards");
        fileChooser.setInitialFileName(safeFileName(deck.getName()) + "-cards.tsv");
        File file = fileChooser.showSaveDialog(window());
        if (file == null) {
            return;
        }

        try {
            importExportService.exportDeckToAnkiTsv(deck.getId(), file.toPath());
            setStatus(messageLabel, "Exported " + flashcardService.getCardsForDeck(deck.getId()).size() + " cards to " + file.getName() + ".");
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    @FXML
    public void createJavaBackendPath() {
        try {
            deckService.createJavaBackendPath();
            loadDecks();
            setStatus(messageLabel, "Java Backend Engineering path added.");
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
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
            setStatus(messageLabel, "No decks yet. Next action: create your first deck with a focused topic.");
            setStatus(deckEmptyGuidanceLabel, "No decks yet. Create your first deck with a focused topic like Java basics or Git commands.");
            setStatus(cardEmptyGuidanceLabel, "Cards will appear here after you create a deck and add your first card.");
            deckListView.setPlaceholder(new Label("Next action: enter a deck name, then choose Create Deck."));
            cardListView.setPlaceholder(new Label("Create a deck first, then add cards from Add Flashcard."));
            cardListView.getItems().clear();
        } else {
            setStatus(deckEmptyGuidanceLabel, "");
            deckListView.getSelectionModel().selectFirst();
        }
    }

    private void loadCards(Deck deck) {
        if (deck == null) {
            setStatus(cardEmptyGuidanceLabel, "Select a deck to inspect its cards, or create one if your deck list is empty.");
            cardListView.setPlaceholder(new Label("No deck selected. Next action: select or create a deck."));
            cardListView.getItems().clear();
            return;
        }

        var cards = FXCollections.observableArrayList(flashcardService.getCardsForDeck(deck.getId()));
        boolean hasCards = !cards.isEmpty();
        setStatus(cardEmptyGuidanceLabel, hasCards
                ? ""
                : "No cards in this deck yet. Next action: add your first card to this deck from Add Flashcard.");
        cardListView.setPlaceholder(new Label("Next action: choose Add Card and save the first prompt for this deck."));
        cardListView.setItems(cards);
    }


    private Deck selectedDeckOrWarn() {
        Deck deck = deckListView.getSelectionModel().getSelectedItem();
        if (deck == null) {
            setStatus(messageLabel, "Select a deck before importing or exporting cards.");
        }
        return deck;
    }

    private FileChooser tsvFileChooser(String title) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Tab-separated text", "*.tsv", "*.txt"));
        return fileChooser;
    }

    private Window window() {
        return deckListView.getScene() == null ? null : deckListView.getScene().getWindow();
    }

    private String safeFileName(String value) {
        String normalized = value == null || value.isBlank() ? "deck" : value.strip().toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
        return normalized.isBlank() ? "deck" : normalized;
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
            long masteredPercent = Math.round(masteryService.summarize(cards).masteredPercent());

            nameLabel.setText(displayText(deck.getName(), "Untitled deck"));
            descriptionLabel.setText(displayText(deck.getDescription(), "No description yet."));
            metadataLabel.setText(formatMetadata(deck, totalCards, dueCards, masteredPercent));
            setGraphic(content);
        }

        private String formatMetadata(Deck deck, long totalCards, long dueCards, long masteredPercent) {
            String cardSummary = String.format(
                    "%d %s • %d due • %d%% mastered",
                    totalCards,
                    totalCards == 1 ? "card" : "cards",
                    dueCards,
                    masteredPercent);
            return detectJavaBackendModule(deck)
                    .stream()
                    .mapToObj(moduleNumber -> String.format("Module %02d • %s", moduleNumber, cardSummary))
                    .findFirst()
                    .orElse(cardSummary);
        }

        private OptionalInt detectJavaBackendModule(Deck deck) {
            if (deck == null) {
                return OptionalInt.empty();
            }

            OptionalInt syllabusModule = syllabusService.getJavaBackendModules().stream()
                    .filter(module -> module.getDeckId() == deck.getId()
                            || module.getDeckName().equalsIgnoreCase(deck.getName()))
                    .mapToInt(module -> module.getModuleNumber())
                    .findFirst();
            if (syllabusModule.isPresent()) {
                return syllabusModule;
            }

            return parseJavaBackendModule(deck.getName());
        }

        private OptionalInt parseJavaBackendModule(String deckName) {
            if (deckName == null) {
                return OptionalInt.empty();
            }

            Matcher matcher = JAVA_BE_MODULE_PATTERN.matcher(deckName);
            if (!matcher.matches()) {
                return OptionalInt.empty();
            }

            return OptionalInt.of(Integer.parseInt(matcher.group(1)));
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

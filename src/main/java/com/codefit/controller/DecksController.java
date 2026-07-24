package com.codefit.controller;

import com.codefit.model.CardState;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardImportExportService;
import com.codefit.service.FlashcardImportExportService.ImportExportException;
import com.codefit.service.FlashcardImportExportService.ImportSummary;
import com.codefit.service.FlashcardService;
import com.codefit.service.MasteryService;
import com.codefit.service.SyllabusService;
import com.codefit.ui.NavigationService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DecksController extends BaseController {
    private static final DateTimeFormatter DUE_DATE_FORMATTER = DateTimeFormatter.ofPattern("MMM d");
    private static final Pattern JAVA_BE_MODULE_PATTERN = Pattern.compile("^\\s*Java\\s+BE\\s+(\\d{1,2})\\b.*", Pattern.CASE_INSENSITIVE);

    @FXML private VBox libraryOverviewRoot;
    @FXML private VBox deckDetailRoot;
    @FXML private VBox deckCardsList;
    @FXML private ListView<Flashcard> cardListView;
    @FXML private TextField deckNameField;
    @FXML private TextArea deckDescriptionArea;
    @FXML private TextField searchField;
    @FXML private TextField cardSearchField;
    @FXML private MenuButton cardStatusMenuButton;
    @FXML private VBox createDeckPanel;
    @FXML private Button newDeckToggleButton;
    @FXML private Label messageLabel;
    @FXML private Label deckEmptyGuidanceLabel;
    @FXML private Label cardEmptyGuidanceLabel;
    @FXML private Label deckDetailNameLabel;
    @FXML private Label deckDetailDescriptionLabel;

    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();
    private final MasteryService masteryService = new MasteryService();
    private final SyllabusService syllabusService = new SyllabusService();
    private final FlashcardImportExportService importExportService = new FlashcardImportExportService(flashcardService);

    private enum CardFilter { ALL, DUE, MASTERED, SUSPENDED }

    private CardFilter cardFilter = CardFilter.ALL;
    private Deck currentDeck;

    @FXML
    public void initialize() {
        cardListView.setCellFactory(listView -> new FlashcardCell());
        configureSearch();
        showLibraryOverview();
        loadDeckCards();
    }

    private void configureSearch() {
        searchField.textProperty().addListener((observable, oldValue, newValue) -> loadDeckCards());
        cardSearchField.textProperty().addListener((observable, oldValue, newValue) -> loadCards(currentDeck));
    }

    @FXML
    public void showAllCards() {
        setCardFilter(CardFilter.ALL, "Status: All");
    }

    @FXML
    public void showDueCards() {
        setCardFilter(CardFilter.DUE, "Status: Due");
    }

    @FXML
    public void showMasteredCards() {
        setCardFilter(CardFilter.MASTERED, "Status: Mastered");
    }

    @FXML
    public void showSuspendedCards() {
        setCardFilter(CardFilter.SUSPENDED, "Status: Suspended");
    }

    private void setCardFilter(CardFilter filter, String displayName) {
        cardFilter = filter;
        cardStatusMenuButton.setText(displayName);
        loadCards(currentDeck);
    }

    @FXML
    public void toggleCreateDeckPanel() {
        boolean nowVisible = !createDeckPanel.isVisible();
        createDeckPanel.setVisible(nowVisible);
        createDeckPanel.setManaged(nowVisible);
        newDeckToggleButton.setText(nowVisible ? "Close" : "+ New Deck");
    }

    @FXML
    public void importCards() {
        Deck deck = currentDeck;
        if (deck == null) {
            setStatus(messageLabel, "Open a deck before importing cards.");
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
        } catch (ImportExportException exception) {
            ImportSummary summary = exception.getSummary();
            String prefix = summary == null ? "Import completed with errors." : summary.message();
            setStatus(messageLabel, prefix + " " + String.join(" ", exception.getRowErrors()));
            loadCards(deck);
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    @FXML
    public void exportCards() {
        Deck deck = currentDeck;
        if (deck == null) {
            setStatus(messageLabel, "Open a deck before exporting cards.");
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
            setStatus(messageLabel, "Exported " + flashcardService.getCardsForDeck(deck.getId()).size()
                    + " cards to " + file.getName() + ".");
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    @FXML
    public void createJavaBackendPath() {
        try {
            deckService.createJavaBackendPath();
            loadDeckCards();
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
            loadDeckCards();
        } catch (RuntimeException exception) {
            setStatus(messageLabel, exception.getMessage());
        }
    }

    private void openDeck(Deck deck) {
        currentDeck = deck;
        deckDetailNameLabel.setText(displayText(deck.getName(), "Untitled deck"));
        deckDetailDescriptionLabel.setText(displayText(deck.getDescription(), "No description yet."));
        cardSearchField.clear();
        cardFilter = CardFilter.ALL;
        cardStatusMenuButton.setText("Status: All");
        showDeckDetail();
        loadCards(deck);
    }

    @FXML
    public void backToLibrary() {
        currentDeck = null;
        cardSearchField.clear();
        showLibraryOverview();
        loadDeckCards();
    }

    private void showLibraryOverview() {
        setVisible(libraryOverviewRoot, true);
        setVisible(deckDetailRoot, false);
    }

    private void showDeckDetail() {
        setVisible(libraryOverviewRoot, false);
        setVisible(deckDetailRoot, true);
    }

    private void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void loadDeckCards() {
        deckCardsList.getChildren().clear();
        List<Deck> allDecks = deckService.getDecks();
        String search = deckSearchText();
        List<Deck> filtered = allDecks.stream()
                .filter(deck -> matchesDeckSearch(deck, search))
                .toList();

        if (allDecks.isEmpty()) {
            setStatus(deckEmptyGuidanceLabel,
                    "No decks yet. Create your first deck with a focused topic like Java basics or Git commands.");
            return;
        }
        if (filtered.isEmpty()) {
            setStatus(deckEmptyGuidanceLabel, "No decks match your search. Try clearing it.");
            return;
        }
        setStatus(deckEmptyGuidanceLabel, "");
        filtered.forEach(deck -> deckCardsList.getChildren().add(createDeckCardRow(deck)));
    }

    private VBox createDeckCardRow(Deck deck) {
        List<Flashcard> cards = flashcardService.getCardsForDeck(deck.getId());
        long dueCount = countDueCards(cards);
        long masteredPercent = Math.round(masteryService.summarize(cards).masteredPercent());

        Label nameLabel = new Label(displayText(deck.getName(), "Untitled deck"));
        nameLabel.getStyleClass().add("deck-row-title");
        nameLabel.setWrapText(true);

        Label descriptionLabel = new Label(displayText(deck.getDescription(), "No description yet."));
        descriptionLabel.getStyleClass().add("deck-row-description");
        descriptionLabel.setWrapText(true);

        String cardSummary = String.format("%d %s • %d due • %d%% mastered",
                cards.size(), cards.size() == 1 ? "card" : "cards", dueCount, masteredPercent);
        Label metadataLabel = new Label(detectJavaBackendModule(deck)
                .stream()
                .mapToObj(moduleNumber -> String.format("Module %02d • %s", moduleNumber, cardSummary))
                .findFirst()
                .orElse(cardSummary));
        metadataLabel.getStyleClass().add("deck-row-stats");

        VBox textColumn = new VBox(4, nameLabel, descriptionLabel, metadataLabel);
        textColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textColumn, Priority.ALWAYS);

        HBox row = new HBox(textColumn);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("deck-row");
        row.setCursor(Cursor.HAND);
        row.setFocusTraversable(true);
        row.setOnMouseClicked(event -> openDeck(deck));
        row.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                openDeck(deck);
                event.consume();
            }
        });

        VBox wrapper = new VBox(row);
        wrapper.getStyleClass().add("panel");
        wrapper.setMaxWidth(Double.MAX_VALUE);
        return wrapper;
    }

    private boolean matchesDeckSearch(Deck deck, String search) {
        if (search.isEmpty()) {
            return true;
        }
        if (deck.getName() != null && deck.getName().toLowerCase().contains(search)) {
            return true;
        }
        return deck.getDescription() != null && deck.getDescription().toLowerCase().contains(search);
    }

    private void loadCards(Deck deck) {
        if (deck == null) {
            cardListView.getItems().clear();
            return;
        }

        List<Flashcard> allCards = flashcardService.getCardsForDeck(deck.getId());
        String search = cardSearchText();
        var cards = FXCollections.observableArrayList(allCards.stream()
                .filter(card -> matchesCardSearch(card, search))
                .filter(this::matchesCardFilter)
                .toList());
        boolean hasCards = !allCards.isEmpty();
        boolean hasVisibleCards = !cards.isEmpty();
        if (!hasCards) {
            setStatus(cardEmptyGuidanceLabel,
                    "No cards in this deck yet. Add your first card from the global New Card action.");
            cardListView.setPlaceholder(new Label("This deck does not contain any cards yet."));
        } else if (!hasVisibleCards) {
            setStatus(cardEmptyGuidanceLabel, "No cards match the current search and status filter.");
            cardListView.setPlaceholder(new Label("Clear the search or choose another status."));
        } else {
            setStatus(cardEmptyGuidanceLabel, "");
        }
        cardListView.setItems(cards);
    }

    private boolean matchesCardSearch(Flashcard card, String search) {
        if (search.isEmpty()) {
            return true;
        }
        return containsIgnoreCase(card.getFront(), search)
                || containsIgnoreCase(card.getBack(), search)
                || containsIgnoreCase(card.getSkillCategory(), search);
    }

    private boolean containsIgnoreCase(String value, String normalizedSearch) {
        return value != null && value.toLowerCase().contains(normalizedSearch);
    }

    private boolean matchesCardFilter(Flashcard card) {
        return switch (cardFilter) {
            case ALL -> true;
            case DUE -> isDue(card);
            case MASTERED -> masteryService.getMasteryState(card) == MasteryService.CardMasteryState.MASTERED;
            case SUSPENDED -> card.getCardState() == CardState.SUSPENDED;
        };
    }

    private boolean isDue(Flashcard card) {
        return card.getDueDate() != null && !card.getDueDate().isAfter(LocalDate.now());
    }

    private long countDueCards(List<Flashcard> cards) {
        LocalDate today = LocalDate.now();
        return cards.stream()
                .filter(card -> card.getDueDate() != null && !card.getDueDate().isAfter(today))
                .count();
    }

    private String deckSearchText() {
        String text = searchField == null ? null : searchField.getText();
        return normalizeSearch(text);
    }

    private String cardSearchText() {
        String text = cardSearchField == null ? null : cardSearchField.getText();
        return normalizeSearch(text);
    }

    private String normalizeSearch(String value) {
        return value == null ? "" : value.strip().toLowerCase();
    }

    private FileChooser tsvFileChooser(String title) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Tab-separated text", "*.tsv", "*.txt"));
        return fileChooser;
    }

    private Window window() {
        return cardListView.getScene() == null ? null : cardListView.getScene().getWindow();
    }

    private String safeFileName(String value) {
        String normalized = value == null || value.isBlank()
                ? "deck"
                : value.strip().toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
        return normalized.isBlank() ? "deck" : normalized;
    }

    private static String displayText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
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

    private static final class FlashcardCell extends ListCell<Flashcard> {
        private final Label promptLabel = new Label();
        private final Label answerLabel = new Label();
        private final Label dueBadge = new Label();
        private final Button editButton = new Button("Edit");
        private final VBox textBlock = new VBox(5, promptLabel, answerLabel);
        private final VBox actionColumn = new VBox(6, dueBadge, editButton);
        private final HBox content = new HBox(12, textBlock, actionColumn);

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

            editButton.getStyleClass().add("ghost-button");
            editButton.setMinWidth(76);
            editButton.setOnAction(event -> {
                Flashcard card = getItem();
                if (card != null) {
                    NavigationService.showEditCard(card.getId());
                }
            });

            actionColumn.setAlignment(Pos.CENTER);

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
            dueBadge.setText(card.getDueDate() == null
                    ? "No due"
                    : "Due " + DUE_DATE_FORMATTER.format(card.getDueDate()));
            setGraphic(content);
        }
    }
}

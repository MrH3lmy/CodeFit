package com.codefit.controller;

import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.UserProgress;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import com.codefit.service.ProgressService;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class DashboardController extends BaseController {
    @FXML private Label levelLabel;
    @FXML private Label xpLabel;
    @FXML private Label streakLabel;
    @FXML private Label deckCountLabel;
    @FXML private Label cardCountLabel;
    @FXML private Label dueCountLabel;
    @FXML private Label emptyStateLabel;
    @FXML private ProgressBar levelProgressBar;
    @FXML private VBox recentDecksList;

    private final ProgressService progressService = new ProgressService();
    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();

    @FXML
    public void initialize() {
        UserProgress progress = progressService.getProgress();
        List<Deck> decks = deckService.getDecks();
        int deckCount = decks.size();
        int cardCount = flashcardService.countAllCards();
        int dueCount = flashcardService.countDueCards();

        levelLabel.setText("Level " + progress.getLevel());
        xpLabel.setText(formatXpProgress(progress));
        streakLabel.setText(progress.getStreakDays() + " day streak");
        deckCountLabel.setText(String.valueOf(deckCount));
        cardCountLabel.setText(String.valueOf(cardCount));
        dueCountLabel.setText(dueCount + " cards due");
        levelProgressBar.setProgress(calculateLevelProgress(progress));

        populateRecentDecks(decks);

        if (deckCount == 0) {
            setStatus(emptyStateLabel, "No decks yet. Create a deck to start your first CodeFit training loop.");
        } else if (cardCount == 0) {
            setStatus(emptyStateLabel, "Decks are ready. Add cards to begin reviews.");
        } else if (dueCount == 0) {
            setStatus(emptyStateLabel, "No due reviews. Your queue is clear for now.");
        } else {
            setStatus(emptyStateLabel, "You have cards ready to review.");
        }
    }

    private String formatXpProgress(UserProgress progress) {
        return getCurrentLevelXp(progress) + " / " + ProgressService.XP_PER_LEVEL + " XP";
    }

    private double calculateLevelProgress(UserProgress progress) {
        return getCurrentLevelXp(progress) / (double) ProgressService.XP_PER_LEVEL;
    }

    private int getCurrentLevelXp(UserProgress progress) {
        return progress.getXp() % ProgressService.XP_PER_LEVEL;
    }

    private void populateRecentDecks(List<Deck> decks) {
        recentDecksList.getChildren().clear();

        Set<Long> dueCardIds = flashcardService.getDueCards().stream()
                .map(Flashcard::getId)
                .collect(Collectors.toCollection(HashSet::new));

        decks.stream()
                .limit(3)
                .map(deck -> createDeckRow(deck, dueCardIds))
                .forEach(row -> recentDecksList.getChildren().add(row));
    }

    private GridPane createDeckRow(Deck deck, Set<Long> dueCardIds) {
        List<Flashcard> deckCards = flashcardService.getCardsForDeck(deck.getId());
        int cardCount = deckCards.size();
        long dueCount = deckCards.stream()
                .filter(card -> dueCardIds.contains(card.getId()))
                .count();
        int progressPercent = calculateProgressPercent(deckCards);

        GridPane row = new GridPane();
        row.setHgap(12);
        row.setVgap(10);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getStyleClass().add("deck-progress-row");

        ColumnConstraints iconColumn = new ColumnConstraints();
        iconColumn.setPercentWidth(10);
        ColumnConstraints nameColumn = new ColumnConstraints();
        nameColumn.setPercentWidth(38);
        nameColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints countColumn = new ColumnConstraints();
        countColumn.setPercentWidth(18);
        ColumnConstraints percentColumn = new ColumnConstraints();
        percentColumn.setPercentWidth(16);
        ColumnConstraints dueColumn = new ColumnConstraints();
        dueColumn.setPercentWidth(18);
        row.getColumnConstraints().addAll(iconColumn, nameColumn, countColumn, percentColumn, dueColumn);

        Label iconLabel = new Label("🗂");
        iconLabel.getStyleClass().add("deck-icon");

        Label nameLabel = new Label(deck.getName());
        nameLabel.getStyleClass().add("deck-name");

        Label countLabel = new Label(cardCount + (cardCount == 1 ? " card" : " cards"));
        countLabel.getStyleClass().add("deck-count");

        Label percentLabel = new Label(progressPercent + "%");
        percentLabel.getStyleClass().add("deck-percent");

        Label dueLabel = new Label(dueCount + " due");
        dueLabel.getStyleClass().add("deck-due-label");

        row.add(iconLabel, 0, 0);
        row.add(nameLabel, 1, 0);
        row.add(countLabel, 2, 0);
        row.add(percentLabel, 3, 0);
        row.add(dueLabel, 4, 0);

        return row;
    }

    private int calculateProgressPercent(List<Flashcard> deckCards) {
        if (deckCards.isEmpty()) {
            return 0;
        }

        long reviewedCards = deckCards.stream()
                .filter(card -> card.getReviewCount() > 0)
                .count();
        return (int) Math.round((reviewedCards * 100.0) / deckCards.size());
    }

}

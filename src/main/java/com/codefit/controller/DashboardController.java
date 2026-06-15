package com.codefit.controller;

import com.codefit.model.UserProgress;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import com.codefit.service.ProgressService;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

public class DashboardController extends BaseController {
    @FXML private Label levelLabel;
    @FXML private Label xpLabel;
    @FXML private Label streakLabel;
    @FXML private Label deckCountLabel;
    @FXML private Label cardCountLabel;
    @FXML private Label dueCountLabel;
    @FXML private Label emptyStateLabel;
    @FXML private ProgressBar levelProgressBar;

    private final ProgressService progressService = new ProgressService();
    private final DeckService deckService = new DeckService();
    private final FlashcardService flashcardService = new FlashcardService();

    @FXML
    public void initialize() {
        UserProgress progress = progressService.getProgress();
        int deckCount = deckService.getDecks().size();
        int cardCount = flashcardService.countAllCards();
        int dueCount = flashcardService.countDueCards();

        levelLabel.setText("Level " + progress.getLevel());
        xpLabel.setText((progress.getXp() % 100) + " XP / 100 XP");
        streakLabel.setText(progress.getStreakDays() + " day streak");
        deckCountLabel.setText(String.valueOf(deckCount));
        cardCountLabel.setText(String.valueOf(cardCount));
        dueCountLabel.setText(dueCount + " cards due");
        levelProgressBar.setProgress((progress.getXp() % 100) / 100.0);

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
}

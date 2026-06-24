package com.codefit.controller;

import com.codefit.model.DailyQuest;
import com.codefit.model.DailyQuestObjectiveType;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.SyllabusModule;
import com.codefit.model.UserProgress;
import com.codefit.service.DailyQuestService;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import com.codefit.service.ProgressService;
import com.codefit.service.SyllabusService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class DashboardController extends BaseController {
    private static final Pattern JAVA_BE_MODULE_PATTERN = Pattern.compile("^\\s*Java\\s+BE\\s+(\\d{1,2})\\b.*", Pattern.CASE_INSENSITIVE);
    private static final int EARLY_JAVA_BE_MODULE_LIMIT = 3;
    private static final double MOSTLY_REVIEWED_PROGRESS = 0.8;

    @FXML private Label levelLabel;
    @FXML private Label xpLabel;
    @FXML private Label streakLabel;
    @FXML private Label deckCountLabel;
    @FXML private Label cardCountLabel;
    @FXML private Label dueCountLabel;
    @FXML private Label dailyQuestTitleLabel;
    @FXML private Label dailyQuestProgressLabel;
    @FXML private ProgressBar dailyQuestProgressBar;
    @FXML private Label emptyStateLabel;
    @FXML private Label nextActionTitleLabel;
    @FXML private Label nextActionHelperLabel;
    @FXML private Button primaryActionButton;
    @FXML private ProgressBar levelProgressBar;
    @FXML private VBox recentDecksList;

    private final ProgressService progressService = new ProgressService();
    private final DeckService deckService = new DeckService();
    private final DailyQuestService dailyQuestService = new DailyQuestService();
    private final FlashcardService flashcardService = new FlashcardService();
    private final SyllabusService syllabusService = new SyllabusService();

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
        configureDailyQuest();

        populateRecentDecks(decks);

        configureEmptyState(decks, deckCount, cardCount, dueCount);
    }


    private void configureDailyQuest() {
        DailyQuest quest = dailyQuestService.getActiveQuest();
        dailyQuestTitleLabel.setText(formatQuestTitle(quest));
        dailyQuestProgressLabel.setText(quest.getCurrentCount() + " / " + quest.getTargetCount()
                + " • " + quest.getXpReward() + " XP" + (quest.isCompleted() ? " • Complete" : ""));
        dailyQuestProgressBar.setProgress(quest.getTargetCount() == 0
                ? 0
                : Math.min(1.0, quest.getCurrentCount() / (double) quest.getTargetCount()));
    }

    private String formatQuestTitle(DailyQuest quest) {
        if (quest.getObjectiveType() == DailyQuestObjectiveType.REVIEW_DUE_CARDS) {
            return "Daily quest: review due cards";
        }
        if (quest.getObjectiveType() == DailyQuestObjectiveType.PRACTICE_WEAK_SKILL) {
            return "Daily quest: practice " + quest.getSkillCategory();
        }
        return "Daily quest: add a stretch card";
    }

    private void configureEmptyState(List<Deck> decks, int deckCount, int cardCount, int dueCount) {
        if (configureJavaBackendPrimaryAction(decks)) {
            return;
        }
        if (deckCount == 0) {
            setStatus(emptyStateLabel, "Create your first deck to organize what you want to practice. Next action: Create a deck.");
            configurePrimaryAction("Create your first deck", "Start with one focused topic, then add cards when the deck is ready.", "Create Deck", this::goDecks);
        } else if (cardCount == 0) {
            setStatus(emptyStateLabel, "Add your first card so CodeFit can build a review queue. Next action: Add a card.");
            configurePrimaryAction("Add your first card", "Capture one prompt and answer in an existing deck to unlock reviews.", "Add Card", this::goAddCard);
        } else if (dueCount == 0) {
            setStatus(emptyStateLabel, "No due reviews. Your queue is clear for now. Next action: Add a stretch card.");
            configurePrimaryAction("Add a stretch card", "Keep momentum by adding one harder card while scheduled reviews mature.", "Add Card", this::goAddCard);
        } else {
            setStatus(emptyStateLabel, "You have cards ready to review. Next action: Start Review.");
            configurePrimaryAction("Review due cards", "Start with the cards that need attention now, then build or browse your library after the review queue is clear.", "Start Review", this::goReview);
        }
    }

    private boolean configureJavaBackendPrimaryAction(List<Deck> decks) {
        List<JavaBackendDeckProgress> javaBackendDecks = javaBackendDeckProgress(decks);
        if (javaBackendDecks.isEmpty()) {
            return false;
        }

        Optional<JavaBackendDeckProgress> emptyEarlyModule = javaBackendDecks.stream()
                .filter(progress -> progress.moduleNumber() <= EARLY_JAVA_BE_MODULE_LIMIT)
                .filter(progress -> progress.cardCount() == 0)
                .min(Comparator.comparingInt(JavaBackendDeckProgress::moduleNumber));
        if (emptyEarlyModule.isPresent()) {
            JavaBackendDeckProgress progress = emptyEarlyModule.get();
            setStatus(emptyStateLabel, "Java Backend Module " + formatModuleNumber(progress.moduleNumber())
                    + " is ready but has no cards. Next action: add or import starter cards.");
            configurePrimaryAction("Add starter Java BE cards",
                    "Start " + progress.deck().getName() + " by adding one card or importing starter prompts from Decks.",
                    "Open Decks", this::goDecks);
            return true;
        }

        Optional<JavaBackendDeckProgress> weakestDueModule = javaBackendDecks.stream()
                .filter(progress -> progress.dueCount() > 0)
                .min(Comparator.comparingInt(JavaBackendDeckProgress::progressPercent)
                        .thenComparing(JavaBackendDeckProgress::dueCount, Comparator.reverseOrder())
                        .thenComparingInt(JavaBackendDeckProgress::moduleNumber));
        if (weakestDueModule.isPresent()) {
            JavaBackendDeckProgress progress = weakestDueModule.get();
            setStatus(emptyStateLabel, progress.dueCount() + " Java BE cards are due. Next action: review the weakest due module.");
            configurePrimaryAction("Review Module " + formatModuleNumber(progress.moduleNumber()),
                    progress.deck().getName() + " has " + progress.dueCount() + " due and "
                            + progress.progressPercent() + "% reviewed, making it the weakest Java BE module right now.",
                    "Start Review", this::goReview);
            return true;
        }

        Optional<JavaBackendDeckProgress> mostlyReviewedModule = javaBackendDecks.stream()
                .filter(progress -> progress.cardCount() > 0)
                .filter(progress -> progress.reviewProgress() >= MOSTLY_REVIEWED_PROGRESS)
                .filter(progress -> nextJavaBackendModule(progress.moduleNumber(), javaBackendDecks).isPresent())
                .max(Comparator.comparingInt(JavaBackendDeckProgress::moduleNumber));
        if (mostlyReviewedModule.isPresent()) {
            JavaBackendDeckProgress current = mostlyReviewedModule.get();
            JavaBackendDeckProgress next = nextJavaBackendModule(current.moduleNumber(), javaBackendDecks).get();
            setStatus(emptyStateLabel, "Java BE Module " + formatModuleNumber(current.moduleNumber())
                    + " is mostly reviewed. Next action: move to Module " + formatModuleNumber(next.moduleNumber()) + ".");
            configurePrimaryAction("Move to Module " + formatModuleNumber(next.moduleNumber()),
                    "You have reviewed " + current.progressPercent() + "% of " + current.deck().getName()
                            + ". Continue the path with " + next.deck().getName() + ".",
                    next.cardCount() == 0 ? "Add Cards" : "Open Syllabus",
                    next.cardCount() == 0 ? this::goAddCard : this::goSyllabus);
            return true;
        }

        return false;
    }

    private List<JavaBackendDeckProgress> javaBackendDeckProgress(List<Deck> decks) {
        List<SyllabusModule> modules = syllabusService.getJavaBackendModules();
        return decks.stream()
                .map(deck -> javaBackendModuleNumber(deck, modules)
                        .stream()
                        .mapToObj(moduleNumber -> toJavaBackendDeckProgress(deck, moduleNumber))
                        .findFirst()
                        .orElse(null))
                .filter(progress -> progress != null)
                .sorted(Comparator.comparingInt(JavaBackendDeckProgress::moduleNumber))
                .toList();
    }

    private JavaBackendDeckProgress toJavaBackendDeckProgress(Deck deck, int moduleNumber) {
        List<Flashcard> cards = flashcardService.getCardsForDeck(deck.getId());
        long dueCount = countDueCards(cards);
        int progressPercent = calculateProgressPercent(cards);
        return new JavaBackendDeckProgress(deck, moduleNumber, cards.size(), dueCount, progressPercent);
    }

    private Optional<JavaBackendDeckProgress> nextJavaBackendModule(int currentModuleNumber,
                                                                   List<JavaBackendDeckProgress> javaBackendDecks) {
        return javaBackendDecks.stream()
                .filter(progress -> progress.moduleNumber() > currentModuleNumber)
                .min(Comparator.comparingInt(JavaBackendDeckProgress::moduleNumber));
    }

    private OptionalInt javaBackendModuleNumber(Deck deck, List<SyllabusModule> modules) {
        if (deck == null) {
            return OptionalInt.empty();
        }

        OptionalInt syllabusModule = modules.stream()
                .filter(module -> isJavaBackendSyllabusModule(deck, module))
                .mapToInt(SyllabusModule::getModuleNumber)
                .findFirst();
        return syllabusModule.isPresent() ? syllabusModule : parseJavaBackendModule(deck.getName());
    }

    private boolean isJavaBackendSyllabusModule(Deck deck, SyllabusModule module) {
        return module.getDeckId() == deck.getId()
                || module.getDeckName().equalsIgnoreCase(deck.getName());
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

    private String formatModuleNumber(int moduleNumber) {
        return String.format("%02d", moduleNumber);
    }

    private void configurePrimaryAction(String title, String helper, String buttonText, Runnable action) {
        nextActionTitleLabel.setText(title);
        nextActionHelperLabel.setText(helper);
        primaryActionButton.setText(buttonText);
        primaryActionButton.setOnAction(event -> action.run());
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

        Label iconLabel = new Label("D");
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

    private record JavaBackendDeckProgress(Deck deck, int moduleNumber, int cardCount, long dueCount,
                                           int progressPercent) {
        private double reviewProgress() {
            return progressPercent / 100.0;
        }
    }

}

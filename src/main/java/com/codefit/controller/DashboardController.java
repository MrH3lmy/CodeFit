package com.codefit.controller;

import com.codefit.model.DailyQuest;
import com.codefit.model.Deck;
import com.codefit.model.UserProgress;
import com.codefit.service.DailyQuestService;
import com.codefit.service.DeckService;
import com.codefit.service.FlashcardService;
import com.codefit.service.ProgressService;
import com.codefit.service.ReviewService;
import com.codefit.service.SessionBudgetService;
import com.codefit.service.StatsService;
import com.codefit.service.StatsSkillPerformance;
import com.codefit.service.SystemMessage;
import com.codefit.service.SystemMessageService;
import com.codefit.service.TrainingPathService;
import com.codefit.ui.NavigationService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public class DashboardController extends BaseController {
    @FXML private Label streakQuestStatusLabel;
    @FXML private Label dueCountLabel;
    @FXML private Label contextBannerLabel;
    @FXML private Label nextActionTitleLabel;
    @FXML private Label nextActionHelperLabel;
    @FXML private Button primaryActionButton;
    @FXML private MenuButton reviewSessionMenuButton;
    @FXML private Button weeklyBossButton;
    @FXML private VBox weakSkillsList;

    private final DeckService deckService = new DeckService();
    private final DailyQuestService dailyQuestService = new DailyQuestService();
    private final FlashcardService flashcardService = new FlashcardService();
    private final StatsService statsService = new StatsService();
    private final TrainingPathService trainingPathService = new TrainingPathService();
    private final SystemMessageService systemMessageService = new SystemMessageService();
    private final ReviewService reviewService = new ReviewService();
    private final ProgressService progressService = new ProgressService();

    @FXML
    public void initialize() {
        UserProgress progress = progressService.getProgress();
        List<Deck> decks = deckService.getDecks();
        int deckCount = decks.size();
        int cardCount = flashcardService.countAllCards();
        int dueCount = flashcardService.countDueCards();

        int availableNewCards = reviewService.getAvailableNewCardBudget();
        dueCountLabel.setText(dueCount + " " + (dueCount == 1 ? "card" : "cards") + " due"
                + (availableNewCards > 0 ? " · " + availableNewCards + " new available" : ""));
        DailyQuest dailyQuest = configureStreakAndQuest(progress);

        populateWeakSkills();
        configurePrimaryState(decks, deckCount, cardCount, dueCount);
        configureContextMessage(progress, dailyQuest);
        configureWeeklyBossCallout();
    }

    @FXML
    public void startQuickSession() {
        NavigationService.showTimedReview(SessionBudgetService.QUICK_MINUTES);
    }

    @FXML
    public void startStandardSession() {
        NavigationService.showTimedReview(SessionBudgetService.STANDARD_MINUTES);
    }

    @FXML
    public void startDeepSession() {
        NavigationService.showTimedReview(SessionBudgetService.DEEP_MINUTES);
    }

    private void populateWeakSkills() {
        weakSkillsList.getChildren().clear();
        List<StatsSkillPerformance> weakSkills = statsService.getNeedsPracticeSkills();
        if (weakSkills.isEmpty()) {
            Label emptyLabel = new Label("No weak-area signal yet — keep reviewing to unlock this.");
            emptyLabel.getStyleClass().add("dashboard-card-helper");
            emptyLabel.setWrapText(true);
            weakSkillsList.getChildren().add(emptyLabel);
            return;
        }
        weakSkills.stream().limit(1).map(this::createWeakSkillRow).forEach(row -> weakSkillsList.getChildren().add(row));
    }

    private HBox createWeakSkillRow(StatsSkillPerformance skill) {
        HBox row = new HBox(12);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("deck-progress-row");

        Label nameLabel = new Label(skill.skillCategory());
        nameLabel.getStyleClass().add("deck-name");
        nameLabel.setWrapText(true);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Label countLabel = new Label(skill.dueCards() + " " + (skill.dueCards() == 1 ? "card" : "cards"));
        countLabel.getStyleClass().add("deck-due-label");

        row.getChildren().addAll(nameLabel, countLabel);
        return row;
    }

    private void configureContextMessage(UserProgress progress, DailyQuest dailyQuest) {
        if (contextBannerLabel.isVisible()) {
            return;
        }

        String rankTitle = progressService.getRankTitle(progress);
        systemMessageService.highestPriorityDashboardMessage(
                        progress,
                        dailyQuest,
                        List.of(),
                        false,
                        rankTitle)
                .map(SystemMessage::text)
                .ifPresentOrElse(message -> setStatus(contextBannerLabel, message),
                        () -> setStatus(contextBannerLabel, ""));
    }

    private void configureWeeklyBossCallout() {
        boolean available = statsService.isWeeklyBossAvailable();
        weeklyBossButton.setVisible(available);
        weeklyBossButton.setManaged(available);
    }

    @FXML
    public void goWeeklyBossBattle() {
        NavigationService.showWeeklyBossBattle();
    }

    private String formatStreakState(UserProgress progress) {
        if (progress.isRecoveryQuestActive()) {
            return "Recovery quest active";
        }
        LocalDate lastReviewDate = progress.getLastReviewDate();
        if (lastReviewDate != null && lastReviewDate.isBefore(LocalDate.now())) {
            return "Streak at risk";
        }
        return progress.getStreakDays() + " day streak";
    }

    private DailyQuest configureStreakAndQuest(UserProgress progress) {
        DailyQuest quest = dailyQuestService.getActiveQuest();
        streakQuestStatusLabel.setText(formatStreakState(progress) + " · Quest "
                + quest.getCurrentCount() + "/" + quest.getTargetCount() + (quest.isCompleted() ? " ✓" : ""));
        return quest;
    }

    private void configurePrimaryState(List<Deck> decks, int deckCount, int cardCount, int dueCount) {
        setStatus(contextBannerLabel, "");
        if (configureTrainingPathPrimaryAction(decks)) {
            return;
        }
        if (deckCount == 0) {
            setStatus(contextBannerLabel, "Create your first deck to organize what you want to practice.");
            configurePrimaryAction("Create your first deck",
                    "Start with one focused topic, then add cards when the deck is ready.",
                    "Create Deck", this::goDecks, false);
        } else if (cardCount == 0) {
            setStatus(contextBannerLabel, "Add your first card so CodeFit can build a review queue.");
            configurePrimaryAction("Add your first card",
                    "Capture one prompt and answer in an existing deck to unlock reviews.",
                    "Add Card", this::goAddCard, false);
        } else if (dueCount == 0) {
            setStatus(contextBannerLabel, "No due reviews. Your queue is clear for now.");
            configurePrimaryAction("Add a stretch card",
                    "Keep momentum by adding one harder card while scheduled reviews mature.",
                    "Add Card", this::goAddCard, false);
        } else {
            configurePrimaryAction("Review due cards",
                    "Start with the cards that need attention now.",
                    "Start Review", this::goReview, true);
        }
    }

    private boolean configureTrainingPathPrimaryAction(List<Deck> decks) {
        Optional<TrainingPathService.TrainingPathRecommendation> recommendation = trainingPathService.recommendNextModule(decks);
        if (recommendation.isEmpty()) {
            return false;
        }

        TrainingPathService.TrainingPathRecommendation nextAction = recommendation.get();
        TrainingPathService.TrainingPathModuleProgress current = nextAction.current();
        String pathName = nextAction.path().getName();
        String moduleNumber = formatModuleNumber(current.module().getOrder());

        if (nextAction.action() == TrainingPathService.TrainingPathAction.ADD_STARTER_CARDS) {
            setStatus(contextBannerLabel, pathName + " Module " + moduleNumber + " is ready but has no cards.");
            configurePrimaryAction("Add starter " + pathName + " cards",
                    "Start " + current.deck().getName() + " by adding one card or importing starter prompts from Decks.",
                    "Open Decks", this::goDecks, false);
            return true;
        }

        if (nextAction.action() == TrainingPathService.TrainingPathAction.REVIEW_DUE_MODULE) {
            setStatus(contextBannerLabel, current.dueCount() + " " + pathName + " cards are due in the weakest module.");
            configurePrimaryAction("Review Module " + moduleNumber,
                    current.deck().getName() + " has " + current.dueCount() + " due and "
                            + current.progressPercent() + "% reviewed.",
                    "Start Review", this::goReview, true);
            return true;
        }

        if (nextAction.action() == TrainingPathService.TrainingPathAction.MOVE_TO_NEXT_MODULE
                && nextAction.next() != null) {
            TrainingPathService.TrainingPathModuleProgress next = nextAction.next();
            String nextModuleNumber = formatModuleNumber(next.module().getOrder());
            setStatus(contextBannerLabel, pathName + " Module " + moduleNumber
                    + " is mostly reviewed. Continue with Module " + nextModuleNumber + ".");
            configurePrimaryAction("Move to Module " + nextModuleNumber,
                    "You have reviewed " + current.progressPercent() + "% of " + current.deck().getName()
                            + ". Continue the path with " + next.deck().getName() + ".",
                    next.cardCount() == 0 ? "Add Cards" : "Open Syllabus",
                    next.cardCount() == 0 ? this::goAddCard : this::goSyllabus,
                    false);
            return true;
        }

        return false;
    }

    private String formatModuleNumber(int moduleNumber) {
        return String.format("%02d", moduleNumber);
    }

    private void configurePrimaryAction(String title, String helper, String buttonText, Runnable action,
                                        boolean showReviewSessionOptions) {
        nextActionTitleLabel.setText(title);
        nextActionHelperLabel.setText(helper);
        primaryActionButton.setText(buttonText);
        primaryActionButton.setOnAction(event -> action.run());
        reviewSessionMenuButton.setVisible(showReviewSessionOptions);
        reviewSessionMenuButton.setManaged(showReviewSessionOptions);
    }
}

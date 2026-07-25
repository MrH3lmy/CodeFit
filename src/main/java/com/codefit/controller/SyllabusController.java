package com.codefit.controller;

import com.codefit.model.SyllabusModule;
import com.codefit.model.TrainingPath;
import com.codefit.model.UserProgress;
import com.codefit.service.FocusPreferenceService;
import com.codefit.service.SyllabusService;
import com.codefit.service.TrainingPathService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

public class SyllabusController extends BaseController {
    @FXML private Label moduleCountLabel;
    @FXML private Label cardCountLabel;
    @FXML private Label progressLabel;
    @FXML private ListView<SyllabusModule> syllabusListView;
    @FXML private ChoiceBox<String> activePathChoiceBox;
    @FXML private ChoiceBox<Integer> focusModuleChoiceBox;
    @FXML private Label focusStatusLabel;
    @FXML private Label focusRecommendationLabel;

    private final SyllabusService syllabusService = new SyllabusService();
    private final TrainingPathService trainingPathService = new TrainingPathService();
    private final FocusPreferenceService focusPreferenceService = new FocusPreferenceService();

    /** Backs the "FOCUS" indicator on module rows; kept as a simple key so the ListCell doesn't need controller access. */
    private String focusedModuleKey;

    @FXML
    public void initialize() {
        syllabusListView.setCellFactory(listView -> new SyllabusModuleCell(this::isFocusedModule));
        var modules = FXCollections.observableArrayList(syllabusService.getAllTrainingPathModules());
        syllabusListView.setItems(modules);
        syllabusListView.setPlaceholder(new Label("No syllabus modules are available yet."));

        int totalCards = modules.stream().mapToInt(SyllabusModule::getEstimatedCardCount).sum();
        int masteredCards = modules.stream().mapToInt(SyllabusModule::getMasteredCardCount).sum();
        moduleCountLabel.setText(String.valueOf(modules.size()));
        cardCountLabel.setText(String.valueOf(totalCards));
        progressLabel.setText(totalCards == 0 ? "0%" : Math.round(masteredCards * 100.0 / totalCards) + "%");

        configureFocusControls();
    }

    private boolean isFocusedModule(SyllabusModule module) {
        return focusedModuleKey != null && focusedModuleKey.equals(moduleKey(module.getPathName(), module.getModuleNumber()));
    }

    private String moduleKey(String pathName, int moduleNumber) {
        return pathName + "::" + moduleNumber;
    }

    private void configureFocusControls() {
        List<TrainingPath> paths = trainingPathService.getTrainingPaths();
        activePathChoiceBox.setItems(FXCollections.observableArrayList(paths.stream().map(TrainingPath::getName).toList()));

        UserProgress progress = focusPreferenceService.getPreference();
        String initialPath = progress.hasFocusModule() ? progress.getActiveTrainingPath() : paths.get(0).getName();
        int initialModuleOrder = progress.hasFocusModule() ? progress.getFocusModuleOrder() : 1;
        activePathChoiceBox.setValue(initialPath);
        refreshFocusModuleChoices(initialPath, initialModuleOrder);

        activePathChoiceBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(oldValue)) {
                refreshFocusModuleChoices(newValue, 1);
            }
        });

        focusedModuleKey = progress.hasFocusModule() ? moduleKey(progress.getActiveTrainingPath(), progress.getFocusModuleOrder()) : null;
        syllabusListView.refresh();
        updateFocusStatus(progress);
        updateFocusRecommendation();
    }

    private void refreshFocusModuleChoices(String pathName, int selectedOrder) {
        TrainingPath path = trainingPathService.getTrainingPaths().stream()
                .filter(candidate -> candidate.getName().equals(pathName))
                .findFirst()
                .orElse(null);
        if (path == null) {
            focusModuleChoiceBox.setItems(FXCollections.observableArrayList());
            return;
        }
        List<Integer> moduleOrders = path.getModules().stream().map(TrainingPath.TrainingPathModule::getOrder).toList();
        focusModuleChoiceBox.setItems(FXCollections.observableArrayList(moduleOrders));
        focusModuleChoiceBox.setValue(moduleOrders.contains(selectedOrder) ? selectedOrder : moduleOrders.get(0));
    }

    /** Persists the chosen active path + focus module. Only updates that preference pointer — never touches schedules or review history (#110). */
    @FXML
    public void setFocus() {
        String pathName = activePathChoiceBox.getValue();
        Integer moduleOrder = focusModuleChoiceBox.getValue();
        if (pathName == null || moduleOrder == null) {
            return;
        }
        focusPreferenceService.setFocus(pathName, moduleOrder);
        focusedModuleKey = moduleKey(pathName, moduleOrder);
        syllabusListView.refresh();
        updateFocusStatus(focusPreferenceService.getPreference());
        updateFocusRecommendation();
    }

    private void updateFocusStatus(UserProgress progress) {
        if (!progress.hasFocusModule()) {
            focusStatusLabel.setText("No focus module set yet — new cards are mixed evenly across every module.");
            return;
        }
        focusStatusLabel.setText("Focused on " + progress.getActiveTrainingPath() + " Module "
                + String.format("%02d", progress.getFocusModuleOrder())
                + ". New/stretch cards are drawn mainly from this module; due cards from every other module still always take priority, and "
                + progress.getMatureInterleavePercent() + "% of leftover session time interleaves mature cards from other modules.");
    }

    private void updateFocusRecommendation() {
        focusPreferenceService.recommendFocusChange()
                .filter(recommendation -> recommendation.next() != null)
                .ifPresentOrElse(recommendation -> setStatus(focusRecommendationLabel,
                                recommendation.current().module().getTitle() + " has reached its mastery threshold — consider moving focus to Module "
                                        + String.format("%02d", recommendation.next().module().getOrder()) + ": "
                                        + recommendation.next().module().getTitle() + "."),
                        () -> setStatus(focusRecommendationLabel, ""));
    }

    private static final class SyllabusModuleCell extends ListCell<SyllabusModule> {
        private final Label numberLabel = new Label();
        private final Label focusPillLabel = new Label("FOCUS");
        private final Label titleLabel = new Label();
        private final Label objectiveLabel = new Label();
        private final Label deckLabel = new Label();
        private final Label cardCountLabel = new Label();
        private final Label statusLabel = new Label();
        private final ProgressBar progressBar = new ProgressBar(0);
        private final VBox content = new VBox(8);
        private final java.util.function.Predicate<SyllabusModule> isFocused;

        private SyllabusModuleCell(java.util.function.Predicate<SyllabusModule> isFocused) {
            this.isFocused = isFocused;
            numberLabel.getStyleClass().add("pill");
            focusPillLabel.getStyleClass().add("pill");
            titleLabel.getStyleClass().add("deck-row-title");
            titleLabel.setWrapText(true);
            objectiveLabel.getStyleClass().add("deck-row-description");
            objectiveLabel.setWrapText(true);
            deckLabel.getStyleClass().add("deck-row-stats");
            deckLabel.setWrapText(true);
            cardCountLabel.getStyleClass().add("deck-row-stats");
            statusLabel.getStyleClass().add("deck-row-stats");
            progressBar.setMaxWidth(Double.MAX_VALUE);

            HBox header = new HBox(10, numberLabel, focusPillLabel, titleLabel);
            header.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(titleLabel, Priority.ALWAYS);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox metadata = new HBox(12, deckLabel, spacer, cardCountLabel, statusLabel);
            metadata.setAlignment(Pos.CENTER_LEFT);

            content.getChildren().addAll(header, objectiveLabel, metadata, progressBar);
            content.getStyleClass().add("deck-row");
            content.setMaxWidth(Double.MAX_VALUE);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(SyllabusModule module, boolean empty) {
            super.updateItem(module, empty);
            if (empty || module == null) {
                setGraphic(null);
                return;
            }

            numberLabel.setText(module.getPathName() + " · Module " + module.getModuleNumber());
            boolean focused = isFocused.test(module);
            focusPillLabel.setVisible(focused);
            focusPillLabel.setManaged(focused);
            titleLabel.setText(module.getTitle());
            objectiveLabel.setText(module.getLearningObjective());
            deckLabel.setText("Deck: " + module.getDeckName() + deckIdText(module));
            cardCountLabel.setText(module.getEstimatedCardCount() + " cards");
            statusLabel.setText(module.getReviewStatus());
            progressBar.setProgress(module.getProgress());
            setGraphic(content);
        }

        private String deckIdText(SyllabusModule module) {
            return module.getDeckId() > 0 ? " (#" + module.getDeckId() + ")" : " (missing)";
        }
    }
}

package com.codefit.controller;

import com.codefit.model.SyllabusModule;
import com.codefit.service.SyllabusService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class SyllabusController extends BaseController {
    @FXML private Label moduleCountLabel;
    @FXML private Label cardCountLabel;
    @FXML private Label progressLabel;
    @FXML private ListView<SyllabusModule> syllabusListView;

    private final SyllabusService syllabusService = new SyllabusService();

    @FXML
    public void initialize() {
        syllabusListView.setCellFactory(listView -> new SyllabusModuleCell());
        var modules = FXCollections.observableArrayList(syllabusService.getAllTrainingPathModules());
        syllabusListView.setItems(modules);
        syllabusListView.setPlaceholder(new Label("No syllabus modules are available yet."));

        int totalCards = modules.stream().mapToInt(SyllabusModule::getEstimatedCardCount).sum();
        int masteredCards = modules.stream().mapToInt(SyllabusModule::getMasteredCardCount).sum();
        moduleCountLabel.setText(String.valueOf(modules.size()));
        cardCountLabel.setText(String.valueOf(totalCards));
        progressLabel.setText(totalCards == 0 ? "0%" : Math.round(masteredCards * 100.0 / totalCards) + "%");
    }

    private static final class SyllabusModuleCell extends ListCell<SyllabusModule> {
        private final Label numberLabel = new Label();
        private final Label titleLabel = new Label();
        private final Label objectiveLabel = new Label();
        private final Label deckLabel = new Label();
        private final Label cardCountLabel = new Label();
        private final Label statusLabel = new Label();
        private final ProgressBar progressBar = new ProgressBar(0);
        private final VBox content = new VBox(8);

        private SyllabusModuleCell() {
            numberLabel.getStyleClass().add("pill");
            titleLabel.getStyleClass().add("deck-row-title");
            titleLabel.setWrapText(true);
            objectiveLabel.getStyleClass().add("deck-row-description");
            objectiveLabel.setWrapText(true);
            deckLabel.getStyleClass().add("deck-row-stats");
            deckLabel.setWrapText(true);
            cardCountLabel.getStyleClass().add("deck-row-stats");
            statusLabel.getStyleClass().add("deck-row-stats");
            progressBar.setMaxWidth(Double.MAX_VALUE);

            HBox header = new HBox(10, numberLabel, titleLabel);
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

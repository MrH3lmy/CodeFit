package com.codefit.service;

import com.codefit.model.UserProgress;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.UserProgressRepository;

import java.util.Optional;
import java.util.Set;

/**
 * Owns the learner's chosen active training path and focus module (#110). This is purely a
 * preference pointer stored alongside the other per-user settings on {@code user_progress}:
 * switching focus only ever updates that row's active_training_path/focus_module_order/
 * mature_interleave_percent columns, never flashcards or review_history, so schedules and review
 * history are unaffected by a focus change.
 */
public class FocusPreferenceService {
    public static final int MIN_MATURE_INTERLEAVE_PERCENT = 0;
    public static final int MAX_MATURE_INTERLEAVE_PERCENT = 50;

    private final UserProgressRepository userProgressRepository;
    private final DeckRepository deckRepository;
    private final TrainingPathService trainingPathService;

    public FocusPreferenceService() {
        this(new UserProgressRepository(), new DeckRepository(), new TrainingPathService());
    }

    public FocusPreferenceService(UserProgressRepository userProgressRepository, DeckRepository deckRepository,
                                  TrainingPathService trainingPathService) {
        this.userProgressRepository = userProgressRepository;
        this.deckRepository = deckRepository;
        this.trainingPathService = trainingPathService;
    }

    public UserProgress getPreference() {
        return userProgressRepository.getProgress();
    }

    /** Sets the active path + focus module. Deliberately touches only the preference row - see class Javadoc. */
    public void setFocus(String pathName, int moduleOrder) {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setActiveTrainingPath(pathName);
        progress.setFocusModuleOrder(moduleOrder);
        userProgressRepository.save(progress);
    }

    public void clearFocus() {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setActiveTrainingPath(null);
        progress.setFocusModuleOrder(0);
        userProgressRepository.save(progress);
    }

    public void setMatureInterleavePercent(int percent) {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setMatureInterleavePercent(clampInterleavePercent(percent));
        userProgressRepository.save(progress);
    }

    static int clampInterleavePercent(int percent) {
        return Math.max(MIN_MATURE_INTERLEAVE_PERCENT, Math.min(MAX_MATURE_INTERLEAVE_PERCENT, percent));
    }

    /** Deck ids backing the current focus module, or empty when no focus module is set/resolvable. */
    public Set<Long> getFocusDeckIds() {
        return getFocusDeckIds(getPreference());
    }

    Set<Long> getFocusDeckIds(UserProgress progress) {
        if (!progress.hasFocusModule()) {
            return Set.of();
        }
        return trainingPathService.resolveModuleDeckIds(progress.getActiveTrainingPath(), progress.getFocusModuleOrder(),
                deckRepository.findAll());
    }

    /** Suggests moving focus to the next module only once the current focus module clears its own mastery threshold (#110). */
    public Optional<TrainingPathService.TrainingPathRecommendation> recommendFocusChange() {
        UserProgress progress = getPreference();
        if (!progress.hasFocusModule()) {
            return Optional.empty();
        }
        return trainingPathService.recommendFocusChange(progress.getActiveTrainingPath(), progress.getFocusModuleOrder(),
                deckRepository.findAll());
    }
}

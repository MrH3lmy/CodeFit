package com.codefit.model;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TrainingPath {
    private final String name;
    private final List<TrainingPathModule> modules;
    private final Pattern deckNamePattern;
    private final int starterCardModuleLimit;
    private final double moduleCompletionThreshold;

    public TrainingPath(String name, List<TrainingPathModule> modules, Pattern deckNamePattern,
                        int starterCardModuleLimit, double moduleCompletionThreshold) {
        this.name = name;
        this.modules = modules.stream()
                .sorted(Comparator.comparingInt(TrainingPathModule::getOrder))
                .toList();
        this.deckNamePattern = deckNamePattern;
        this.starterCardModuleLimit = starterCardModuleLimit;
        this.moduleCompletionThreshold = moduleCompletionThreshold;
    }

    public String getName() { return name; }
    public List<TrainingPathModule> getModules() { return modules; }
    public Pattern getDeckNamePattern() { return deckNamePattern; }
    public int getStarterCardModuleLimit() { return starterCardModuleLimit; }
    public double getModuleCompletionThreshold() { return moduleCompletionThreshold; }

    public Optional<TrainingPathModule> findModuleForDeck(Deck deck) {
        if (deck == null) {
            return Optional.empty();
        }

        Optional<TrainingPathModule> mappedModule = modules.stream()
                .filter(module -> module.matchesDeck(deck))
                .findFirst();
        return mappedModule.isPresent() ? mappedModule : parseModuleOrder(deck.getName()).flatMap(this::findModuleByOrder);
    }

    public Optional<TrainingPathModule> findModuleByOrder(int order) {
        return modules.stream()
                .filter(module -> module.getOrder() == order)
                .findFirst();
    }

    private Optional<Integer> parseModuleOrder(String deckName) {
        if (deckName == null || deckNamePattern == null) {
            return Optional.empty();
        }

        Matcher matcher = deckNamePattern.matcher(deckName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(matcher.group(1)));
    }

    public static class TrainingPathModule {
        private final int order;
        private final String title;
        private final String learningObjective;
        private final String deckName;

        public TrainingPathModule(int order, String title, String learningObjective, String deckName) {
            this.order = order;
            this.title = title;
            this.learningObjective = learningObjective;
            this.deckName = deckName;
        }

        public int getOrder() { return order; }
        public String getTitle() { return title; }
        public String getLearningObjective() { return learningObjective; }
        public String getDeckName() { return deckName; }

        public boolean matchesDeck(Deck deck) {
            return deck != null && deckName.equalsIgnoreCase(deck.getName());
        }
    }
}

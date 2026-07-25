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

    /**
     * One module in a training path. A module is normally backed by a single flashcard deck, but
     * {@code deckNames} may list more than one deck (e.g. a module whose curriculum was authored as
     * several smaller decks) so mastery and due-card counts aggregate across all of them.
     *
     * <p>{@code prerequisiteModuleOrders} documents which earlier modules should be reasonably solid
     * before starting this one, and {@code masteryThreshold} is the durable-mastery fraction (0-1,
     * from {@link com.codefit.service.MasteryService}) this module must reach before the
     * recommendation engine considers it complete enough to move on.
     */
    public static class TrainingPathModule {
        private final int order;
        private final String title;
        private final String learningObjective;
        private final List<String> deckNames;
        private final List<Integer> prerequisiteModuleOrders;
        private final double masteryThreshold;

        public TrainingPathModule(int order, String title, String learningObjective, List<String> deckNames,
                                  List<Integer> prerequisiteModuleOrders, double masteryThreshold) {
            this.order = order;
            this.title = title;
            this.learningObjective = learningObjective;
            this.deckNames = List.copyOf(deckNames);
            this.prerequisiteModuleOrders = List.copyOf(prerequisiteModuleOrders);
            this.masteryThreshold = masteryThreshold;
        }

        public TrainingPathModule(int order, String title, String learningObjective, String deckName,
                                  List<Integer> prerequisiteModuleOrders, double masteryThreshold) {
            this(order, title, learningObjective, List.of(deckName), prerequisiteModuleOrders, masteryThreshold);
        }

        public int getOrder() { return order; }
        public String getTitle() { return title; }
        public String getLearningObjective() { return learningObjective; }

        /** The primary/first deck backing this module. Use {@link #getDeckNames()} for the full set. */
        public String getDeckName() { return deckNames.get(0); }
        public List<String> getDeckNames() { return deckNames; }
        public List<Integer> getPrerequisiteModuleOrders() { return prerequisiteModuleOrders; }
        public boolean hasPrerequisites() { return !prerequisiteModuleOrders.isEmpty(); }
        public double getMasteryThreshold() { return masteryThreshold; }

        public boolean matchesDeck(Deck deck) {
            return deck != null && deckNames.stream().anyMatch(deckName -> deckName.equalsIgnoreCase(deck.getName()));
        }
    }
}

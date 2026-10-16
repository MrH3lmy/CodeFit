package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.*;

import java.util.*;

/** Training objective, distinct from the legacy DailyQuest engagement feature. */
public record EvolutionQuest(
        CatalogRef ref,
        String domainId,
        GraphRef graph,
        Cohort cohort,
        String title,
        String objective,
        String narrative,
        List<BehaviorTarget> targets,
        List<SkillRef> prerequisites,
        int difficulty,
        int effortMinutes,
        List<CatalogRef> challenges,
        Completion completion,
        String futureMilestone) {
    public record Completion(int independentFamilies, boolean unassistedRequired, String rule) {
        public Completion {
            if (independentFamilies < 1 || independentFamilies > 16)
                throw new IllegalArgumentException("Bounded family requirement needed");
            CatalogValues.text(rule);
        }
    }

    public EvolutionQuest {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(graph);
        Objects.requireNonNull(cohort);
        Objects.requireNonNull(completion);
        CatalogRef.id(domainId);
        CatalogValues.text(title);
        CatalogValues.text(objective);
        CatalogValues.text(narrative);
        targets = CatalogValues.distinct(targets, 16);
        challenges = CatalogValues.distinct(challenges, 64);
        prerequisites = List.copyOf(prerequisites).stream().sorted().toList();
        if (new HashSet<>(prerequisites).size() != prerequisites.size()
                || difficulty < 1
                || difficulty > 5
                || effortMinutes < 1
                || effortMinutes > 180) throw new IllegalArgumentException("Invalid quest bounds");
        if (futureMilestone != null) CatalogRef.id(futureMilestone);
    }
}

package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.*;

import java.util.*;

/** Domain-neutral versioned engineering task; content lives in immutable bundled assets. */
public record ChallengeSpec(
        CatalogRef ref,
        String domainId,
        GraphRef graph,
        String familyId,
        String variantId,
        Cohort cohort,
        List<BehaviorTarget> targets,
        List<SkillRef> prerequisites,
        int difficulty,
        int effortMinutes,
        String objective,
        Runner runner,
        List<AssetRef> assets,
        List<Hint> hints,
        RetryPolicy retry,
        Attribution attribution,
        ChallengeRubric rubric) {
    public record Hint(int level, String assetId) {
        public Hint {
            if (level < 1 || level > 3) throw new IllegalArgumentException("Hint levels are 1..3");
            CatalogRef.id(assetId);
        }
    }

    public record RetryPolicy(boolean allowed, int maximumPresentations, String description) {
        public RetryPolicy {
            if (maximumPresentations < 0 || maximumPresentations > 1000)
                throw new IllegalArgumentException("Invalid retry bound");
            CatalogValues.text(description);
        }
    }

    public record Attribution(String author, String license, String source) {
        public Attribution {
            CatalogValues.text(author);
            CatalogValues.text(license);
            CatalogValues.text(source);
        }
    }

    public ChallengeSpec {
        Objects.requireNonNull(ref);
        Objects.requireNonNull(graph);
        Objects.requireNonNull(cohort);
        Objects.requireNonNull(runner);
        Objects.requireNonNull(retry);
        Objects.requireNonNull(attribution);
        Objects.requireNonNull(rubric);
        CatalogRef.id(domainId);
        CatalogRef.id(familyId);
        CatalogRef.id(variantId);
        CatalogValues.text(objective);
        targets = CatalogValues.distinct(targets, 16);
        prerequisites = List.copyOf(prerequisites).stream().sorted().toList();
        if (new HashSet<>(prerequisites).size() != prerequisites.size())
            throw new IllegalArgumentException("Duplicate prerequisites");
        if (difficulty < 1 || difficulty > 5 || effortMinutes < 1 || effortMinutes > 180)
            throw new IllegalArgumentException("Difficulty 1..5 and effort 1..180 required");
        assets = List.copyOf(assets).stream().sorted(Comparator.comparing(AssetRef::id)).toList();
        if (assets.isEmpty()
                || assets.size() > 64
                || assets.stream().map(AssetRef::id).distinct().count() != assets.size())
            throw new IllegalArgumentException("Unique bounded assets required");
        for (var kind :
                List.of(AssetKind.PROMPT, AssetKind.STARTER, AssetKind.REFERENCE, AssetKind.MUTANT))
            if (assets.stream().noneMatch(a -> a.kind() == kind))
                throw new IllegalArgumentException("Missing asset role: " + kind);
        for (var kind : List.of(AssetKind.PROMPT, AssetKind.STARTER, AssetKind.REFERENCE))
            if (assets.stream().filter(a -> a.kind() == kind).count() != 1)
                throw new IllegalArgumentException("Exactly one primary asset per role required");
        if (runner == Runner.JAVA21_MAIN
                && assets.stream().filter(a -> a.kind() == AssetKind.HARNESS).count() != 1)
            throw new IllegalArgumentException("Exactly one trusted Java harness required");
        hints = List.copyOf(hints).stream().sorted(Comparator.comparingInt(Hint::level)).toList();
        if (hints.size() != 3
                || hints.get(0).level() != 1
                || hints.get(1).level() != 2
                || hints.get(2).level() != 3)
            throw new IllegalArgumentException(
                    "Fair clarification/approach/explanation ladder required");
        for (var hint : hints)
            if (assets.stream()
                    .noneMatch(a -> a.id().equals(hint.assetId()) && a.kind() == AssetKind.HINT))
                throw new IllegalArgumentException("Missing hint asset");
        for (var check : rubric.checks()) {
            if (!targets.containsAll(check.targets()))
                throw new IllegalArgumentException("Check targets outside challenge");
            for (String id : check.assets())
                if (assets.stream().noneMatch(a -> a.id().equals(id)))
                    throw new IllegalArgumentException("Missing check asset");
        }
        for (var target : targets)
            if (rubric.checks().stream()
                    .noneMatch(
                            c ->
                                    c.required()
                                            && c.targets().contains(target)
                                            && c.kind() != CheckKind.REFLECTION))
                throw new IllegalArgumentException("Target lacks required observable check");
    }

    public AssetRef asset(String id) {
        return assets.stream()
                .filter(a -> a.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown asset"));
    }
}

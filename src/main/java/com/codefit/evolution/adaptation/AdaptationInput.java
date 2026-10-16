package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.evidence.EvidenceTypes;
import com.codefit.evolution.mastery.MasteryTypes.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.Cohort;

import java.time.*;
import java.util.*;

/** Frozen metadata only: no prompts, answers, hidden cases or learner artifacts. */
public record AdaptationInput(
        GraphRef graph,
        boolean graphAvailable,
        String limitation,
        Preferences preferences,
        Instant asOf,
        String policy,
        String configuration,
        List<SkillState> skills,
        List<Candidate> candidates,
        Long curriculumProblem) {
    public record Preferences(
            long version,
            Track track,
            SkillRef goal,
            int budgetMinutes,
            boolean paused,
            Set<String> declined,
            Map<String, Instant> deferred) {
        public Preferences {
            if (version < 0 || budgetMinutes < 1 || budgetMinutes > 180)
                throw new IllegalArgumentException("Invalid preference bounds");
            Objects.requireNonNull(track);
            declined = Collections.unmodifiableSet(new TreeSet<>(declined));
            deferred = Collections.unmodifiableMap(new TreeMap<>(deferred));
        }

        public static Preferences initial() {
            return new Preferences(0, Track.EVOLUTION, null, 20, false, Set.of(), Map.of());
        }
    }

    public record Observation(
            String evidenceId,
            String submissionId,
            String family,
            CatalogRef challenge,
            Instant at,
            EvidenceTypes.Outcome outcome,
            EvidenceTypes.Assistance assistance,
            EvidenceTypes.Exposure exposure,
            boolean independent,
            boolean deliberateFailure,
            Set<Integer> passed,
            Set<Integer> failed,
            List<String> checks,
            int difficulty,
            Cohort cohort) {
        public Observation {
            passed = Set.copyOf(passed);
            failed = Set.copyOf(failed);
            checks = checks.stream().distinct().sorted().toList();
        }
    }

    public record Activity(String id, Instant at, Set<Integer> behaviors) {
        public Activity {
            behaviors = Set.copyOf(behaviors);
        }
    }

    public record SkillState(
            SkillRef skill,
            long revision,
            String inputSha,
            String estimatePolicy,
            String epoch,
            Band band,
            Support support,
            Freshness freshness,
            boolean current,
            boolean contradicted,
            int independentFamilies,
            Set<Integer> requiredBehaviors,
            Set<Integer> supportedBehaviors,
            List<SkillRef> prerequisites,
            List<Observation> observations,
            List<Activity> activities,
            List<String> exclusions) {
        public SkillState {
            requiredBehaviors = Set.copyOf(requiredBehaviors);
            supportedBehaviors = Set.copyOf(supportedBehaviors);
            prerequisites = prerequisites.stream().distinct().sorted().toList();
            observations =
                    observations.stream()
                            .distinct()
                            .sorted(
                                    Comparator.comparing(Observation::at)
                                            .thenComparing(Observation::evidenceId))
                            .toList();
            activities =
                    activities.stream()
                            .distinct()
                            .sorted(
                                    Comparator.comparing(
                                                    Activity::at,
                                                    Comparator.nullsLast(Comparator.naturalOrder()))
                                            .thenComparing(Activity::id))
                            .toList();
            if (observations.stream().map(Observation::submissionId).distinct().count()
                    != observations.size())
                throw new IllegalArgumentException("Conflicting correlated submission metadata");
            exclusions = exclusions.stream().distinct().sorted().toList();
        }
    }

    public record Candidate(
            CatalogRef quest,
            CatalogRef challenge,
            GraphRef graph,
            String family,
            String variant,
            Cohort cohort,
            List<BehaviorTarget> targets,
            List<SkillRef> prerequisites,
            int difficulty,
            int effortMinutes,
            CatalogRef rubric,
            String manifestSha,
            String rubricSha,
            String evaluator,
            String runner,
            boolean available,
            String limitation,
            boolean equivalent,
            int familyPresentations,
            int variantPresentations,
            int priorSubmissions,
            int highestHint,
            boolean solutionRevealed,
            boolean familiar,
            boolean baselineSkipped,
            boolean retryAllowed,
            int maximumPresentations) {
        public Candidate {
            targets = targets.stream().sorted().toList();
            prerequisites = prerequisites.stream().sorted().toList();
        }

        public String key() {
            return quest.tag() + ":" + challenge.tag();
        }

        public boolean fresh() {
            return familyPresentations == 0
                    && priorSubmissions == 0
                    && highestHint == 0
                    && !solutionRevealed
                    && !familiar;
        }

        public boolean targets(SkillRef skill, Set<Integer> behaviors) {
            return targets.stream()
                    .filter(t -> t.skill().equals(skill))
                    .map(BehaviorTarget::behavior)
                    .collect(java.util.stream.Collectors.toSet())
                    .containsAll(behaviors);
        }
    }

    public AdaptationInput {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(preferences);
        Objects.requireNonNull(asOf);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(configuration);
        Objects.requireNonNull(limitation);
        skills = skills.stream().sorted(Comparator.comparing(SkillState::skill)).toList();
        candidates = candidates.stream().sorted(Comparator.comparing(Candidate::key)).toList();
        if (skills.stream().map(SkillState::skill).distinct().count() != skills.size()
                || candidates.stream().map(Candidate::key).distinct().count() != candidates.size()
                || candidates.stream().map(Candidate::challenge).distinct().count()
                        != candidates.size())
            throw new IllegalArgumentException("Duplicate adaptation identity");
        if (skills.size() > 128 || candidates.size() > 256)
            throw new IllegalArgumentException("Adaptation snapshot exceeds V1 bounds");
    }
}

package com.codefit.evolution.mastery;

import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.DurableEvaluationRepository.CheckSummary;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.quest.*;

import java.time.Instant;
import java.util.*;

/** Frozen, metadata-only policy input. No answer, artifact, harness or private diagnostics. */
public record EstimateInput(
        GraphRef graph,
        SkillDefinition skill,
        Epoch epoch,
        Instant asOf,
        List<EvidenceMappingRef> mappings,
        List<Observation> observations,
        List<Activity> activities,
        List<String> unavailableMappings) {
    public record Epoch(String id, Instant startedAt, long evidenceThrough) {
        public Epoch(String id, Instant startedAt) {
            this(id, startedAt, 0);
        }

        public Epoch {
            ChallengePresentation.identity(id);
            Objects.requireNonNull(startedAt);
            if (evidenceThrough < 0)
                throw new IllegalArgumentException("Invalid epoch source boundary");
        }
    }

    public record Context(
            CatalogRef quest,
            CatalogRef challenge,
            String family,
            String variant,
            QuestTypes.Cohort cohort,
            boolean novel,
            boolean heldOutEquivalent,
            String presentationId,
            Instant presentedAt,
            List<CheckSummary> checks) {
        public Context {
            checks = List.copyOf(checks);
        }
    }

    public record Observation(
            String id,
            long sequence,
            EvidenceMappingRef mapping,
            EvidenceObservation facts,
            List<String> exclusions,
            Context context,
            long firstSequence) {
        public Observation(
                String id,
                long sequence,
                EvidenceMappingRef mapping,
                EvidenceObservation facts,
                List<String> exclusions,
                Context context) {
            this(id, sequence, mapping, facts, exclusions, context, sequence);
        }

        public Observation {
            exclusions = exclusions.stream().distinct().sorted().toList();
        }
    }

    /**
     * Presentations, instruction/reveals and deliberate attempts all interrupt retention quiet
     * time.
     */
    public record Activity(String id, Instant at, Set<Integer> behaviors) {
        public Activity {
            behaviors = Set.copyOf(behaviors);
        }
    }

    public EstimateInput {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(skill);
        Objects.requireNonNull(epoch);
        Objects.requireNonNull(asOf);
        mappings =
                mappings.stream()
                        .distinct()
                        .sorted(
                                Comparator.comparing(EvidenceMappingRef::mappingId)
                                        .thenComparingInt(EvidenceMappingRef::version))
                        .toList();
        observations =
                observations.stream()
                        .sorted(
                                Comparator.comparingLong(Observation::sequence)
                                        .thenComparing(Observation::id)
                                        .thenComparing(o -> o.mapping().mappingId())
                                        .thenComparingInt(o -> o.mapping().version()))
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
        unavailableMappings = unavailableMappings.stream().distinct().sorted().toList();
    }
}

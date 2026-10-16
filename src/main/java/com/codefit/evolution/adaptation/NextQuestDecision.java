package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.quest.*;

import java.time.Instant;
import java.util.*;

/** UI-safe interpretation of exact frozen inputs. Null action selection is explicit. */
public record NextQuestDecision(
        Branch branch,
        Selection primary,
        List<Selection> alternatives,
        List<Ranking> candidates,
        List<Explanation> reasons,
        List<String> permittedControls) {
    public record Selection(
            Action action,
            CatalogRef quest,
            CatalogRef challenge,
            SkillRef skill,
            Set<Integer> behaviors,
            int difficulty,
            int effortMinutes,
            Instant dueAt,
            Long curriculumProblem,
            boolean exploratory) {
        public Selection {
            behaviors = Set.copyOf(behaviors);
        }
    }

    public record Explanation(
            Reason code, String message, List<String> evidenceIds, List<Long> estimateRevisions) {
        public Explanation {
            evidenceIds = List.copyOf(evidenceIds);
            estimateRevisions = List.copyOf(estimateRevisions);
        }
    }

    public record Ranking(
            String candidateKey,
            boolean eligible,
            boolean exploratoryEligible,
            int rank,
            List<Reason> exclusions,
            Instant dueAt) {
        public Ranking {
            exclusions = List.copyOf(exclusions);
        }
    }

    public NextQuestDecision {
        alternatives = List.copyOf(alternatives);
        candidates = List.copyOf(candidates);
        reasons = List.copyOf(reasons);
        permittedControls = List.copyOf(permittedControls);
    }
}

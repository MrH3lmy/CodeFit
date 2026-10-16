package com.codefit.evolution.mastery;

import static com.codefit.evolution.mastery.MasteryTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.evidence.*;

import java.time.Instant;
import java.util.*;

/** A revisable interpretation of observed scope, not a permanent claim about a person. */
public record MasteryEstimate(
        SkillRef skill,
        GraphRef graph,
        EstimateInput.Epoch epoch,
        Band band,
        Band historicalBand,
        Support support,
        Freshness freshness,
        boolean contradicted,
        boolean regressionCap,
        boolean currentCapabilitySupported,
        int independentFamilies,
        Set<Integer> supportedBehaviors,
        Map<EvidenceTypes.Provenance, Integer> provenanceCounts,
        Map<EvidenceTypes.Assistance, Integer> assistanceCounts,
        Map<EvidenceTypes.Exposure, Integer> exposureCounts,
        Instant lastMeaningfulObservation,
        Instant asOf,
        String policyVersion,
        List<Contribution> contributions,
        List<Exclusion> exclusions,
        List<Reason> reasons) {
    public record Contribution(
            String evidenceId,
            EvidenceMappingRef mapping,
            String submissionId,
            String family,
            Set<Integer> behaviors,
            Set<Integer> failedBehaviors,
            List<String> checkIds,
            boolean eligibleDeliberateFailure,
            boolean independent) {
        public Contribution {
            behaviors = Set.copyOf(behaviors);
            failedBehaviors = Set.copyOf(failedBehaviors);
            checkIds = List.copyOf(checkIds);
        }
    }

    public record Reason(ReasonCode code, String message, List<String> evidenceIds) {
        public Reason {
            evidenceIds = List.copyOf(evidenceIds);
        }
    }

    public record Exclusion(
            String evidenceId, EvidenceMappingRef mapping, ReasonCode code, String message) {}

    public MasteryEstimate {
        supportedBehaviors = Set.copyOf(supportedBehaviors);
        provenanceCounts = Map.copyOf(provenanceCounts);
        assistanceCounts = Map.copyOf(assistanceCounts);
        exposureCounts = Map.copyOf(exposureCounts);
        contributions = List.copyOf(contributions);
        exclusions = List.copyOf(exclusions);
        reasons = List.copyOf(reasons);
    }
}

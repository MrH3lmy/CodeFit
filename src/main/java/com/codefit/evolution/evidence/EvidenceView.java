package com.codefit.evolution.evidence;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import com.codefit.evolution.SkillRef;

import java.util.Set;

/** Effective means current usable interpretation, not proof of independent success or mastery. */
public record EvidenceView(
        StoredEvidence evidence,
        EvidenceMappingRef mapping,
        SkillRef skill,
        Availability currentAvailability,
        Set<Exclusion> exclusions) {
    public EvidenceView {
        exclusions = Set.copyOf(exclusions);
    }

    public boolean effective() {
        return exclusions.isEmpty() && currentAvailability == Availability.AVAILABLE;
    }

    public boolean objectivelyEvaluated() {
        return effective()
                && evidence.observation().outcome() != Outcome.UNAVAILABLE
                && (evidence.observation().provenance() == Provenance.OBJECTIVE_REVIEW
                        || evidence.observation().provenance() == Provenance.LOCAL_CHECK);
    }
}

package com.codefit.service;

import com.codefit.model.InterviewDomain;

import java.util.List;

/**
 * How ready one {@link InterviewDomain} is: its own score (averaged over only its measurable
 * requirements - see {@link InterviewReadinessService}), how much of the domain that average
 * actually covers, and - for a critical gate - whether it passes its minimum threshold.
 *
 * @param scorePercent   whole-percentage-points average of measurable requirements (0-100), or
 *                        {@code null} when the domain has none
 * @param coveragePercent {@code measuredRequirementCount / totalRequirementCount} as a whole percentage
 */
public record InterviewDomainReadiness(
        String domainId,
        String domainTitle,
        int weightPercent,
        boolean criticalGate,
        Integer minimumReadinessThresholdPercent,
        Integer scorePercent,
        int coveragePercent,
        int measuredRequirementCount,
        int totalRequirementCount,
        InterviewDomainReadinessStatus status,
        List<InterviewRequirementReadiness> requirements
) {
    public InterviewDomainReadiness {
        requirements = List.copyOf(requirements);
        boolean statusImpliesScore = status == InterviewDomainReadinessStatus.PASS
                || status == InterviewDomainReadinessStatus.FAIL
                || status == InterviewDomainReadinessStatus.MEASURED;
        if (statusImpliesScore && scorePercent == null) {
            throw new IllegalArgumentException("Domain readiness '" + domainId + "' with status " + status + " must have a score.");
        }
        if (status == InterviewDomainReadinessStatus.NOT_MEASURED && scorePercent != null) {
            throw new IllegalArgumentException("NOT_MEASURED domain readiness '" + domainId + "' must not have a score.");
        }
    }

    public boolean isMeasured() {
        return scorePercent != null;
    }
}

package com.codefit.service;

import com.codefit.model.InterviewPreparationProfile;

import java.util.List;

/**
 * How ready the user is for one {@link InterviewPreparationProfile}, computed dynamically from
 * existing CodeFit progress by {@link InterviewReadinessService} - nothing here is persisted.
 *
 * <p>{@code overallReadinessPercent} is the weighted average of only the <em>measured</em> domains
 * (see {@link InterviewReadinessService} for the formula) so unmeasured/planned domains can never
 * drag the score down by silently counting as 0%; {@code coveragePercent} exposes how much of the
 * profile's total weight that average actually represents, so a caller can tell "71% and fully
 * measured" apart from "71% but only 70% of the profile has data yet". {@code overallReadinessPercent}
 * is {@code null} only when literally nothing in the profile is measurable yet.
 *
 * @param blockingCriticalDomainIds critical-gate domain ids that are not currently {@code PASS}
 *                                  (either {@code FAIL} or {@code NOT_MEASURED}) - empty when every
 *                                  critical gate passes
 */
public record InterviewReadinessResult(
        String profileId,
        String profileTitle,
        List<InterviewDomainReadiness> domains,
        Integer overallReadinessPercent,
        int coveragePercent,
        InterviewReadinessStatus status,
        List<String> blockingCriticalDomainIds
) {
    public InterviewReadinessResult {
        domains = List.copyOf(domains);
        blockingCriticalDomainIds = List.copyOf(blockingCriticalDomainIds);
    }
}

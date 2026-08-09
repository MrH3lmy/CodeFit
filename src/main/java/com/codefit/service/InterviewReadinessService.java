package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;

import java.util.List;
import java.util.Optional;

/**
 * Answers "how ready is the user for this interview-preparation profile, per domain and overall?"
 * by resolving each {@link InterviewRequirement} against existing CodeFit progress data (via
 * {@link InterviewRequirementReadinessResolver}) and aggregating it up through
 * {@link InterviewDomain} to an {@link InterviewReadinessResult} - entirely at read time, nothing is
 * persisted here.
 *
 * <h2>Weighting</h2>
 * A domain's score is the average of only its measurable requirements. Overall weighting accounts
 * for partial domain coverage: a domain contributes {@code domainWeight * measuredRequirements /
 * totalRequirements} of effective measured weight, so one measured requirement can never make an
 * entire domain appear covered. The overall score is then the weighted average over that effective
 * measured weight. Planned/unmeasured requirements are never treated as zero.
 *
 * <h2>Critical gates</h2>
 * A critical domain below its threshold is {@link InterviewDomainReadinessStatus#FAIL} even if only
 * part of the domain is currently measurable. A critical domain at/above threshold cannot become
 * {@link InterviewDomainReadinessStatus#PASS} until every requirement in that domain is measurable;
 * until then it is {@link InterviewDomainReadinessStatus#PARTIAL}. A PARTIAL or NOT_MEASURED critical
 * gate prevents an overall READY result and yields {@link InterviewReadinessStatus#INSUFFICIENT_DATA}
 * unless another critical gate is already measurably failing.
 *
 * <h2>Rounding policy</h2>
 * Every exposed score/coverage/threshold in this slice is a whole percentage point (0-100), rounded
 * with {@link Math#round(double)} at the aggregation boundary. Effective measured weights remain
 * doubles internally so domain coverage is not distorted by an intermediate rounded percentage.
 *
 * <h2>Validation</h2>
 * {@link #calculate(InterviewPreparationProfile)} rejects a structurally invalid profile (see
 * {@link InterviewPreparationProfile#validate()}) before resolving or scoring anything - an invalid
 * profile can never produce a readiness result, silently-normalized or otherwise.
 */
public class InterviewReadinessService {

    /**
     * The overall pass bar (0-100), kept out of {@link InterviewPreparationProfile} itself since it is
     * a property of how the readiness engine grades a profile, not of the profile's content.
     */
    public record InterviewReadinessPolicy(int overallReadinessThresholdPercent) {
        public InterviewReadinessPolicy {
            if (overallReadinessThresholdPercent < 0 || overallReadinessThresholdPercent > 100) {
                throw new IllegalArgumentException(
                        "Overall readiness threshold must be between 0 and 100: " + overallReadinessThresholdPercent);
            }
        }
    }

    public static final InterviewReadinessPolicy DEFAULT_POLICY = new InterviewReadinessPolicy(75);

    private final InterviewProfileService interviewProfileService;
    private final List<InterviewRequirementReadinessResolver> resolvers;
    private final InterviewReadinessPolicy policy;

    public InterviewReadinessService() {
        this(new InterviewProfileService(),
                List.of(new DeckInterviewReadinessResolver(), new ProblemSolvingInterviewReadinessResolver()),
                DEFAULT_POLICY);
    }

    InterviewReadinessService(InterviewProfileService interviewProfileService,
                              List<InterviewRequirementReadinessResolver> resolvers, InterviewReadinessPolicy policy) {
        this.interviewProfileService = interviewProfileService;
        this.resolvers = List.copyOf(resolvers);
        this.policy = policy;
    }

    public Optional<InterviewReadinessResult> calculate(String profileId) {
        return interviewProfileService.findProfile(profileId).map(this::calculate);
    }

    /**
     * @throws IllegalArgumentException if {@link InterviewPreparationProfile#validate()} reports any
     *                                  violation (duplicate ids, weights not summing to exactly 100%,
     *                                  etc.)
     */
    public InterviewReadinessResult calculate(InterviewPreparationProfile profile) {
        List<String> violations = profile.validate();
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException("Cannot calculate readiness for invalid profile '" + profile.getId()
                    + "': " + String.join("; ", violations));
        }

        List<InterviewDomainReadiness> domainReadiness = profile.getDomains().stream()
                .map(this::resolveDomain)
                .toList();
        return buildResult(profile, domainReadiness, policy);
    }

    private InterviewDomainReadiness resolveDomain(InterviewDomain domain) {
        List<InterviewRequirementReadiness> requirementReadiness = domain.getRequirements().stream()
                .map(this::resolveRequirement)
                .toList();
        return buildDomainReadiness(domain, requirementReadiness);
    }

    private InterviewRequirementReadiness resolveRequirement(InterviewRequirement requirement) {
        if (!requirement.isAvailable()) {
            return InterviewRequirementReadiness.planned(requirement);
        }
        InterviewMaterialType type = requirement.getReference().type();
        return resolvers.stream()
                .filter(resolver -> resolver.supports(type))
                .findFirst()
                .map(resolver -> resolver.resolve(requirement))
                .orElseGet(() -> InterviewRequirementReadiness.unmeasurable(requirement, type,
                        "No readiness resolver is registered for material type " + type + " yet."));
    }

    // ---- Pure aggregation, independent of the database so it is directly unit testable. ----

    static InterviewDomainReadiness buildDomainReadiness(InterviewDomain domain,
                                                          List<InterviewRequirementReadiness> requirementReadiness) {
        List<InterviewRequirementReadiness> measured = requirementReadiness.stream()
                .filter(InterviewRequirementReadiness::measurable)
                .toList();
        int totalRequirementCount = requirementReadiness.size();
        int measuredRequirementCount = measured.size();
        int coveragePercent = totalRequirementCount == 0 ? 0
                : roundToPercent(measuredRequirementCount * 100.0 / totalRequirementCount);

        if (measured.isEmpty()) {
            return new InterviewDomainReadiness(domain.getId(), domain.getTitle(), domain.getWeightPercent(),
                    domain.isCriticalGate(), domain.getMinimumReadinessThresholdPercent(), null, coveragePercent,
                    measuredRequirementCount, totalRequirementCount, InterviewDomainReadinessStatus.NOT_MEASURED,
                    requirementReadiness);
        }

        int scorePercent = roundToPercent(
                measured.stream().mapToInt(InterviewRequirementReadiness::scorePercent).average().orElseThrow());

        InterviewDomainReadinessStatus status;
        if (domain.isCriticalGate()) {
            if (scorePercent < domain.getMinimumReadinessThresholdPercent()) {
                status = InterviewDomainReadinessStatus.FAIL;
            } else if (measuredRequirementCount < totalRequirementCount) {
                status = InterviewDomainReadinessStatus.PARTIAL;
            } else {
                status = InterviewDomainReadinessStatus.PASS;
            }
        } else {
            status = InterviewDomainReadinessStatus.MEASURED;
        }

        return new InterviewDomainReadiness(domain.getId(), domain.getTitle(), domain.getWeightPercent(),
                domain.isCriticalGate(), domain.getMinimumReadinessThresholdPercent(), scorePercent, coveragePercent,
                measuredRequirementCount, totalRequirementCount, status, requirementReadiness);
    }

    static InterviewReadinessResult buildResult(InterviewPreparationProfile profile,
                                                 List<InterviewDomainReadiness> domainReadiness,
                                                 InterviewReadinessPolicy policy) {
        int totalWeightPercent = domainReadiness.stream().mapToInt(InterviewDomainReadiness::weightPercent).sum();
        double measuredWeightPercent = domainReadiness.stream()
                .mapToDouble(InterviewReadinessService::effectiveMeasuredWeightPercent)
                .sum();
        int coveragePercent = totalWeightPercent == 0 ? 0
                : roundToPercent(measuredWeightPercent * 100.0 / totalWeightPercent);

        Integer overallReadinessPercent = null;
        if (measuredWeightPercent > 0.0) {
            double weightedSum = domainReadiness.stream()
                    .filter(InterviewDomainReadiness::isMeasured)
                    .mapToDouble(readiness -> readiness.scorePercent() * effectiveMeasuredWeightPercent(readiness))
                    .sum();
            overallReadinessPercent = roundToPercent(weightedSum / measuredWeightPercent);
        }

        List<InterviewDomainReadiness> criticalDomains = domainReadiness.stream()
                .filter(InterviewDomainReadiness::criticalGate)
                .toList();
        List<String> blockingCriticalDomainIds = criticalDomains.stream()
                .filter(readiness -> readiness.status() != InterviewDomainReadinessStatus.PASS)
                .map(InterviewDomainReadiness::domainId)
                .toList();
        boolean anyCriticalFailed = criticalDomains.stream()
                .anyMatch(readiness -> readiness.status() == InterviewDomainReadinessStatus.FAIL);
        boolean anyCriticalIncomplete = criticalDomains.stream()
                .anyMatch(readiness -> readiness.status() == InterviewDomainReadinessStatus.NOT_MEASURED
                        || readiness.status() == InterviewDomainReadinessStatus.PARTIAL);

        InterviewReadinessStatus status;
        if (anyCriticalFailed) {
            status = InterviewReadinessStatus.NOT_READY;
        } else if (anyCriticalIncomplete || overallReadinessPercent == null) {
            status = InterviewReadinessStatus.INSUFFICIENT_DATA;
        } else if (overallReadinessPercent >= policy.overallReadinessThresholdPercent()) {
            status = InterviewReadinessStatus.READY;
        } else {
            status = InterviewReadinessStatus.NOT_READY;
        }

        return new InterviewReadinessResult(profile.getId(), profile.getTitle(), domainReadiness,
                overallReadinessPercent, coveragePercent, status, blockingCriticalDomainIds);
    }

    private static double effectiveMeasuredWeightPercent(InterviewDomainReadiness readiness) {
        if (!readiness.isMeasured() || readiness.totalRequirementCount() <= 0) {
            return 0.0;
        }
        return readiness.weightPercent()
                * (readiness.measuredRequirementCount() / (double) readiness.totalRequirementCount());
    }

    private static int roundToPercent(double value) {
        return (int) Math.round(value);
    }
}

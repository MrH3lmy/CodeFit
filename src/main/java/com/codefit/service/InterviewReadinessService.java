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
 * A domain's score is the average of only its <em>measurable</em> requirements (unmeasured/planned
 * requirements contribute neither a 0 nor a bias, they are simply excluded). The overall score is
 * likewise a weighted average over only the <em>measured</em> domains:
 * {@code sum(domainScore * domainWeight) / sum(measuredDomainWeights)}. This is deliberately not
 * divided by the full 100% weight, so domains with no content yet (the still-unbuilt RJ modules)
 * cannot silently drag the score toward 0 - {@link InterviewReadinessResult#coveragePercent()}
 * exposes how much of the profile that average actually represents.
 *
 * <h2>Critical gates</h2>
 * A critical domain that is measurable and below its {@link InterviewDomain#getMinimumReadinessThresholdPercent()}
 * always forces {@link InterviewReadinessStatus#NOT_READY}, regardless of how high the overall score
 * is. A critical domain that cannot currently be measured forces
 * {@link InterviewReadinessStatus#INSUFFICIENT_DATA} rather than ever claiming
 * {@link InterviewReadinessStatus#READY}. Only when every critical gate passes and the overall score
 * clears {@link InterviewReadinessPolicy#overallReadinessThresholdPercent()} is the result READY.
 *
 * <h2>Rounding policy</h2>
 * Every exposed score/coverage/threshold in this slice is a whole percentage point (0-100), rounded
 * with {@link Math#round(double)} once per aggregation step (requirement -&gt; domain -&gt; overall) -
 * consistently, so nothing here mixes a decimal-percent policy with an integer one.
 */
public class InterviewReadinessService {

    /**
     * The overall pass bar (0-100), kept out of {@link InterviewPreparationProfile} itself since it is
     * a property of how the readiness engine grades a profile, not of the profile's content - a
     * second profile, or a future re-tuning of this bar, should never require touching the domain
     * model. Mirrors {@link MasteryService.MasteryThresholds}/{@code DEFAULT_THRESHOLDS}.
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

    public InterviewReadinessResult calculate(InterviewPreparationProfile profile) {
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

    // ---- Pure aggregation, independent of the database so it is directly unit testable
    // (mirrors TrainingPathService.recommend and MasteryService.evaluate). ----

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
            status = scorePercent >= domain.getMinimumReadinessThresholdPercent()
                    ? InterviewDomainReadinessStatus.PASS
                    : InterviewDomainReadinessStatus.FAIL;
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
        int measuredWeightPercent = domainReadiness.stream()
                .filter(InterviewDomainReadiness::isMeasured)
                .mapToInt(InterviewDomainReadiness::weightPercent)
                .sum();
        int coveragePercent = totalWeightPercent == 0 ? 0
                : roundToPercent(measuredWeightPercent * 100.0 / totalWeightPercent);

        Integer overallReadinessPercent = null;
        if (measuredWeightPercent > 0) {
            double weightedSum = domainReadiness.stream()
                    .filter(InterviewDomainReadiness::isMeasured)
                    .mapToDouble(readiness -> readiness.scorePercent() * (double) readiness.weightPercent())
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
        boolean anyCriticalUnmeasured = criticalDomains.stream()
                .anyMatch(readiness -> readiness.status() == InterviewDomainReadinessStatus.NOT_MEASURED);

        InterviewReadinessStatus status;
        if (anyCriticalFailed) {
            status = InterviewReadinessStatus.NOT_READY;
        } else if (anyCriticalUnmeasured || overallReadinessPercent == null) {
            status = InterviewReadinessStatus.INSUFFICIENT_DATA;
        } else if (overallReadinessPercent >= policy.overallReadinessThresholdPercent()) {
            status = InterviewReadinessStatus.READY;
        } else {
            status = InterviewReadinessStatus.NOT_READY;
        }

        return new InterviewReadinessResult(profile.getId(), profile.getTitle(), domainReadiness,
                overallReadinessPercent, coveragePercent, status, blockingCriticalDomainIds);
    }

    private static int roundToPercent(double value) {
        return (int) Math.round(value);
    }
}

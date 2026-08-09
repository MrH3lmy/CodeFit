package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds one interview-focused daily workout by composing existing CodeFit behavior:
 *
 * <ul>
 *   <li>spaced-repetition work comes directly from {@link GuidedTrainingService}'s adaptive review plan,</li>
 *   <li>the coding challenge is {@link GuidedPracticeService}'s existing next recommended problem,</li>
 *   <li>technical focus is selected from the weakest/least-covered interview domain,</li>
 *   <li>system-design and production-failure drills alternate deterministically by date, and</li>
 *   <li>reflection converts the day's highest-value miss into a future flashcard.</li>
 * </ul>
 *
 * <p>No workout state is persisted. Rebuilding the same date from the same underlying progress gives
 * the same orchestration while review/problem progress continues to live in its existing tables.
 */
public class InterviewWorkoutService {
    public static final int DEFAULT_CODING_MINUTES = 45;
    public static final int DEFAULT_TECHNICAL_MINUTES = 10;
    public static final int DEFAULT_SCENARIO_MINUTES = 25;
    public static final int DEFAULT_REFLECTION_MINUTES = 5;

    private static final WorkoutPolicy DEFAULT_POLICY = new WorkoutPolicy(
            DEFAULT_CODING_MINUTES, DEFAULT_TECHNICAL_MINUTES, DEFAULT_SCENARIO_MINUTES,
            DEFAULT_REFLECTION_MINUTES);

    private static final Map<String, ProfileWorkoutConfiguration> PROFILE_CONFIGURATIONS = Map.of(
            RevolutJavaInterviewProfile.ID,
            new ProfileWorkoutConfiguration(
                    "system-design",
                    "reliability-observability-jvm-role-stack",
                    "abe-10")
    );

    private final InterviewProfileService interviewProfileService;
    private final InterviewReadinessService interviewReadinessService;
    private final GuidedTrainingService guidedTrainingService;
    private final GuidedPracticeService guidedPracticeService;
    private final WorkoutPolicy policy;

    public InterviewWorkoutService() {
        this(new InterviewProfileService(), new InterviewReadinessService(), new GuidedTrainingService(),
                new GuidedPracticeService(), DEFAULT_POLICY);
    }

    InterviewWorkoutService(InterviewProfileService interviewProfileService,
                            InterviewReadinessService interviewReadinessService,
                            GuidedTrainingService guidedTrainingService,
                            GuidedPracticeService guidedPracticeService,
                            WorkoutPolicy policy) {
        this.interviewProfileService = interviewProfileService;
        this.interviewReadinessService = interviewReadinessService;
        this.guidedTrainingService = guidedTrainingService;
        this.guidedPracticeService = guidedPracticeService;
        this.policy = policy;
    }

    public Optional<InterviewWorkout> build(String profileId) {
        return build(profileId, LocalDate.now());
    }

    /** Package-visible so tests can pin the alternating drill date. */
    Optional<InterviewWorkout> build(String profileId, LocalDate date) {
        Optional<InterviewPreparationProfile> profile = interviewProfileService.findProfile(profileId);
        if (profile.isEmpty()) {
            return Optional.empty();
        }

        ProfileWorkoutConfiguration configuration = PROFILE_CONFIGURATIONS.get(profileId);
        if (configuration == null) {
            throw new IllegalArgumentException("No interview workout configuration is registered for profile '"
                    + profileId + "'.");
        }

        InterviewReadinessResult readiness = interviewReadinessService.calculate(profile.get());
        int reviewMinutes = guidedTrainingService.getPreferredSessionMinutes();
        GuidedTrainingPlan guidedPlan = guidedTrainingService.buildPlan(reviewMinutes);
        TodayPlan todayPlan = guidedPracticeService.buildTodayPlan();

        return Optional.of(compose(profile.get(), readiness, guidedPlan, todayPlan, date, configuration, policy));
    }

    /**
     * Pure composition over already-built snapshots. Keeping this DB-free makes the scheduling and
     * prioritization rules directly testable without duplicating repository fixtures.
     */
    static InterviewWorkout compose(InterviewPreparationProfile profile,
                                    InterviewReadinessResult readiness,
                                    GuidedTrainingPlan guidedPlan,
                                    TodayPlan todayPlan,
                                    LocalDate date,
                                    ProfileWorkoutConfiguration configuration,
                                    WorkoutPolicy policy) {
        if (!profile.getId().equals(readiness.profileId())) {
            throw new IllegalArgumentException("Readiness result belongs to profile '" + readiness.profileId()
                    + "', not '" + profile.getId() + "'.");
        }

        InterviewWorkout.Prompt technicalPrompt = selectTechnicalPrompt(profile, readiness, policy.technicalMinutes());
        InterviewWorkout.Prompt scenarioPrompt = selectScenarioPrompt(profile, date, configuration, policy.scenarioMinutes());
        InterviewWorkout.Prompt reflectionPrompt = reflectionPrompt(policy.reflectionMinutes());

        return new InterviewWorkout(
                profile.getId(),
                profile.getTitle(),
                date,
                readiness,
                guidedPlan.sessionMinutes(),
                guidedPlan.reviewPlan(),
                policy.codingMinutes(),
                todayPlan.nextRecommended(),
                todayPlan.nextRecommendedReason(),
                technicalPrompt,
                scenarioPrompt,
                reflectionPrompt);
    }

    /**
     * Selects one explain-it-out-loud prompt. Critical failures come first, then partial/unmeasured
     * critical gates, then already-passing critical areas, then non-critical domains. Within the
     * selected domain an unmeasured deck-backed requirement is preferred because it represents a
     * coverage hole; otherwise the lowest measured requirement wins.
     */
    static InterviewWorkout.Prompt selectTechnicalPrompt(InterviewPreparationProfile profile,
                                                          InterviewReadinessResult readiness,
                                                          int targetMinutes) {
        InterviewDomain selectedDomain = profile.getDomains().stream()
                .filter(InterviewWorkoutService::hasDeckBackedRequirement)
                .min(Comparator
                        .comparingInt((InterviewDomain domain) -> technicalDomainPriority(domainReadiness(readiness, domain.getId())))
                        .thenComparingInt(domain -> nullableScoreForSort(domainReadiness(readiness, domain.getId())))
                        .thenComparing(Comparator.comparingInt(InterviewDomain::getWeightPercent).reversed())
                        .thenComparing(InterviewDomain::getId))
                .orElseThrow(() -> new IllegalStateException(
                        "Interview profile '" + profile.getId() + "' has no deck-backed requirement for a technical drill."));

        InterviewDomainReadiness selectedDomainReadiness = domainReadiness(readiness, selectedDomain.getId());
        InterviewRequirement selectedRequirement = selectedDomain.getRequirements().stream()
                .filter(requirement -> requirement.isAvailable()
                        && requirement.getReference().type() == InterviewMaterialType.DECK)
                .min(Comparator
                        .comparingInt((InterviewRequirement requirement) ->
                                requirementPriority(selectedDomainReadiness, requirement.getId()))
                        .thenComparing(InterviewRequirement::getId))
                .orElseThrow();

        return promptFromRequirement(
                InterviewWorkout.PromptType.TECHNICAL_DEEP_DIVE,
                selectedDomain,
                selectedRequirement,
                "Explain: " + selectedRequirement.getTitle(),
                "Explain this out loud as if the interviewer asked you cold. Cover: "
                        + selectedRequirement.getDescription()
                        + " State the key invariants, trade-offs, failure modes, and one concrete Java or production example.",
                targetMinutes);
    }

    /** Alternates deterministic system-design and production-failure drills on consecutive dates. */
    static InterviewWorkout.Prompt selectScenarioPrompt(InterviewPreparationProfile profile,
                                                         LocalDate date,
                                                         ProfileWorkoutConfiguration configuration,
                                                         int targetMinutes) {
        boolean systemDesignDay = Math.floorMod(date.toEpochDay(), 2) == 0;
        if (systemDesignDay) {
            InterviewDomain domain = requiredDomain(profile, configuration.systemDesignDomainId());
            List<InterviewRequirement> requirements = domain.getRequirements();
            if (requirements.isEmpty()) {
                throw new IllegalStateException("System-design workout domain '" + domain.getId() + "' has no requirements.");
            }
            int index = Math.floorMod(Math.floorDiv(date.toEpochDay(), 2), requirements.size());
            InterviewRequirement requirement = requirements.get(index);
            return promptFromRequirement(
                    InterviewWorkout.PromptType.SYSTEM_DESIGN,
                    domain,
                    requirement,
                    "System design: " + requirement.getTitle(),
                    "Design the system from scratch under interview conditions. Start by clarifying functional and non-functional "
                            + "requirements, estimate scale, define APIs and the data model, then sketch components. Focus today's drill on: "
                            + requirement.getDescription()
                            + " Explicitly cover consistency, failure handling, observability, security, bottlenecks, and trade-offs.",
                    targetMinutes);
        }

        InterviewDomain domain = requiredDomain(profile, configuration.failureDomainId());
        InterviewRequirement requirement = domain.getRequirements().stream()
                .filter(candidate -> candidate.getId().equals(configuration.failureRequirementId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failure-scenario requirement '"
                        + configuration.failureRequirementId() + "' is missing from domain '" + domain.getId() + "'."));
        return promptFromRequirement(
                InterviewWorkout.PromptType.FAILURE_SCENARIO,
                domain,
                requirement,
                "Production failure drill: " + requirement.getTitle(),
                "Work through one realistic production incident around: " + requirement.getDescription()
                        + " Explain the symptoms, first hypotheses, metrics/logs/traces you would inspect, immediate containment, "
                        + "root cause, durable fix, and the trade-offs introduced by that fix.",
                targetMinutes);
    }

    private static InterviewWorkout.Prompt reflectionPrompt(int targetMinutes) {
        return new InterviewWorkout.Prompt(
                InterviewWorkout.PromptType.REFLECTION,
                null,
                null,
                null,
                "Interview workout reflection",
                "Capture the highest-value gap from today: one assumption you missed, trade-off you explained poorly, "
                        + "or concept you could not recall. Turn that gap into a CodeFit flashcard before ending the session.",
                targetMinutes,
                false,
                null);
    }

    private static InterviewWorkout.Prompt promptFromRequirement(InterviewWorkout.PromptType type,
                                                                 InterviewDomain domain,
                                                                 InterviewRequirement requirement,
                                                                 String title,
                                                                 String instruction,
                                                                 int targetMinutes) {
        String sourceReferenceKey = requirement.isAvailable() ? requirement.getReference().key() : null;
        return new InterviewWorkout.Prompt(type, domain.getId(), domain.getTitle(), requirement.getId(), title,
                instruction, targetMinutes, requirement.isAvailable(), sourceReferenceKey);
    }

    private static InterviewDomain requiredDomain(InterviewPreparationProfile profile, String domainId) {
        return profile.findDomainById(domainId)
                .orElseThrow(() -> new IllegalStateException("Interview workout configuration references missing domain '"
                        + domainId + "' in profile '" + profile.getId() + "'."));
    }

    private static boolean hasDeckBackedRequirement(InterviewDomain domain) {
        return domain.getRequirements().stream().anyMatch(requirement -> requirement.isAvailable()
                && requirement.getReference().type() == InterviewMaterialType.DECK);
    }

    private static InterviewDomainReadiness domainReadiness(InterviewReadinessResult readiness, String domainId) {
        return readiness.domains().stream()
                .filter(domain -> domain.domainId().equals(domainId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Readiness result is missing domain '" + domainId + "'."));
    }

    private static int technicalDomainPriority(InterviewDomainReadiness readiness) {
        if (readiness.criticalGate()) {
            return switch (readiness.status()) {
                case FAIL -> 0;
                case PARTIAL -> 1;
                case NOT_MEASURED -> 2;
                case PASS -> 3;
                case MEASURED -> 3;
            };
        }
        return readiness.isMeasured() ? 4 : 5;
    }

    private static int nullableScoreForSort(InterviewDomainReadiness readiness) {
        return readiness.scorePercent() == null ? 101 : readiness.scorePercent();
    }

    private static int requirementPriority(InterviewDomainReadiness domainReadiness, String requirementId) {
        Optional<InterviewRequirementReadiness> readiness = domainReadiness.requirements().stream()
                .filter(requirement -> requirement.requirementId().equals(requirementId))
                .findFirst();
        if (readiness.isEmpty() || !readiness.get().measurable()) {
            return -1;
        }
        return readiness.get().scorePercent();
    }

    record ProfileWorkoutConfiguration(String systemDesignDomainId, String failureDomainId,
                                       String failureRequirementId) {
    }

    record WorkoutPolicy(int codingMinutes, int technicalMinutes, int scenarioMinutes, int reflectionMinutes) {
        WorkoutPolicy {
            if (codingMinutes <= 0 || technicalMinutes <= 0 || scenarioMinutes <= 0 || reflectionMinutes <= 0) {
                throw new IllegalArgumentException("Interview workout block durations must all be positive.");
            }
        }
    }
}

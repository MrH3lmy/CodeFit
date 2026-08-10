package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.repository.InterviewMockRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Generates and grades interview simulations without touching flashcard schedules or problem
 * progress. The plan contains the prompts/rubric; completion turns rubric scores into durable stage
 * and domain evidence through {@link InterviewMockRepository}.
 */
public class InterviewMockService {
    public static final int LIVE_CODING_MINUTES = 45;
    public static final int TECHNICAL_MINUTES = 45;
    public static final int SYSTEM_DESIGN_MINUTES = 45;
    public static final int TEAM_FIT_MINUTES = 30;

    private static final List<String> TECHNICAL_DOMAIN_IDS = List.of(
            "java-concurrency-jmm",
            "databases-postgresql-jooq",
            "distributed-systems-architecture");

    private final InterviewProfileService profileService;
    private final InterviewReadinessService readinessService;
    private final GuidedPracticeService guidedPracticeService;
    private final InterviewMockRepository repository;

    public InterviewMockService() {
        this(new InterviewProfileService(), new InterviewReadinessService(), new GuidedPracticeService(),
                new InterviewMockRepository());
    }

    InterviewMockService(InterviewProfileService profileService, InterviewReadinessService readinessService,
                         GuidedPracticeService guidedPracticeService, InterviewMockRepository repository) {
        this.profileService = profileService;
        this.readinessService = readinessService;
        this.guidedPracticeService = guidedPracticeService;
        this.repository = repository;
    }

    public Optional<InterviewMockPlan> build(String profileId, InterviewMockMode mode) {
        return profileService.findProfile(profileId).map(profile -> build(profile, mode));
    }

    InterviewMockPlan build(InterviewPreparationProfile profile, InterviewMockMode mode) {
        InterviewReadinessResult readiness = readinessService.calculate(profile);

        List<InterviewMockPlan.Stage> stages = switch (mode) {
            case LIVE_CODING -> List.of(liveCodingStage(guidedPracticeService.buildTodayPlan(), 100));
            case TECHNICAL_DEEP_DIVE -> List.of(technicalStage(profile, readiness, 100));
            case SYSTEM_DESIGN -> List.of(systemDesignStage(profile, 100));
            case TEAM_FIT -> List.of(teamFitStage(profile, 100));
            case FULL_INTERVIEW_LOOP -> {
                TodayPlan todayPlan = guidedPracticeService.buildTodayPlan();
                yield List.of(
                        liveCodingStage(todayPlan, 25),
                        technicalStage(profile, readiness, 25),
                        systemDesignStage(profile, 25),
                        teamFitStage(profile, 25));
            }
        };
        return new InterviewMockPlan(profile.getId(), profile.getTitle(), mode, stages);
    }

    /**
     * Grades every rubric criterion, persists the result, and returns the durable evaluation. The
     * supplied map must contain exactly one score (0-100) for every criterion in the plan.
     */
    public InterviewMockEvaluation complete(InterviewMockPlan plan, Map<String, Integer> criterionScores, String notes) {
        InterviewMockEvaluation evaluation = evaluate(plan, criterionScores, notes, UUID.randomUUID().toString(),
                LocalDateTime.now());
        repository.save(evaluation);
        return evaluation;
    }

    public List<InterviewMockRepository.StoredRun> recentRuns(String profileId, int limit) {
        return repository.findRecentRuns(profileId, limit);
    }

    /** Pure grading logic kept DB-free for deterministic unit tests. */
    static InterviewMockEvaluation evaluate(InterviewMockPlan plan, Map<String, Integer> criterionScores,
                                            String notes, String runId, LocalDateTime completedAt) {
        Map<String, InterviewMockPlan.RubricCriterion> criteriaById = plan.stages().stream()
                .flatMap(stage -> stage.rubric().stream())
                .collect(Collectors.toMap(InterviewMockPlan.RubricCriterion::id, criterion -> criterion));
        if (!criterionScores.keySet().equals(criteriaById.keySet())) {
            Set<String> missing = criteriaById.keySet().stream().filter(id -> !criterionScores.containsKey(id))
                    .collect(Collectors.toSet());
            Set<String> extra = criterionScores.keySet().stream().filter(id -> !criteriaById.containsKey(id))
                    .collect(Collectors.toSet());
            throw new IllegalArgumentException("Interview mock scoring must cover exactly the plan rubric. Missing="
                    + missing + ", extra=" + extra + ".");
        }
        criterionScores.forEach((id, score) -> {
            if (score == null || score < 0 || score > 100) {
                throw new IllegalArgumentException("Interview mock criterion '" + id
                        + "' score must be between 0 and 100.");
            }
        });

        List<InterviewMockEvaluation.StageScore> stageScores = new ArrayList<>();
        Map<String, WeightedScore> domainScores = new LinkedHashMap<>();
        double overallWeighted = 0.0;

        for (InterviewMockPlan.Stage stage : plan.stages()) {
            double stageWeighted = 0.0;
            for (InterviewMockPlan.RubricCriterion criterion : stage.rubric()) {
                int score = criterionScores.get(criterion.id());
                stageWeighted += score * criterion.weightPercent();
                double effectiveDomainWeight = stage.weightPercent() * criterion.weightPercent();
                domainScores.computeIfAbsent(criterion.domainId(), ignored -> new WeightedScore())
                        .add(score, effectiveDomainWeight);
            }
            int stageScorePercent = roundPercent(stageWeighted / 100.0);
            stageScores.add(new InterviewMockEvaluation.StageScore(stage.id(), stage.type(), stageScorePercent));
            overallWeighted += stageScorePercent * stage.weightPercent();
        }

        List<InterviewMockEvaluation.DomainScore> domainResults = domainScores.entrySet().stream()
                .map(entry -> new InterviewMockEvaluation.DomainScore(entry.getKey(), entry.getValue().average()))
                .sorted(Comparator.comparing(InterviewMockEvaluation.DomainScore::domainId))
                .toList();

        return new InterviewMockEvaluation(runId, plan.profileId(), plan.mode(),
                roundPercent(overallWeighted / 100.0), completedAt, notes, stageScores, domainResults);
    }

    private InterviewMockPlan.Stage liveCodingStage(TodayPlan todayPlan, int stageWeightPercent) {
        Optional<ProblemLibraryEntry> codingProblem = todayPlan.nextRecommended();
        String problemDescription = codingProblem
                .map(entry -> "Solve " + entry.problem().getTitle() + " (" + entry.problem().getPlatform() + ")")
                .orElse("Solve one unseen medium-difficulty Java data-structures/algorithms problem selected before the mock");
        String prompt = problemDescription + ". Work as if screen-sharing with an interviewer: clarify assumptions first, "
                + "state the brute-force and improved approaches, implement clean Java, analyze complexity, test edge cases, "
                + "and narrate trade-offs. Do not use hints or an editorial during the timed stage.";
        String domain = "live-java-coding-dsa-testing";
        return new InterviewMockPlan.Stage(
                "live-coding",
                InterviewMockPlan.StageType.LIVE_CODING,
                "Live Java coding",
                prompt,
                LIVE_CODING_MINUTES,
                stageWeightPercent,
                codingProblem,
                List.of(
                        criterion("live-framing", "Problem framing", "Clarifies inputs, constraints, edge cases, and success criteria before coding.", domain, 15),
                        criterion("live-correctness", "Correctness", "Produces a correct solution and handles edge cases without interviewer rescue.", domain, 30),
                        criterion("live-complexity", "Algorithmic reasoning", "Chooses appropriate data structures and explains time/space complexity and alternatives.", domain, 20),
                        criterion("live-java", "Java quality", "Writes readable, idiomatic Java with sensible naming, decomposition, and APIs.", domain, 15),
                        criterion("live-testing", "Testing", "Constructs useful examples/tests including boundary and failure cases.", domain, 10),
                        criterion("live-communication", "Communication under time pressure", "Keeps the interviewer oriented while making progress and reacting to feedback.", domain, 10)));
    }

    private InterviewMockPlan.Stage technicalStage(InterviewPreparationProfile profile,
                                                    InterviewReadinessResult readiness, int stageWeightPercent) {
        InterviewDomain weakest = TECHNICAL_DOMAIN_IDS.stream()
                .map(id -> profile.findDomainById(id).orElseThrow())
                .min(Comparator
                        .comparingInt((InterviewDomain domain) -> domainReadiness(readiness, domain.getId()).status()
                                == InterviewDomainReadinessStatus.FAIL ? 0 : 1)
                        .thenComparingInt(domain -> Optional.ofNullable(domainReadiness(readiness, domain.getId()).scorePercent())
                                .orElse(-1)))
                .orElseThrow();
        String prompt = "Run a senior Java technical deep dive. Start with the current weakest area: " + weakest.getTitle()
                + " — " + weakest.getDescription() + " Then cover concurrency/JMM, database correctness/performance, and "
                + "distributed-system delivery/consistency. Answer from first principles, write small Java/SQL examples when useful, "
                + "and make failure modes and trade-offs explicit rather than naming patterns only.";
        return new InterviewMockPlan.Stage(
                "technical-deep-dive",
                InterviewMockPlan.StageType.TECHNICAL_DEEP_DIVE,
                "Technical deep dive",
                prompt,
                TECHNICAL_MINUTES,
                stageWeightPercent,
                Optional.empty(),
                List.of(
                        criterion("tech-concurrency-model", "Concurrency reasoning", "Explains visibility, atomicity, happens-before, liveness, and thread-safety invariants accurately.", "java-concurrency-jmm", 20),
                        criterion("tech-concurrency-code", "Concurrency implementation", "Can turn concurrency reasoning into safe Java primitives/executor/locking choices.", "java-concurrency-jmm", 15),
                        criterion("tech-db-correctness", "Database correctness", "Reasons about transactions, isolation, locking, MVCC, races, and idempotency.", "databases-postgresql-jooq", 20),
                        criterion("tech-db-performance", "Database performance", "Explains indexes, query plans, SQL/jOOQ trade-offs, and performance diagnosis.", "databases-postgresql-jooq", 15),
                        criterion("tech-distributed-semantics", "Distributed semantics", "Reasons about delivery guarantees, consistency, outbox/sagas, partial failure, and retries.", "distributed-systems-architecture", 20),
                        criterion("tech-architecture-tradeoffs", "Architecture trade-offs", "Chooses boundaries and patterns based on constraints rather than pattern matching.", "distributed-systems-architecture", 10)));
    }

    private InterviewMockPlan.Stage systemDesignStage(InterviewPreparationProfile profile, int stageWeightPercent) {
        requireDomain(profile, "system-design");
        requireDomain(profile, "distributed-systems-architecture");
        requireDomain(profile, "databases-postgresql-jooq");
        requireDomain(profile, "reliability-observability-jvm-role-stack");
        requireDomain(profile, "ddd-cqrs-event-driven");
        String prompt = "Design a high-volume money-transfer platform for a fintech app. Clarify functional and non-functional "
                + "requirements, estimate scale, define APIs and the ledger/data model, then design the distributed architecture. "
                + "Cover idempotency, consistency, duplicate delivery, fraud/risk hooks, reconciliation, cache use, failure isolation, "
                + "observability, security, deployment/rollout, and how the design evolves as traffic grows. State trade-offs explicitly.";
        return new InterviewMockPlan.Stage(
                "system-design",
                InterviewMockPlan.StageType.SYSTEM_DESIGN,
                "Fintech system design",
                prompt,
                SYSTEM_DESIGN_MINUTES,
                stageWeightPercent,
                Optional.empty(),
                List.of(
                        criterion("design-scope", "Requirements and scale", "Clarifies requirements, estimates scale, and identifies the important quality attributes.", "system-design", 15),
                        criterion("design-architecture", "End-to-end architecture", "Produces coherent APIs, components, flows, bottlenecks, and explicit trade-offs.", "system-design", 25),
                        criterion("design-distributed", "Distributed correctness", "Handles retries, duplicate delivery, consistency, partitioning, replication, and partial failure.", "distributed-systems-architecture", 20),
                        criterion("design-data", "Data and transaction design", "Uses a correct ledger/data model, transaction boundaries, indexes, and idempotency strategy.", "databases-postgresql-jooq", 15),
                        criterion("design-reliability", "Reliability and operations", "Covers resilience, observability, capacity, rollout, recovery, and operational debugging.", "reliability-observability-jvm-role-stack", 15),
                        criterion("design-domain", "Domain boundaries", "Defines useful bounded contexts/aggregates/events and protects business invariants.", "ddd-cqrs-event-driven", 10)));
    }

    private InterviewMockPlan.Stage teamFitStage(InterviewPreparationProfile profile, int stageWeightPercent) {
        requireDomain(profile, "team-fit-communication-star");
        String prompt = "Answer four interview questions using concise STAR stories: (1) a high-impact production or product problem "
                + "you owned, (2) a disagreement with another senior engineer or stakeholder, (3) a decision made with incomplete "
                + "information under time pressure, and (4) a change where you improved a measurable engineering or business outcome. "
                + "Use concrete actions, numbers where available, what you personally did, and what you learned.";
        String domain = "team-fit-communication-star";
        return new InterviewMockPlan.Stage(
                "team-fit",
                InterviewMockPlan.StageType.TEAM_FIT,
                "Team fit and STAR",
                prompt,
                TEAM_FIT_MINUTES,
                stageWeightPercent,
                Optional.empty(),
                List.of(
                        criterion("fit-structure", "STAR structure", "Keeps stories concise, chronological, and easy to follow without losing important context.", domain, 25),
                        criterion("fit-ownership", "Ownership", "Makes personal decisions/actions clear instead of hiding behind 'we'.", domain, 25),
                        criterion("fit-ambiguity", "Conflict and ambiguity", "Shows mature trade-off handling, disagreement, uncertainty, and collaboration.", domain, 25),
                        criterion("fit-impact", "Quantified impact", "Connects actions to measurable technical, customer, product, or business results.", domain, 25)));
    }

    private static InterviewMockPlan.RubricCriterion criterion(String id, String title, String description,
                                                                String domainId, int weight) {
        return new InterviewMockPlan.RubricCriterion(id, title, description, domainId, weight);
    }

    private static InterviewDomain requireDomain(InterviewPreparationProfile profile, String domainId) {
        return profile.findDomainById(domainId)
                .orElseThrow(() -> new IllegalStateException("Interview profile '" + profile.getId()
                        + "' is missing mock-interview domain '" + domainId + "'."));
    }

    private static InterviewDomainReadiness domainReadiness(InterviewReadinessResult readiness, String domainId) {
        return readiness.domains().stream().filter(domain -> domain.domainId().equals(domainId)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Readiness result is missing domain '" + domainId + "'."));
    }

    private static int roundPercent(double value) {
        return (int) Math.round(value);
    }

    private static final class WeightedScore {
        private double weightedSum;
        private double totalWeight;

        void add(int score, double weight) {
            weightedSum += score * weight;
            totalWeight += weight;
        }

        int average() {
            return roundPercent(weightedSum / totalWeight);
        }
    }
}

package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;

import java.util.List;

/**
 * Static definition of the Revolut Java Senior Software Engineer interview-preparation profile.
 * It composes existing Java Concurrency in Practice / Advanced Backend Engineering / Database
 * Internals material with the seven bundled RJ interview decks. It remains a cross-cutting profile,
 * not another sequential TrainingPath.
 */
final class RevolutJavaInterviewProfile {
    static final String ID = "revolut-java-senior-swe";
    private static final Integer CRITICAL_GATE_MINIMUM_PERCENT = 70;
    private static final String PROBLEM_SOLVING_SUBSYSTEM_KEY = ProblemSolvingInterviewReadinessResolver.SUPPORTED_KEY;

    private RevolutJavaInterviewProfile() {
    }

    static InterviewPreparationProfile build() {
        return new InterviewPreparationProfile(
                ID,
                "Revolut - Java Senior Software Engineer",
                "Interview-preparation profile for the Revolut Java Senior Software Engineer role. It composes "
                        + "existing CodeFit training material, dedicated RJ interview decks, problem-solving evidence, "
                        + "and mock-interview evidence across the published interview domains.",
                List.of(
                        concurrencyAndJmm(),
                        liveCodingDsaTesting(),
                        databasesPostgresJooq(),
                        distributedSystemsArchitecture(),
                        systemDesign(),
                        dddCqrsEventDriven(),
                        reliabilityObservabilityJvmRoleStack(),
                        teamFitCommunicationStar()
                ));
    }

    private static InterviewDomain concurrencyAndJmm() {
        return new InterviewDomain(
                "java-concurrency-jmm",
                "Java Concurrency & Java Memory Model",
                "Thread safety, task execution/cancellation, liveness and performance, and locks/atomics/happens-before reasoning under production load.",
                18, true, CRITICAL_GATE_MINIMUM_PERCENT,
                List.of(
                        InterviewRequirement.available("jcip-01", "JCIP 01 - Fundamentals",
                                "Thread safety, atomicity, visibility, publication, confinement, and immutability.",
                                InterviewMaterialType.DECK, "JCIP 01 - Fundamentals"),
                        InterviewRequirement.available("jcip-02", "JCIP 02 - Task Execution & Cancellation",
                                "Executors, futures, parallel task design, cancellation, interruption, and shutdown.",
                                InterviewMaterialType.DECK, "JCIP 02 - Task Execution & Cancellation"),
                        InterviewRequirement.available("jcip-03", "JCIP 03 - Liveness, Performance & Testing",
                                "Deadlock, lock ordering, starvation, livelock, scalability, and concurrent testing.",
                                InterviewMaterialType.DECK, "JCIP 03 - Liveness, Performance & Testing"),
                        InterviewRequirement.available("jcip-04", "JCIP 04 - Locks, Atomics & Memory Model",
                                "Explicit locks, conditions, AQS, CAS, nonblocking algorithms, and happens-before.",
                                InterviewMaterialType.DECK, "JCIP 04 - Locks, Atomics & Memory Model")
                ));
    }

    private static InterviewDomain liveCodingDsaTesting() {
        return new InterviewDomain(
                "live-java-coding-dsa-testing",
                "Live Java Coding, DSA & Testing",
                "Timed problem solving, data structures and algorithms, modern Java, SOLID/refactoring, and writing tests while coding under time pressure.",
                17, true, CRITICAL_GATE_MINIMUM_PERCENT,
                List.of(
                        InterviewRequirement.available("java-be-06-testing", "Java BE 06 - Testing with JUnit/Mockito",
                                "Unit tests, mocks, integration tests, and repeatable test slices.",
                                InterviewMaterialType.DECK, "Java BE 06 - Testing with JUnit/Mockito"),
                        InterviewRequirement.available("problem-solving-system", "Problem-Solving Training",
                                "Timed problem attempts, roadmap-driven DSA practice, and solving-workspace phase tracking.",
                                InterviewMaterialType.PROBLEM_SOLVING, PROBLEM_SOLVING_SUBSYSTEM_KEY),
                        rj("rj-01", RevolutInterviewContentPackService.RJ01_DECK,
                                "Records, sealed classes, pattern matching, generics, streams, CompletableFuture, virtual threads, and core interview traps.")
                ));
    }

    private static InterviewDomain databasesPostgresJooq() {
        return new InterviewDomain(
                "databases-postgresql-jooq",
                "Databases, PostgreSQL & jOOQ",
                "Isolation levels, locking, idempotency, PostgreSQL performance and operations, and jOOQ-specific query and transaction correctness.",
                15, true, CRITICAL_GATE_MINIMUM_PERCENT,
                List.of(
                        InterviewRequirement.available("abe-02", "ABE 02 - Database Transactions, Locking & Isolation",
                                "Isolation levels, locking, deadlocks, and MVCC.",
                                InterviewMaterialType.DECK, "ABE 02 - Database Transactions, Locking & Isolation"),
                        InterviewRequirement.available("abe-03", "ABE 03 - Idempotency & Race-Condition Prevention",
                                "Idempotency keys, deduplication, compare-and-set, and fencing tokens.",
                                InterviewMaterialType.DECK, "ABE 03 - Idempotency & Race-Condition Prevention"),
                        rj("rj-02", RevolutInterviewContentPackService.RJ02_DECK,
                                "EXPLAIN/ANALYZE, index selection, MVCC and vacuum behavior, query observability, and SQL-first jOOQ patterns.")
                ));
    }

    private static InterviewDomain distributedSystemsArchitecture() {
        return new InterviewDomain(
                "distributed-systems-architecture",
                "Distributed Systems & Architecture",
                "Delivery semantics, transactional outbox and sagas, and distributed-storage foundations behind microservice architecture decisions.",
                15, false, null,
                List.of(
                        InterviewRequirement.available("abe-04", "ABE 04 - Kafka Delivery Semantics, Outbox & DLQs",
                                "At-least-once/at-most-once/exactly-once tradeoffs, transactional outbox, and dead-letter handling.",
                                InterviewMaterialType.DECK, "ABE 04 - Kafka Delivery Semantics, Outbox & DLQs"),
                        InterviewRequirement.available("abe-05", "ABE 05 - Distributed Transactions & Sagas",
                                "Two-phase commit, sagas, compensating actions, and TCC.",
                                InterviewMaterialType.DECK, "ABE 05 - Distributed Transactions & Sagas"),
                        InterviewRequirement.available("di-04", "DI 04 - Distributed Foundations & Consistency",
                                "Partial failure, clocks, failure detection, CAP, consistency models, and quorums.",
                                InterviewMaterialType.DECK, "DI 04 - Distributed Foundations & Consistency"),
                        InterviewRequirement.available("di-05", "DI 05 - Anti-Entropy, Transactions & Consensus",
                                "Replica repair, gossip, distributed commit, Paxos, Raft, and ZAB.",
                                InterviewMaterialType.DECK, "DI 05 - Anti-Entropy, Transactions & Consensus")
                ));
    }

    private static InterviewDomain systemDesign() {
        return new InterviewDomain(
                "system-design",
                "System Design",
                "Scalable distributed system design end to end: requirements, scale, API/data model, components, consistency, failure handling, and trade-offs, including fintech scenarios.",
                15, true, CRITICAL_GATE_MINIMUM_PERCENT,
                List.of(
                        rj("rj-04", RevolutInterviewContentPackService.RJ04_DECK,
                                "Estimation, load balancing, partitioning, replication, queues, caches, rate limiting, resilience, and observability."),
                        rj("rj-05", RevolutInterviewContentPackService.RJ05_DECK,
                                "Payments, wallet and ledger correctness, authorization, fraud and risk, reconciliation, and transaction history.")
                ));
    }

    private static InterviewDomain dddCqrsEventDriven() {
        return new InterviewDomain(
                "ddd-cqrs-event-driven",
                "DDD, CQRS & Event-Driven Architecture",
                "Bounded contexts, aggregates and invariants, domain and integration events, and CQRS/event-sourcing trade-offs.",
                8, false, null,
                List.of(rj("rj-03", RevolutInterviewContentPackService.RJ03_DECK,
                        "Bounded contexts, aggregates, invariants, event contracts, CQRS, replay, and event-sourcing trade-offs.")));
    }

    private static InterviewDomain reliabilityObservabilityJvmRoleStack() {
        return new InterviewDomain(
                "reliability-observability-jvm-role-stack",
                "Reliability, Observability, JVM & Role Stack",
                "Caching/invalidation, production debugging, JVM memory and GC tuning, failure-scenario diagnosis, and awareness of the role's infrastructure/tooling stack.",
                6, false, null,
                List.of(
                        InterviewRequirement.available("abe-07", "ABE 07 - Caching, Consistency & Invalidation",
                                "Caching strategies, stampedes, and invalidation races.",
                                InterviewMaterialType.DECK, "ABE 07 - Caching, Consistency & Invalidation"),
                        InterviewRequirement.available("abe-08", "ABE 08 - Observability & Production Debugging",
                                "Logs, metrics, traces, and thread/heap dumps.",
                                InterviewMaterialType.DECK, "ABE 08 - Observability & Production Debugging"),
                        InterviewRequirement.available("abe-09", "ABE 09 - JVM Memory, Garbage Collection & Performance",
                                "Heap generations, GC tuning, leaks, and JIT warm-up.",
                                InterviewMaterialType.DECK, "ABE 09 - JVM Memory, Garbage Collection & Performance"),
                        InterviewRequirement.available("abe-10", "ABE 10 - API & Database Failure Scenarios",
                                "Timeouts, retries, circuit breakers, and cascading failures.",
                                InterviewMaterialType.DECK, "ABE 10 - API & Database Failure Scenarios"),
                        rj("rj-06", RevolutInterviewContentPackService.RJ06_DECK,
                                "Redis, GCP, Kubernetes, Prometheus/Grafana/New Relic, Flyway, Spock, jOOQ, and container trade-offs.")
                ));
    }

    private static InterviewDomain teamFitCommunicationStar() {
        return new InterviewDomain(
                "team-fit-communication-star",
                "Team Fit, Communication & STAR",
                "Collaboration, handling uncertainty and conflict, ownership, product impact, and concise quantified STAR-format experience stories.",
                6, false, null,
                List.of(rj("rj-07", RevolutInterviewContentPackService.RJ07_DECK,
                        "Story structure, result metrics, conflict, ambiguity, ownership, feedback, influence, prioritization, and motivation drills.")));
    }

    private static InterviewRequirement rj(String id, String deckName, String description) {
        return InterviewRequirement.available(id, deckName, description, InterviewMaterialType.DECK, deckName);
    }
}

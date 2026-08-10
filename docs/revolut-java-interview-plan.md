# Revolut Java Interview — 150% Preparation Plan

> Goal: prepare beyond the published Revolut Java interview bar without wasting time relearning basic Spring Boot material that is not central to the interview.

## Source of truth

- Revolut Java interview guide: https://www.revolut.com/en-US/blog/post/how-to-ace-your-java-interview-at-revolut/
- Current relocation role (Poland, Spain, UAE): https://www.revolut.com/en-US/careers/position/software-engineer-java-relocation-to-poland-spain-or-uae-6f9aaa10-4366-4200-8294-79722110d51d/

Published interview areas:

1. Live Java coding: Java fundamentals, data structures, SOLID, testing, timed problem solving, multithreading.
2. Technical interview: concurrency, databases, indexing/query performance/transactions, distributed-system considerations, microservices, DDD, event-driven architecture.
3. System design: scalable distributed systems, performance, reliability, resilience, database performance.
4. Team fit: collaboration, uncertainty, conflict, motivation, concise quantified experience stories.
5. Role stack awareness: Java 17/21, PostgreSQL + jOOQ, Redis, GCP, Kubernetes, Grafana, Prometheus, New Relic, Spock, Flyway; lean frameworks, TDD, DDD, CI/CD.

## Learning strategy

Courses are for acquiring/refreshing concepts. CodeFit is for retaining them and converting them into interview performance.

Rule: every course lesson must produce at least one of:

- a flashcard/trade-off card,
- an implementation drill,
- a failure scenario,
- a timed coding problem,
- a system-design decision/rubric item,
- an interview story/question.

No passive binge-watching.

## Primary Udemy curriculum

### U1 — Java Multithreading, Concurrency & Performance Optimization
Michael Pogrebinsky
https://www.udemy.com/course/java-multithreading-concurrency-performance-optimization/

Priority: CRITICAL. Complete fully, then reinforce with existing JCIP decks.

Coverage: threads, synchronization, locks, executors, concurrency correctness, performance, modern concurrency concepts.

### U2 — Fundamentals of Database Engineering
Hussein Nasser
https://www.udemy.com/course/database-engines-crash-course/

Priority: CRITICAL.

Coverage: ACID, indexing, B-Trees, isolation/concurrency control, partitioning, sharding, replication, database-engine trade-offs.

### U3 — Software Architecture & Design of Modern Large Scale Systems
Michael Pogrebinsky
https://www.udemy.com/course/software-architecture-design-of-modern-large-scale-systems/

Priority: CRITICAL.

Coverage: scalability, availability, performance, architectural building blocks, APIs, distributed systems, large-scale design.

### U4 — Software Architecture & System Design Practical Case Studies
Michael Pogrebinsky
https://www.udemy.com/course/software-architecture-system-design-practical-case-studies/

Priority: CRITICAL after U3.

Coverage: repeated end-to-end system-design practice, architecture trade-offs, fault tolerance, interview process.

### U5 — Java Data Structures & Algorithms + LEETCODE Exercises
Scott Barrett
https://www.udemy.com/course/data-structures-and-algorithms-java/

Priority: HIGH. Do not necessarily consume every lecture; prioritize exercises and timed solving.

Coverage: Big-O, linked lists, stacks, queues, hash tables, trees, heaps, graphs, recursion, sorting, dynamic programming, LeetCode-style practice.

### U6 — Event Driven Microservices with CQRS, Saga, Event Sourcing
Madan Reddy / Eazy Bytes
https://www.udemy.com/course/event-driven-microservices-with-cqrs-saga-event-sourcing/

Priority: HIGH, selective where implementation becomes Axon-specific.

Coverage: CQRS, event sourcing, Saga, transactional outbox, materialized views, database-per-service, event-driven architecture.

### U7 — Fundamentals of Backend Engineering
Hussein Nasser
https://www.udemy.com/course/fundamentals-of-backend-communications-and-protocols/

Priority: MEDIUM/HIGH, selective.

Coverage: backend communication patterns, threads/processes/async I/O, HTTP 1/2/3, TLS, gRPC, proxies and network fundamentals. Useful for the extra 50%, not the first interview gate.

### U8 — Modern Java: Learn Latest Features Beyond Java 8 by Example
Pragmatic Code School
https://www.udemy.com/course/modern-java-master-all-new-features-in-java-by-coding-it/

Priority: MEDIUM, selective fast pass.

Coverage: Java 9-21 features including records, sealed classes, switch/pattern matching, text blocks and modern language evolution.

## LinkedIn Learning precision supplements

These are shorter reinforcement blocks, not a second full curriculum.

### L1 — Advanced Java: Threads and Concurrency
https://www.linkedin.com/learning/advanced-java-threads-and-concurrency

Use as a fast visual refresher for synchronization, race/data races, Future/CompletableFuture, executors, fork/join, concurrent collections, Project Loom and virtual threads.

### L2 — Advanced SQL for Query Tuning and Performance Optimization
https://www.linkedin.com/learning/advanced-sql-for-query-tuning-and-performance-optimization-22894038

High value: PostgreSQL EXPLAIN/ANALYZE, scans, indexes, join algorithms, partitioning, materialized views, PostgreSQL-specific index types.

### L3 — Strategic Monoliths and Microservices
https://www.linkedin.com/learning/strategic-monoliths-and-microservices

Use for DDD depth: EventStorming, impact mapping, bounded architecture decisions, event-driven architecture, right-sized services.

### L4 — Microservices: Asynchronous Messaging
https://www.linkedin.com/learning/microservices-asynchronous-messaging

Use for architectural trade-offs in async communication and event-driven microservices.

### L5 — Java Memory Management: Garbage Collection, JVM Tuning, and Spotting Memory Leaks
https://www.linkedin.com/learning/java-memory-management-garbage-collection-jvm-tuning-and-spotting-memory-leaks

150% coverage: GC, heap/non-heap, JVM tuning, metrics, heap dumps and memory leaks.

### L6 — Redis Essential Training
https://www.linkedin.com/learning/redis-essential-training-15012713

Role-stack familiarity: data structures, cache use, pub/sub, streams, keyspace notifications and message-bus use cases.

### L7 — Essential Google Cloud Training: Deploy, Analyze, and Secure Your Cloud Environment
https://www.linkedin.com/learning/essential-google-cloud-training-deploy-analyze-and-secure-your-cloud-environment

Role-stack familiarity only. Prioritize compute/container/GKE/network/storage concepts rather than certification-style memorization.

## Topics that courses do not get to own

These must be practiced in CodeFit because knowing definitions is not enough:

- timed live Java coding and explanation while coding,
- SOLID/refactoring under time pressure,
- writing tests during a coding exercise,
- Java Memory Model and happens-before reasoning,
- PostgreSQL transaction/isolation scenarios,
- jOOQ mental model and SQL-first data access,
- idempotency across multiple JVMs/pods,
- payment/ledger/card-authorization/fraud/reconciliation system design,
- requirements clarification and capacity reasoning,
- failure injection: retries, duplicates, timeouts, partial success, split-brain, lag, overload,
- concise STAR stories with measurable outcomes,
- answering architecture questions aloud, not just recognizing the right answer.

## 12-week aggressive sequence

### Weeks 1-2 — Java concurrency + modern Java + coding baseline

- U1 as the main course.
- L1 as reinforcement/selective revision.
- Existing JCIP decks every day.
- U5 problem solving begins immediately.
- U8 selected Java 17/21 material.
- Implement from memory: bounded executor, producer/consumer, thread-safe cache, rate limiter, idempotent in-memory registry.

Readiness gate: explain volatile/synchronized/locks/atomics/happens-before/executors/concurrent collections and debug common race/deadlock scenarios without notes.

### Weeks 3-4 — Databases + PostgreSQL + transactional correctness

- U2.
- L2.
- Existing ABE modules 2 and 3.
- Database Internals modules 1-2 selectively.
- PostgreSQL labs using EXPLAIN ANALYZE, composite indexes, covering/index-only scans, locking and isolation scenarios.
- Add jOOQ-specific exercises/cards.

Readiness gate: given a slow query or concurrency bug, produce a diagnosis plan and defend the chosen index/transaction/locking approach.

### Week 5 — Distributed systems foundation

- U3.
- U7 selected networking/backend-protocol sections.
- Database Internals distributed modules.
- Existing ABE caching/failure-scenario cards.

Readiness gate: reason cleanly about replication, partitioning, CAP-style trade-offs, consistency, availability, failure modes and backpressure.

### Week 6 — DDD + event-driven + CQRS + Saga/outbox

- U6.
- L3 selected DDD/EventStorming/event-driven sections.
- L4.
- Existing ABE modules 4 and 5.

Readiness gate: design an event-driven workflow and explain delivery semantics, idempotency, outbox, retries, DLQ, ordering, Saga orchestration/choreography and compensations.

### Weeks 7-8 — System design as an interview skill

- Finish U3 where needed.
- U4 becomes the main course.
- Minimum designs: URL/rate limiter plus payment processing, wallet/ledger, card authorization, fraud/risk pipeline, transaction history, bank reconciliation.
- Every design must cover requirements, scale, API, data model, components, consistency, failure handling, observability, security and trade-offs.

Readiness gate: complete an unfamiliar design in a timed mock without jumping to technology before clarifying requirements.

### Week 9 — Production/stack extra 50%

- L5 JVM memory.
- L6 Redis selectively.
- L7 GCP/GKE selectively.
- Existing ABE observability, JVM and failure modules.
- Refresh Docker/Kubernetes, Prometheus/Grafana/New Relic concepts, Flyway and CI/CD.

Readiness gate: explain how to diagnose latency, CPU, memory, thread-pool exhaustion, connection-pool exhaustion, cache stampede and database degradation.

### Week 10 — Live coding pressure week

- U5 exercises dominate.
- Daily timed Java problem.
- Twice-weekly backend component exercises: idempotency store, concurrent service registry/load balancer, LRU/TTL cache, task scheduler, rate limiter, ledger mutation with optimistic locking.
- Require tests and verbal explanation.

Readiness gate: clean compiling Java, tests, complexity analysis, edge cases and explanation under time pressure.

### Week 11 — Revolut fintech + behavioral

- Fintech designs only.
- Prepare at least 8 STAR stories: production incident, conflict, ownership, ambiguity, performance improvement, failed approach/learning, cross-team work, customer/product impact.
- Attach numbers/results wherever defensible.
- Practice 'why Revolut', 'why relocate', 'why this team', and fast-paced/product ownership answers.

### Week 12 — Full interview loops

Run repeated mock loops:

1. recruiter/team-fit,
2. live Java coding,
3. concurrency/database/architecture technical,
4. system design,
5. feedback -> weak-area CodeFit workout -> repeat.

Do not spend Week 12 consuming new courses unless a mock exposes a real gap.

## Application timing

Do not wait until Week 12 to apply to a currently open role. The preparation plan and application process should run in parallel. A reasonable target is to have the CV/application package ready immediately and use the first 1-2 weeks to sharpen the earliest likely gates (recruiter, live coding, concurrency) while recruiting progresses.

## CodeFit: Revolut Target Interview Profile

### Architecture decision

Do **not** create a fourth duplicated sequential training path. The target interview spans material already present in multiple paths.

Introduce a `TargetInterviewProfile` / `InterviewPreparationProfile` abstraction that can reference:

- existing training-path modules/decks,
- problem-solving categories,
- new interview-only decks,
- timed mock formats,
- weighted readiness gates.

### Reuse immediately

- JCIP 01-04 — concurrency.
- ABE 02 — transactions/locking/isolation.
- ABE 03 — idempotency/races.
- ABE 04 — Kafka/outbox/DLQ.
- ABE 05 — distributed transactions/Sagas.
- ABE 07 — caching/consistency.
- ABE 08 — observability.
- ABE 09 — JVM/performance.
- ABE 10 — API/database failure scenarios.
- Database Internals — B-Trees, recovery, consistency, transactions, consensus.
- Existing problem-solving workspace and assessment machinery.

### New Revolut-specific content only

1. `RJ 01 - Modern Java 17/21 & Core Interview` — records, sealed classes, pattern matching, virtual threads, collections/API traps, SOLID/testing prompts.
2. `RJ 02 - PostgreSQL Performance & jOOQ` — EXPLAIN/ANALYZE, index selection, joins, MVCC/Postgres details, SQL-first/jOOQ patterns, transaction boundaries.
3. `RJ 03 - DDD, CQRS & Event-Driven Design` — bounded contexts, aggregates, invariants, domain events, CQRS trade-offs, event sourcing trade-offs.
4. `RJ 04 - System Design Building Blocks` — estimation, load balancing, partitioning, replication, queues, caches, rate limiting, resilience, observability.
5. `RJ 05 - Fintech System Design` — payments, wallet/ledger, card authorization, fraud/risk, reconciliation, transaction history.
6. `RJ 06 - Revolut Stack Awareness` — Redis, GCP, Kubernetes, Grafana/Prometheus/New Relic, Flyway, Spock, jOOQ; concept/trade-off level, not trivia.
7. `RJ 07 - Team Fit & STAR` — story prompts, result metrics, conflict/ambiguity/ownership/product-impact drills.

### Daily Interview Workout

A generated session should contain:

- due spaced-repetition cards,
- one timed Java coding problem,
- one verbal/explain-it prompt from concurrency/database/architecture,
- one system-design or failure scenario on alternating days,
- a short reflection with missed assumptions/trade-offs converted into new cards.

### Timed mock modes

- Live Coding Mock — requirements clarification, implementation, tests, complexity, explanation.
- Technical Deep Dive — concurrency + DB + architecture rapid-fire/scenario questions.
- System Design Mock — requirements -> scale -> API/data -> architecture -> failures -> trade-offs.
- Team Fit Mock — STAR answer with follow-up pressure.
- Full Revolut Loop — composes all four and stores per-domain scores.

### Readiness score

Initial suggested weighting (tune from actual mock results):

- Concurrency / Java Memory Model: 18%
- Live Java coding / DSA / testing: 17%
- Databases / PostgreSQL / jOOQ: 15%
- Distributed systems / architecture: 15%
- System design interview performance: 15%
- DDD / event-driven / CQRS: 8%
- Reliability / observability / JVM / role stack: 6%
- Team fit / communication / STAR: 6%

A high overall score must not hide a failed critical domain; concurrency, coding, database and system-design gates should each have minimum thresholds.

## Definition of 'ready'

The goal is not course completion. Ready means:

- concepts can be recalled without prompts,
- code can be produced under a timer,
- design decisions can be defended aloud,
- failure modes are anticipated before being prompted,
- database/concurrency answers include correctness implications,
- fintech examples connect theory to production experience,
- weak answers automatically become future CodeFit work.

That is the 150% target.
# Advanced Backend Engineering training path

This directory contains the starter flashcard curriculum for CodeFit's **second training path**:
a track aimed at **senior backend engineers**, not beginners learning Java syntax. Where the
`Java Backend` path teaches core language, JDBC, Spring Boot, and deployment fundamentals, this
path assumes that foundation and drills the judgment calls and failure modes that separate a
junior implementation from a production-grade one: concurrency correctness, data consistency,
messaging semantics, distributed coordination, service auth, operability, and performance.

If you can already write a working CRUD service and want to reason like the engineer who gets
paged when it breaks at 3am, this path is for you.

## Content principles

Every deck in this path favors:

- **Scenario diagnosis** — "here is a production symptom, what is the cause?"
- **Trade-off analysis** — "here are two valid designs, what do you give up with each?"
- **Code/output prediction** — deterministic behavior worth knowing cold.
- **Implementation tasks** — a concrete SQL statement, command, or regex to produce.

Cards deliberately avoid acronym or annotation trivia that has no bearing on production judgment
unless that fact is a genuine prerequisite for reasoning about a scenario.

## Modules

| # | Module | Deck(s) | Cards | Prerequisites | Mastery threshold |
|---:|---|---|---:|---|---:|
| 1 | Java Concurrency & Thread Safety | `JCIP 01-04` (see `../java-concurrency-in-practice/`) | 160 | — | 80% |
| 2 | Database Transactions, Locking & Isolation | `ABE 02 - Database Transactions, Locking & Isolation` | 35 | Module 1 | 85% |
| 3 | Idempotency & Race-Condition Prevention | `ABE 03 - Idempotency & Race-Condition Prevention` | 31 | Modules 1, 2 | 85% |
| 4 | Kafka Delivery Semantics, Outbox & DLQs | `ABE 04 - Kafka Delivery Semantics, Outbox & DLQs` | 30 | Modules 2, 3 | 80% |
| 5 | Distributed Transactions & Sagas | `ABE 05 - Distributed Transactions & Sagas` | 27 | Modules 2, 4 | 80% |
| 6 | OAuth2, OIDC & Service Authentication | `ABE 06 - OAuth2, OIDC & Service Authentication` | 28 | Module 1 | 75% |
| 7 | Caching, Consistency & Invalidation | `ABE 07 - Caching, Consistency & Invalidation` | 26 | Modules 1, 2 | 80% |
| 8 | Observability & Production Debugging | `ABE 08 - Observability & Production Debugging` | 28 | Module 1 | 75% |
| 9 | JVM Memory, Garbage Collection & Performance | `ABE 09 - JVM Memory, Garbage Collection & Performance` | 27 | Module 1 | 75% |
| 10 | API & Database Failure Scenarios | `ABE 10 - API & Database Failure Scenarios` | 27 | Modules 2, 4, 7, 8, 9 | 80% |

Total starter content for modules 2-10: **259 cards**. Module 1 reuses the 160-card Java
Concurrency in Practice curriculum from `../java-concurrency-in-practice/`, so the full path ships
with **419 cards**.

Module numbers, prerequisites, and mastery thresholds are registered in code as the source of
truth: see `TrainingPathService.ADVANCED_BACKEND_ENGINEERING_PATH` in
`src/main/java/com/codefit/service/TrainingPathService.java`. Prerequisites document which earlier
modules should be reasonably solid first; mastery thresholds are the durable-mastery fraction
(from `MasteryService`, not a raw "percent of cards attempted") each module must reach before the
in-app recommendation engine treats it as ready to move on from.

## Learning objective and coverage map per module

**Module 2 — Database Transactions, Locking & Isolation.** *Objective:* choose isolation levels
and locking strategies deliberately, and diagnose lost updates, phantom reads, and deadlocks in
production transactions. *Coverage:* ACID, isolation levels and their anomalies (dirty/non-repeatable/phantom
reads, write skew), optimistic vs. pessimistic locking, `SELECT ... FOR UPDATE [SKIP LOCKED]`,
deadlock causes and lock-ordering fixes, MVCC, transaction-scope pitfalls (holding a transaction
open across a slow call, lazy-loading after close), advisory locks, and statement timeouts.

**Module 3 — Idempotency & Race-Condition Prevention.** *Objective:* design idempotent APIs and
background jobs that stay correct under retries, duplicate delivery, and concurrent requests.
*Coverage:* idempotency keys, unique-constraint-based deduplication, optimistic concurrency and
ETags, compare-and-swap and the ABA problem, distributed locks with fencing tokens, and idempotent
message consumers.

**Module 4 — Kafka Delivery Semantics, Outbox & DLQs.** *Objective:* reason about at-least-once,
at-most-once, and exactly-once tradeoffs, design transactional outbox publishing, and build safe
retry and dead-letter handling for Kafka consumers. *Coverage:* producer `acks`, idempotent and
transactional producers, offset-commit ordering, consumer-group rebalances, ISR/`min.insync.replicas`,
the dual-write problem, the transactional outbox pattern, CDC, retry topics, and DLQs.

**Module 5 — Distributed Transactions & Sagas.** *Objective:* coordinate multi-service consistency
with sagas and compensating actions instead of unsafe two-phase commit across service boundaries.
*Coverage:* 2PC mechanics and its blocking-coordinator risk, choreography vs. orchestration,
compensating transactions, TCC, saga isolation anomalies, semantic locks, and durable
orchestrator state.

**Module 6 — OAuth2, OIDC & Service Authentication.** *Objective:* apply OAuth2 grant types, OIDC
identity flows, and service-to-service authentication (mTLS, client credentials) correctly in
backend systems. *Coverage:* authorization vs. authentication, grant type selection (auth code +
PKCE, client credentials), refresh token rotation, JWT structure/signature/audience validation,
JWKS, token revocation limits, and mTLS in a service mesh.

**Module 7 — Caching, Consistency & Invalidation.** *Objective:* choose caching strategies and
invalidation approaches that keep read paths fast without serving stale or inconsistent data.
*Coverage:* cache-aside/write-through/write-behind, cache stampedes and jitter, invalidation
ordering races, local vs. distributed cache coherence, hot keys, eviction policies, and HTTP
caching (`Cache-Control`, `ETag`).

**Module 8 — Observability & Production Debugging.** *Objective:* use logs, metrics, traces, and
thread/heap dumps to diagnose production incidents quickly instead of guessing. *Coverage:* the
three pillars of observability, RED/USE methods, SLIs/SLOs/error budgets, percentiles vs.
averages, thread and heap dumps (`jstack`, `jcmd`), log-parsing regexes, runbooks, and blameless
postmortems.

**Module 9 — JVM Memory, Garbage Collection & Performance.** *Objective:* reason about heap
regions, garbage collectors, and JVM tuning flags well enough to diagnose latency spikes, memory
leaks, and throughput regressions. *Coverage:* generational heap layout, GC algorithms and
pause-time vs. throughput tuning, `OutOfMemoryError` variants, classic leak patterns (static
collections, `ThreadLocal`), escape analysis, JIT warm-up, and boxed-`Integer`/`StringBuilder`
gotchas.

**Module 10 — API & Database Failure Scenarios.** *Objective:* design and diagnose backend systems
for partial failure: timeouts, retries, backpressure, circuit breakers, and cascading outages
across APIs and databases. *Coverage:* timeout budgets and deadline propagation, backoff with
jitter and retry amplification, circuit breakers and bulkheads, load shedding, liveness vs.
readiness, database failover and split-brain, replica lag, partial fan-out failure, chaos
engineering, and fail-open vs. fail-closed trade-offs.

## Importing into CodeFit

For each TSV file:

1. Open **Decks**.
2. Create a deck using the exact suggested deck name from the table above (module 1 uses the four
   `JCIP` deck names documented in `../java-concurrency-in-practice/README.md`).
3. Select that deck.
4. Choose **Import Cards** and select the matching TSV file.

Matching the deck name exactly is what lets the Syllabus screen and the training-path
recommendation engine recognize the deck as that module. The importer skips a card when the
selected deck already contains the same prompt, so repeating an import is safe.

## How this plugs into mastery and recommendations

This path is registered in `TrainingPathService` exactly like the `Java Backend` path, so it:

- Appears on the **Syllabus** screen alongside the Java Backend modules, each tagged with its path
  name, learning objective, and mastery-based progress.
- Participates in the same `recommendNextModule` recommendation engine used on the Dashboard —
  there is no separate code path for this training path.
- Uses `MasteryService`'s durable-mastery definition (a sustained run of correct, mature-interval
  reviews) for "module complete," not a naive attempted-card percentage.
- Feeds its cards into the same due-card review queue as every other deck, so a review session
  can naturally mix a due card from an already-mastered module in with cards from the module
  you're actively working through, instead of a special-cased or path-specific queue.

## Scope note

This path does not attempt to be exhaustive. Each module ships a real, usable starter deck sized
similarly to the concurrency curriculum, covering the highest-value scenarios and trade-offs for
that topic. Teams are expected to extend it with cards drawn from their own incidents and systems
over time.

# Evolution V1 foundation

Design deliverable for [#209](https://github.com/MrH3lmy/CodeFit/issues/209), under
[epic #208](https://github.com/MrH3lmy/CodeFit/issues/208). Audited against `main`
`9cf3e5f554829300bca1346511b4bca38ce92e46` on 2026-10-15.

**Status:** foundation accepted in merged PR #219. [EV-02 skill catalog](skill-graph.md) implements
versioned definitions and prerequisite graphs for #210. [EV-03 evidence](skill-evidence.md) adds
provenance-aware observations, exact mappings and bounded local ingestion for #211.
[EV-05 catalog](quest-catalog.md) adds immutable offline quests/challenges and durable exposure for #213.
[EV-06 evaluation](challenge-evaluation.md) adds frozen submissions, bounded local checks, durable
source/evidence completion and recovery for #214. [EV-04 baseline and mastery estimates](mastery-estimation.md)
now provide conservative, versioned per-skill interpretations for #212.
[EV-07 adaptation](adaptive-quest-policy.md) adds deterministic next actions and durable learner controls
for #215. [EV-08 Player System](player-system-ui.md) connects those boundaries through an offline JavaFX UI for #216; rewards and observed learning outcomes remain future work. The original audit describes the pre-Evolution baseline;
implementation status and verification are recorded per slice. This is not evidence of learning gains.

CodeFit should help learners solve unfamiliar real problems better. Activity, XP, card familiarity,
and a passed local test are useful signals with different meanings; none alone proves that outcome.

| Deliverable | Document |
| --- | --- |
| Code-referenced reuse, reliability, gaps, protected behavior | [Existing system audit](existing-system-audit.md) |
| Responsibilities, relationships, persistence, privacy, trade-offs | [ADR: bounded local Evolution architecture](adr-0001-local-evolution.md) |
| Concepts, interfaces, events, invariants, ownership | [Domain model and contracts](domain-contracts.md) |
| Baseline → evidence → estimate → next quest, failure states | [Player progression lifecycle](progression-lifecycle.md) |
| Java Debugging & Concurrency example and evaluation design | [First vertical slice](java-debugging-concurrency.md) |
| #210–#218 dependencies, refinements, next implementation | [Implementation roadmap](implementation-roadmap.md) |
| #209 acceptance mapping, checks, limitations | [Verification](verification.md) |
| #210 implemented catalog, API, schema, tests and limits | [Versioned skill graph](skill-graph.md) |
| #211 evidence, source provenance, schema, backfill, #212/#214 APIs and limits | [Skill evidence](skill-evidence.md) |
| #213 quest/challenge catalog, original pack, hold-out isolation, exposure and #214/#216 handoff | [Quest catalog](quest-catalog.md) |
| #212 ordinal policy, coherent inputs, revisions/epochs, baseline and prospective assessment facts | [Mastery estimation](mastery-estimation.md), [verification](mastery-estimation-verification.md) |
| #215 ordered adaptive policy, coherent decisions, learner controls and UI handoff | [Adaptive quest policy](adaptive-quest-policy.md), [verification](adaptive-quest-verification.md) |
| #216 JavaFX Player System, authorized workspace, journal and recovery | [Player System](player-system-ui.md), [verification](player-system-verification.md) |
| #214 staged evaluation, checks, evidence, recovery, privacy and limits | [Challenge evaluation](challenge-evaluation.md), [verification](challenge-evaluation-verification.md) |

Read the audit before the ADR and contracts. Numerical thresholds in the lifecycle are a deliberately
conservative, versioned starting policy, to be tested in #212/#215 and evaluated in #218; they are
not calibrated probabilities or universal definitions of human ability.

Reviewed issue bodies and all available comments: #208, #209, #104, #164, #179, #210–#218.
#104 and #164 are completed inputs, not work to reopen. #179's checklist is older than current code;
its current implementation, ADR, protocol and privacy tests take precedence over guesses about status.

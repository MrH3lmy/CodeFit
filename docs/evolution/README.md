# Evolution V1 foundation

Design deliverable for [#209](https://github.com/MrH3lmy/CodeFit/issues/209), under
[epic #208](https://github.com/MrH3lmy/CodeFit/issues/208). Audited against `main`
`9cf3e5f554829300bca1346511b4bca38ce92e46` on 2026-10-15.

**Status:** proposed V1 contracts for review. This PR adds documentation only. None of the
Evolution services, tables, policies, or example results below are implemented by this PR.
Existing behavior is identified explicitly in the audit; requirements elsewhere describe proposed behavior.
Approval of this foundation precedes schema implementation in #210. This is not evidence of learning gains.

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

Read the audit before the ADR and contracts. Numerical thresholds in the lifecycle are a deliberately
conservative, versioned starting policy, to be tested in #212/#215 and evaluated in #218; they are
not calibrated probabilities or universal definitions of human ability.

Reviewed issue bodies and all available comments: #208, #209, #104, #164, #179, #210–#218.
#104 and #164 are completed inputs, not work to reopen. #179's checklist is older than current code;
its current implementation, ADR, protocol and privacy tests take precedence over guesses about status.

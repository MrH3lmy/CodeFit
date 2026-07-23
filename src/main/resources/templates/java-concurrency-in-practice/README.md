# Java Concurrency in Practice training path

This directory contains an original, paraphrased flashcard curriculum inspired by *Java Concurrency in Practice* by Brian Goetz and its coauthors. The cards summarize ideas and create new diagnostic or production-oriented scenarios; they do not reproduce the book's prose or code listings.

## Decks

| File | Suggested deck name | Chapters | Cards |
|---|---|---:|---:|
| `01-fundamentals.tsv` | `JCIP 01 - Fundamentals` | 1–5 | 40 |
| `02-task-execution-cancellation.tsv` | `JCIP 02 - Task Execution & Cancellation` | 6–9 | 40 |
| `03-liveness-performance-testing.tsv` | `JCIP 03 - Liveness, Performance & Testing` | 10–12 | 36 |
| `04-locks-atomics-memory-model.tsv` | `JCIP 04 - Locks, Atomics & Memory Model` | 13–16 | 44 |

Total: **160 cards**.

## Learning design

The deck is deliberately not a chapter-summary dump. Its card mix is:

- **108 `CONCEPT` cards** for explanation, comparison, design decisions, production diagnosis, and failure analysis.
- **46 `RECALL` cards** for terms, rules, API semantics, and invariants that should become instantly available.
- **6 `CODE_OUTPUT` cards** for deterministic API behavior where exact recall is useful.

Prompts emphasize:

- Recognizing races, visibility failures, deadlocks, starvation, livelock, and resource exhaustion.
- Explaining why a design is or is not thread-safe.
- Choosing between confinement, immutability, locks, atomics, queues, synchronizers, and executors.
- Designing cancellation, shutdown, bounded execution, backpressure, and time budgets.
- Diagnosing production behavior from thread-pool, lock, queue, and thread-dump symptoms.
- Reasoning with happens-before rather than relying on timing intuition.
- Testing safety, blocking behavior, interruption, resource leaks, and scalability.

## Importing into CodeFit

For each TSV file:

1. Open **Decks**.
2. Create a deck using the suggested name above.
3. Select that deck.
4. Choose **Import Cards** and select the matching TSV file.

The importer skips a card when the selected deck already contains the same prompt, so repeating an import is safe.

## Recommended training sequence

1. Import `01-fundamentals.tsv` first.
2. Train due cards until the fundamentals deck is stable before introducing the next deck.
3. Add `02-task-execution-cancellation.tsv`, then `03-liveness-performance-testing.tsv`.
4. Add `04-locks-atomics-memory-model.tsv` last.
5. Keep the daily new-card limit low; these cards are intentionally reasoning-heavy.
6. For `CONCEPT` cards, answer from memory before revealing, then compare meaning rather than wording.
7. When a card exposes a real gap from work, create a narrower reflection card using your own system and incident.

## Scope and version note

The book establishes foundational rules for shared mutable state, task execution, liveness, performance, testing, explicit locks, atomics, and the Java Memory Model. Those rules remain valuable on modern Java. The book predates virtual threads and structured concurrency, so this path does not claim to teach those newer APIs. They should be added as a separate modern-Java module rather than mixed into the source-derived chapter path.

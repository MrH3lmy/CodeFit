# Database Internals training path

This directory contains a **200-card, five-module deep-dive curriculum** for senior backend engineers who want to reason about what happens below SQL and across distributed database nodes.

The curriculum is inspired by the concepts and structure of *Database Internals: A Deep Dive into How Distributed Data Systems Work* by Alex Petrov (O'Reilly Media, 2019). All prompts, answers, hints, scenarios, and wording in these files are original and paraphrased for active-recall practice. The book's text and figures are not reproduced.

## Product intent

This pack applies CodeFit's product direction: give a backend engineer a guided outcome instead of a folder of loose files.

- One action in **Library → More → Install Database Internals Path** creates all five decks and imports the bundled cards.
- Reinstalling is safe: existing decks are reused and duplicate prompts are skipped.
- The Syllabus and recommendation engine treat the decks as one ordered training path.
- Cards emphasize production diagnosis, design trade-offs, and explaining system behavior rather than memorizing isolated terminology.

## Modules

| # | Module | Suggested deck name | Cards | Prerequisites | Mastery threshold |
|---:|---|---|---:|---|---:|
| 1 | Architecture, Layout & File Formats | `DI 01 - Architecture, Layout & File Formats` | 40 | — | 80% |
| 2 | B-Trees, Buffer Management & Recovery | `DI 02 - B-Trees, Buffer Management & Recovery` | 40 | Module 1 | 85% |
| 3 | LSM Trees & Storage Trade-offs | `DI 03 - LSM Trees & Storage Trade-offs` | 40 | Modules 1, 2 | 85% |
| 4 | Distributed Foundations & Consistency | `DI 04 - Distributed Foundations & Consistency` | 40 | Module 1 | 80% |
| 5 | Anti-Entropy, Transactions & Consensus | `DI 05 - Anti-Entropy, Transactions & Consensus` | 40 | Modules 2, 4 | 85% |

Total: **200 cards**.

## Learning objectives

### Module 1 — Architecture, Layout & File Formats

Trace a request through database subsystems; choose row, column, heap, hash, and index-organized layouts according to workload; reason about clustered and secondary indexes; and understand pages, slotted layouts, versioning, and checksums.

### Module 2 — B-Trees, Buffer Management & Recovery

Explain B-Tree navigation and structural changes; connect page layout to concurrency and maintenance; reason about buffer-pool eviction and dirty pages; and diagnose durability and recovery behavior through WAL, STEAL/NO-FORCE, ARIES, OCC, MVCC, and locking.

### Module 3 — LSM Trees & Storage Trade-offs

Follow writes through WAL, memtables, SSTables, and compaction; reason about tombstones, Bloom filters, read/write/space amplification, compaction policies, key-value separation, SSD behavior, and storage-stack interactions.

### Module 4 — Distributed Foundations & Consistency

Reason about partial failure, ambiguous timeouts, retries, clocks, backpressure, failure models, FLP, failure detection, leader epochs, CAP, consistency models, session guarantees, quorums, and CRDTs.

### Module 5 — Anti-Entropy, Transactions & Consensus

Explain repair and dissemination mechanisms; reason about vector clocks, gossip, two-phase commit, partitioning, coordination avoidance, atomic broadcast, Paxos, Raft, ZAB, Byzantine consensus, and recovery of replicated logs.

## Card format

Each TSV uses CodeFit's extended eight-field format:

```tsv
front	back	card_type	accepted_answers	validation_mode	hint	skill_category	time_limit_seconds
```

The pack intentionally uses mostly `CONCEPT` cards with production-facing explanations and a smaller set of `RECALL` cards for terminology that must be available quickly.

## Manual import

The one-click installer is recommended. For manual import:

1. Create a deck using the exact suggested deck name.
2. Open the deck in Library.
3. Choose **Actions → Import Cards**.
4. Select the corresponding TSV file.
5. Import modules in numeric order.

## Scope

This is a deliberate learning pack, not a replacement for the book. It focuses on concepts that transfer to backend design reviews and production incidents. Learners should use the original book, database documentation, source code, and papers for deeper implementation detail.

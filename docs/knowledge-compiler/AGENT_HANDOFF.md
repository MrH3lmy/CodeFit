# Agent handoff: Knowledge Compiler v0.2

**Read first:** README.md, source_coverage_v0.2.json, foundations_v0.2.json, equality_v0.2.json.

## Scope and safety

- This branch is a **knowledge-design checkpoint**; none of the content is released to learners.
- Do not implement a learner-facing feature or grant XP/mastery based on any draft without explicit subsequent review.
- Do not re-extract books by assuming Copilot has the Google Drive connector; it does not inherit ChatGPT's source permissions.
- Do not upload, print or commit copyrighted source PDF contents, tables, images, original exercises, solutions, private links or credentials to this public repository.
- The original PDF data is held separately by the user. Local extraction code works offline with these provided copies.
- Maintain immutable, versioned source IDs. A chapter reference is not sufficient verification for a contained claim.
- Do not edit files changed by ongoing #240 progression, #218 pilot or #221 catalog performance work.
- The '504 discovered' metric is **major index coverage**, NOT detailed knowledge extraction coverage.
- File `foundations_v0.2.json` contains six human-readable source-linked drafts and 24 blueprints; `equality_v0.2.json` adds one capsule and seven blueprints.

## Next bounded engineering task

1. Design and validate the missing fine-grained source registry schema: source -> major unit -> nested unit -> unique learning objective -> evidence -> assessment. Every record needs stable ID, source locator, Java-version qualifier, status, human review flag, duplicate relationship and exclusion reason.
2. Create a dry-run validation command that rejects orphan pointers, false '100%' claims, invalid prerequisite cycles, duplicate assessment families, and premature mastery permissions.
3. Make an extraction checkpoint idempotent, restartable and auditable. Keep private PDF parsing local and avoid raw-source text in GitHub.
4. Expose a machine-readable coverage report with separate `discovered`, `extracted`, `mapped`, `reviewed`, `assessed`, `verified` statuses per unit.
5. For first learner experience, create an original micro-lesson with a code runner and positive/negative tests for First Program only **after** a human-approved content review; keep a distinct unseen assessment family.

## Non-goals

- No merging, promotion, auto-scoring of free-text interview responses, reward policy changes or retroactive mastery.
- No claim that the five books can be deeply mastered in seven days.
- No production access required from raw privately owned source books.

## Checkpoint procedure

After every extraction batch, update the source coverage ledger, add a dated CHANGELOG entry containing counts and failures, run tests, and commit a resumable snapshot to a dedicated branch. Never count same source anchor twice.

The user goal is rapid yet genuine interview preparation with a compelling Solo Leveling-inspired UX; evidence-based mastery remains more important than artificial XP acceleration.

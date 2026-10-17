# CodeFit Knowledge Compiler — extraction checkpoint v0.2

**Status:** EARLY EXTRACTION / CURATION DRAFT; not a shipped syllabus and not eligible for player XP or verified mastery.

This branch stores a **copyright-safe agent handoff** for work on the five source books. Original PDFs, extracted passages, code samples, solutions, illustrations, full source headings, Google Drive links, credentials and proprietary fixtures MUST NOT be committed to this public repository.

## Evidence-backed inventory

- OCP Java SE 17 Developer Programmer's Guide: 26 major chapters
- Java Coding Problems: 269 numbered problems
- 100 Java Mistakes: 100 mistakes
- Effective Java (third edition): 90 items
- The Complete Coding Interview Guide in Java: 19 major chapters
- **504 / 504 major units discovered** and **1,334 native PDF bookmarks indexed** across the private originals.
- **7 draft canonical knowledge capsules**, including the existing equality/hash capsule.
- **31 original question/mission blueprints** (including 7 from the earlier equality prototype).
- **43 distinct major source units linked** to these draft capsules.
- Existing Python tests: 6 passing; standalone Java 21 equality smoke checks: 11 passing.
- **0 independently reviewed capsules; 0 production graders; 0 validated mastery outcomes.**

Book-level discovery **does not** mean complete subsection, example, figure, code, question, or semantic coverage. Major units spanning multiple concepts must be broken down further. Mapping also does not mean technical validation.

## Six-stage workflow

1. **Extract** immutable unit IDs with page locators and outline hashes, then audit nested sections, code snippets, tables, figures and tasks with private-source access.
2. **Understand** write original explanations, caveats, misconceptions and prerequisite objectives with source pointers.
3. **Connect** map multiple sources to a canonical DAG; never overwrite historical skill versions.
4. **Design** use Predict → Debug → Implement → Explain → Unseen checks, with beginner scaffolding and gradual withdrawal.
5. **Verify** technical source review, Java 21 executable checks and negative controls, learner/pedagogical review and privacy licensing.
6. **Publish** only approved original specs to the runtime; maintain a repeatable ledger and fail closed on unresolved content.

## Handoff artifacts

- `source_coverage_v0.2.json`: book and draft coverage counts, deliberately conservative statuses.
- `foundations_v0.2.json`: six original foundational teaching capsules and mission blueprints.
- `equality_v0.2.json`: one cross-source draft capsule and review constraints.
- `AGENT_HANDOFF.md`: implementation boundaries, verification policy and next steps.

## Development boundary

This branch MUST remain distinct from parallel PR #240 (XP), issue #218 (pilot protocol) and issue #221 (SkillCatalog performance). Do not merge directly. Review contents, tests and copyright safety before integration. Do not expose authored drafts to learners or award XP/mastery. Copyrighted book text stays in the private source environment.

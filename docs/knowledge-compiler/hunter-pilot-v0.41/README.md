# Knowledge Compiler → Hunter: v0.41 metadata-only pilot

Original author-side metadata only, prepared from the verified private v0.41 archive (parent v0.40): Effective Java Items 57–60, four skills, sixteen stage IDs. No publisher PDF text/code, exercise prompts, solution keys, private Drive URLs, reviewer signing material or learner data.

**Release status: UNREVIEWED.** Zero independent content approvals, production graders, Hunter imports, or verified learner mastery. Source SHA-256 is a source-section locator, not evidence of semantic accuracy. Preview metadata is not a deployable mission catalog.

## Copilot continuation for draft PR #271 / Issue #266

1. Inspect Java 21 SkillRef/GraphRef catalog and exposure/auth contracts. Map four skill IDs and prerequisites to pinned graph versions only where actual mappings exist; report gaps without guessing.
2. Implement strict versioned offline read-only schema/adapter validation: duplicate IDs, malformed identifiers, unsupported version, cycles, missing external prerequisites, content/status tampering, and immutable revision checks fail closed. Do not modify live SQLite data.
3. Expose only preview-safe metadata via typed Java IPC to React/Electron under #261; no restricted prompts/answers in renderer static assets, logs, exports, or P2P.
4. Add positive/negative unit/integration tests and no-XP/reward/mastery-mutation checks. Preserve JavaFX fallback and align with web-desktop epic #256 and approved UI gate #259.
5. Leave PR as **draft and unmerged** with exact tests and remaining blockers. Independent review (#253), private full mission briefs, actual content approval and grader rollout (#269) remain separate explicit gates.

Archive SHA-256: a4a1e168f2b00c26677e7f75052d5585cf4425f1641af697dd7927a7ae25e865. Book Java9-era guidance needs independent JDK21 verification. These 16 stage IDs replace drafts; they do not add unique blueprint slots or imply approvals.

Related: #266 #267 #268 #269 #270 #253 #256 #261.

## Catalog boundary and mapping audit

The supplied preview is the only authorized input for this slice. Its checkpoint
hash records author-supplied provenance; this repository does not contain or
independently reverify the private archive. In particular, the archive hash is not
the distributable preview's hash and does not establish semantic accuracy.
No v0.40 remote backup is asserted.

The existing `JavaDebuggingSkillSeed.graph()` pins
`GraphRef("java-debugging-concurrency", 1)` and defines only
`java.debugging.reproduce`, `java.concurrency.atomic-update`,
`java.concurrency.invariant`, and `java.concurrency.liveness`, each at definition
version 1. None is an exact match for the pilot or its external prerequisites.
Do not substitute related-sounding concurrency skills or strip `.v1` from compiler
identifiers. A suffix in a compiler ID is not a verified CodeFit definition version.

| Compiler skill ID | Mission IDs (each suffix is a distinct stage) | Declared prerequisites | Existing SkillRef / GraphRef |
| --- | --- | --- | --- |
| `java.general.local.scope.v1` | `EJ41-057-{PREDICT,DEBUG,BUILD,DEFEND}` | `java.control.flow`, `java.exceptions.checked` | Unresolved / unresolved |
| `java.general.enhanced.iteration.v1` | `EJ41-058-{PREDICT,DEBUG,BUILD,DEFEND}` | `java.general.local.scope.v1` (internal), `java.collections.iteration` | Unresolved / unresolved |
| `java.general.library.selection.v1` | `EJ41-059-{PREDICT,DEBUG,BUILD,DEFEND}` | `java.numeric.integer.overflow`, `java.api.contracts` | Unresolved / unresolved |
| `java.general.exact.decimal.v1` | `EJ41-060-{PREDICT,DEBUG,BUILD,DEFEND}` | `java.numeric.integer.overflow`, `java.api.preconditions.v1` | Unresolved / unresolved |

For every row:

- Objective: the supplied original `title`, not a newly authored learning claim.
- Source locator: the corresponding `EJ-ITEM-057` through `EJ-ITEM-060` and
  `source_section_sha256` in the pinned input. These are locators, not approvals.
- Exposure: `PUBLIC_PREVIEW` metadata only. `AUTHORIZED_CHALLENGE` prompts/hints
  and `RESTRICTED` solutions/grader keys are absent and unsupported.
- Review: author draft, projected as `UNREVIEWED`, with zero independent approvals.
- Java: Java 9-era guidance, Java 21 host; compatibility remains independently
  unverified. No code execution or compiler compatibility claim is made.
- Difficulty: not provided; unknown, not inferred.
- Renderer boundary: immutable preview DTOs only, never an executable
  `ChallengeSpec`, evidence mapping, recommendation or mastery estimate.

Items 57–60 were selected by the author in the supplied handoff, not independently
selected or approved by the adapter. The sixteen IDs replace draft stage slots;
they are not sixteen additional approved missions.

The adapter must not call `PlayerSystemService.overview()` or adaptation/reward
reconciliation to obtain previews: those are mutating operations, not read APIs.
It does not seed `SkillCatalogRepository` or `QuestCatalogRepository`, initialize
SQLite, or register a desktop IPC command. Stable Java DTOs prepare a future
allowlisted host integration under #261 without expanding the currently approved
IPC command matrix, changing the Electron host decision, or shipping new React
static content.

### Remaining release gates

Loading preview metadata does **not** satisfy production import acceptance.
Exact versioned graph mappings and all six distinct external prerequisites remain
unresolved. A deployable import must fail closed until an independently reviewed
mapping and prerequisite DAG are supplied. Full original mission briefs,
challenge authorization, independent review under #253, JDK21 verification,
approved difficulty, production graders under #269, and explicit host exposure
remain blocked/deferred. No approval, grading, XP, rewards or mastery eligibility
may be inferred from a valid checksum or a successfully loaded preview.

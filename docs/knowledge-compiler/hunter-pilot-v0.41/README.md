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

## Versioned input schema

`KnowledgePreview.loadBundled()` is the application entry point. It reads only
the fixed Java classpath resources; there is no URL, caller-selected classpath
path, database importer, filesystem watcher or fallback revision. The CLI is a
separate developer build tool, not a renderer-accessible command.

Input is UTF-8 JSON, at most 32,768 bytes, nesting depth 8, 2,048 parsed values,
512 characters per parsed string and 16 characters per number. JSON integers,
booleans, arrays and objects are checked without coercion. Nulls, floats,
duplicate keys, trailing input, missing fields and extra fields are rejected.
The following field sets are exhaustive, including in nested objects:

| Object | Required fields and constraints |
| --- | --- |
| Root identity | `schema`: `codefit-curriculum-preview/0.1`; `source_version`: `v0.41`; `private_checkpoint_sha256`: lowercase 64-hex locator; `source_kind`: `original_author_metadata_only` |
| Root restrictions | `production_import_supported`, `grader_available`, `xp_enabled`, `mastery_enabled`, `source_body_included`: literal `false`; `independent_approvals`: integer `0`; `prerequisite_mapping_status`: `UNRESOLVED` |
| Root catalog | `skills`: exactly four skill objects |
| Skill identity | `skill_id`: canonical lowercase dotted/hyphenated ID, at most 128 characters; `title`: nonblank, at most 256 characters; `source_item`: one of `EJ-ITEM-057` through `EJ-ITEM-060`; `source_section_sha256`: lowercase 64-hex locator |
| Skill restrictions | `review_status`: `AUTHOR_DRAFT_PENDING_EXTERNAL_REVIEW`; `release_eligible`, `gradable`, `mastery_eligible`: literal `false` |
| Skill relationships | `prerequisites`: unique canonical IDs, at most sixteen; `mission_stages`: exactly one of each `PREDICT`, `DEBUG`, `BUILD`, `DEFEND` |
| Stage | `stage`: one of the four labels; `id`: `EJ41-<matching three-digit source item>-<stage>` |

Skill IDs, source items and mission IDs are unique. Internal prerequisite
relationships must be acyclic and refer to present skills. Unmapped external
prerequisites remain explicit blockers rather than being created, dropped or
silently accepted as satisfied. The exact input bytes must additionally match
the compiled-in revision pin and bundled checksum manifest. Consequently, even
otherwise schema-valid changes to a title, locator, prerequisite, ordering,
whitespace or review claim cannot replace this revision.

Validation builds an immutable snapshot in memory and returns nothing on
failure; repeat successful loads are equal and have no persistence side effects.
A new content revision requires a new reviewed resource/pin and explicit mapping
decision, never in-place updates to historical SkillRef/GraphRef definitions or
approval carry-over. No approval store is introduced here.

Diagnostics are fixed `PreviewFailure.Code` values only, with no parser exception
causes, input snippets, source text, private URI, filesystem paths or hashes.
Successful CLI checksum output identifies only the public preview artifact.

The separate renderer-safe DTO schema is
`codefit-public-knowledge-preview/0.1`. It carries `revision` and
`revisionSha256` (the public input pin), `PUBLIC_PREVIEW`, `UNREVIEWED`,
`JDK21_VERIFICATION_PENDING`, the Java-era caveat, and explicit disabled
`productionImportSupported`, `graderAvailable`, `xpEnabled`, `rewardsEnabled`
and `masteryEnabled` flags with `independentApprovals: 0`. Skills contain only
IDs, titles, public source item/section locators, prerequisite IDs and stage IDs.
Exact `mappings` are empty for this handoff; `blockers` identifies four unmapped
skills and six distinct unresolved external prerequisite IDs. The private
checkpoint hash is not projected into the DTO or export.

## Reproducible build and verification

Run from the repository root with JDK21. No private archive or network content
fetch is required; Maven dependencies must already be cached for an offline build.

```sh
cd /home/runner/work/CodeFit/CodeFit
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
mvn -B -Dtest=KnowledgePreviewTest test
mvn -B -DskipTests package
CP="target/classes:$HOME/.m2/repository/com/fasterxml/jackson/core/jackson-core/2.22.4/jackson-core-2.22.4.jar"
java -cp "$CP" com.codefit.knowledge.KnowledgePreviewCli build \
  /home/runner/work/CodeFit/CodeFit/docs/knowledge-compiler/hunter-pilot-v0.41/preview-catalog.json
java -cp "$CP" com.codefit.knowledge.KnowledgePreviewCli export \
  /home/runner/work/CodeFit/CodeFit/docs/knowledge-compiler/hunter-pilot-v0.41/preview-catalog.json
cd /home/runner/work/CodeFit/CodeFit/target/knowledge-preview
sha256sum -c public-preview.json.sha256
```

Adjust `JAVA_HOME` to a local JDK21 installation on other machines. The classpath
separator above is for Unix. For a fully cached Maven build, add `-o`; an initial
dependency download is not part of catalog loading.

The input SHA-256 is
`76377d04ecb0ff45690df32bbc001f66bfaa672e1935327e1c09b2b047a46f52`.
The deterministic public DTO artifact SHA-256 is
`95ff42f24a88ac85400a4cda43daf9e0caabab8e4e324fa21c992f78a2ecb874`.
The classpath `preview-catalog.json` is byte-identical to the supplied input and
has its own checked `preview-catalog.sha256` manifest. A schema-valid edit is
still rejected unless it matches the compiled-in pin.

`build` validates and prints the deterministic public artifact checksum without
writing files. `export` writes only the allowlisted public DTO and checksum under
the ignored `target/knowledge-preview/` build directory. Neither command prints
the input document, private checkpoint locator, prompts, solutions or learner
state. The public DTO is not a round-trippable mission import format.

The export publishes the JSON/checksum pair together using an atomic directory
move from `target/.knowledge-preview-staging`; unsupported atomic moves fail
closed. Identical existing output is a no-op; incomplete, changed or redirected
output is refused rather than overwritten. An interrupted staging directory is
not trusted or automatically reused. With no export process running, remove
that ignored build-only staging directory before retrying; never use this
procedure on a learner profile.

### Implementation inventory

Paths below are relative to `/home/runner/work/CodeFit/CodeFit/`:

- `src/main/java/com/codefit/knowledge/KnowledgePreview.java`: bounded validator,
  fixed resource pin and exact graph lookup.
- `src/main/java/com/codefit/knowledge/PublicPreview.java`: immutable preview DTO.
- `src/main/java/com/codefit/knowledge/PreviewFailure.java`: redacted error codes.
- `src/main/java/com/codefit/knowledge/KnowledgePreviewCli.java`: deterministic
  developer build/export entry point.
- `src/main/resources/knowledge/hunter-pilot-v0.41/preview-catalog.json` and
  `preview-catalog.sha256`: pinned original-only author metadata and manifest.
- `src/test/java/com/codefit/knowledge/KnowledgePreviewTest.java`: positive,
  tampering, bounded parsing, graph blocker, immutable replay, resource corruption,
  safe export and non-mutation boundary regressions.
- This README: schema, mapping audit, reproducibility and unfulfilled release gates.

No existing graph/catalog, SQLite schema, evidence, grading, XP/reward, mastery,
recovery, P2P, JavaFX or desktop IPC implementation is modified.

### Regression evidence

With JDK21, the complete existing Java regression command
`xvfb-run -a mvn -B test` passed **1,881 tests with zero failures, errors or
skips** after the explicit preview capability/locator DTO changes.
`mvn -q -DskipTests package` also passed. The focused tests additionally exercise
the CLI build/export subprocess and compare pinned bundled bytes and safe output.
These checks validate the adapter, not the semantic accuracy of book-derived
claims or the readiness of a grader.

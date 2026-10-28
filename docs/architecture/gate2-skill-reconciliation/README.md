# Explicit pilot skill reconciliation (#281)

## Audit before implementation

- `MasteryEstimateService.capturePilot` reads one caller-owned snapshot, exact installed graph and available pinned mappings. `prepare` runs the existing ordinal policy/codec outside transactions. `publish` saves one estimate on a caller-owned validated writer transaction. `pilot()` publishes separately per skill and is **not** the atomic entry point for this command.
- `EstimateRevisionRepository.save` deduplicates exact policy/input bytes; epoch reset is a separate write and is excluded. Inputs contain metadata and evidence references, not source artifacts.
- `EstimateInputReader` pages effective evidence and checks evaluation context on one connection. Missing mappings reduce support; this adapter additionally rejects incompatible/superseded installed mappings instead of silently selecting versions.
- `EvidenceRepository`/mapping reads are reused; append, correction and mapping publication are excluded.
- `LearnerSkillProjection` is a bounded historical read, not a publication entry point. Debrief remains explicitly refreshed and read-only.
- `AdaptiveQuestService.recommend` publishes estimates and decisions; `recommendCurrent` also reconciles milestones. `PlayerSystemService.overview` invokes recommendation and effort-reward reconciliation. None are called.
- `ExperimentalProfile` permits only marked, locked Linux disposable practice profiles. Additive desktop-only metadata is installed after its existing schema/integrity checks; SchemaMigrator and real learner databases remain untouched.
- Electron main owns fsynced metadata-only intent and exact command identity. Session authorization, exclusive owner, bounded transport and generation fencing remain authoritative.

## Transaction and recovery choice

The command first durably accepts its immutable identity and Java as-of time. It then captures coherent inputs, computes through `prepare` outside a transaction, acquires the writer lock and validates the original same-connection `data_version`. All four `publish` calls, revision references and terminal receipt commit together. No partial publication. No automatic retry/re-capture after evidence changes.

An accepted command interrupted before publication has **no estimates published by that command**. Query-only status reports INTERRUPTED; it never reinterprets later evidence under that identity. The learner reconciles that original command and must explicitly confirm a new command to use a fresh input cut. After commit, replay returns the exact saved revision IDs, inputs/digests and as-of without publishing again. A command absent from Java is NOT_FOUND, not permission to recreate an ambiguous old input cut. This intentionally conservative terminal-abort design avoids persisting another copy of policy inputs or reconstructing lost inputs after restart.

Only estimate revisions and desktop command/receipt correlation metadata may change. No rewards, preferences, recommendations, exposures, drafts, evaluations, evidence or XP writes are authorized.

## API and privacy boundary

The v1 renderer bridge is `practiceSkillAction(action, profileId, sessionId)`, where action is exactly `read | publish`. The owning main frame is checked on the finite `codefit:practice:skills` channel; extra arguments fail. The renderer cannot supply graph, time, mappings, policy, command ID or evidence.

Main issues only these private Java protocol methods (all include `v`, transport `id`, exact `profileId` and current `sessionId`):

| Method | Additional fields | Effect |
| --- | --- | --- |
| `practice.skills.state` | none | Read latest accepted command/receipt |
| `practice.skills.status` | `commandId`, `originSessionId` | Read the original command, including across process restart |
| `practice.skills.reconcile` | `commandId`, `originSessionId` | Explicit new publication; origin must equal current session |

`PracticeSkillsProtocol` writes a manual JSON allowlist; `practice-skills-contract.ts` strictly validates responses. Each response identifies graph `java-debugging-concurrency` v1, policy `ordinal-v1`, four exact public skill labels, and a nullable reconciliation. A reconciliation identifies command/origin, Java UTC `asOf`, state/reason and input digest. PUBLISHED returns four exact saved revision references, per-input digests, epoch, ordinal band, support, freshness **at asOf**, contradiction/regression flags, independent-family/exclusion counts, behavior indices and static policy explanations. No raw evidence, contribution IDs, source, notes, evaluator reports, hints or protected catalog assets cross this boundary. Unknown/extra fields, inconsistent status, mismatched command identity and oversized frames fail closed. Existing 512 KiB response bounds, deadlines, cancellation, child supervision and profile/session fencing apply.

Three additive immutable metadata tables live only in the locked experimental learning profile: `desktop_skill_commands`, `desktop_skill_receipts`, and `desktop_skill_results`. The last references existing `evolution_estimate_revisions`; there is no new estimate/evidence store. Exact DDL is verified on restart. Real-profile migrations and `SchemaMigrator` are unchanged. Existing JavaFX policy and repositories retain their semantics; the caller-connection estimate lookup is additive.

Main fsyncs only `{v, profileId, commandId, originSessionId, operation}` before dispatch. Pending intent recovery always queries the original command, even if the user presses Publish. A verified status clears the pending intent. Lost replies after main clears it remain discoverable through the latest Java receipt. Cancellation can interrupt transport after commit: it is not a promise to undo publication. The next explicit read determines the durable outcome.

## Input validation and concurrency

The installed graph must equal the published Java pilot graph; a newer published graph or superseding selected mapping is rejected pending an adapter review. Missing mappings, unavailable interpretations, future epochs and clock rollback fail closed. Input capture uses the existing effective-evidence reader, evaluator lookup and correlation/withdrawal policy. The adapter adds corpus bounds (1,000 artifact-bearing records/table, 2 MB per artifact, 16 MB total/table, 10,000 metadata records, 1 MiB encoded input per skill). These are deliberate experimental-profile limits, not a claim to support arbitrarily large histories.

The service serializes commands within its owner. The exclusive profile lock excludes cooperating writers; SQLite's writer lock plus same-connection `data_version` catches intervening commits from another connection. A changed source, graph, mapping or epoch cannot yield a partial batch. A second explicit command may create a new as-of interpretation; identical policy/input bytes reuse existing revisions. Replaying the original command never creates a revision.

## Interface and limitations

The Practice Workspace mounts a session-keyed panel without calling any policy. The learner first reads/reconciles saved state, then confirms the four exact skills before publication. Responses update the panel only after acknowledgement. Unknown completion disables new publication until an explicit status read. Disconnect/unmount fences delayed responses. All results are labeled historical; later evidence can change support. Refreshing Learning Debrief remains an explicit read. The consented real-learner Ascension snapshot is unchanged.

The UI inspects the latest command; earlier immutable receipts remain in Java storage but do not yet have a renderer history browser. A prepublication interruption is terminal for that command, rather than resumed computation. Reconciliation does not advance a band without the existing policy's required independent evidence. Only Linux disposable practice profiles are supported; real profiles, cross-platform writers, automatic reconciliation and background polling remain disabled.

A later recommendation/reward slice must separately audit and explicitly authorize `AdaptiveQuestService` and `ProgressionService` side effects. This PR does not call them or change their policy.

## Verification and adversarial review

The new isolated Java tests exercise no/assisted/sufficient evidence, withdrawn and corrected results, staleness, contradictory failures, superseded mappings, missing mappings, graph conflict, oversized metadata, clock rollback, concurrent input changes, all publication crash boundaries, command conflict, replay and restart. Whole-database comparisons allow changes only in estimate revisions and the three command metadata tables.

Node integration tests exercise real Java receipts, strict schemas, lost requests/replies, restart and delayed cross-session responses. Packaged Electron tests verify explicit confirmation, double-click protection, durable restart, read-only status, late-response fencing, unauthorized frames, argument validation and keyboard interaction. Database hashes prove unchanged drafts, evidence, evaluations, rewards, exposures and recommendations. A sentinel real-profile file remains untouched.

Security review: publication is inaccessible through snapshot APIs; no arbitrary query/path/graph is accepted; no private artifact is serialized; immutable receipts retain their original as-of and do not imply current support. Unknown completion is reconciled without redispatch. Source changes fail rather than silently recapturing. Withdrawal affects a new estimate through the existing engine and does not rewrite historical receipts. Local unencrypted disposable profile data retains the existing filesystem privacy boundary; this feature does not add encryption or protect against a malicious process with the same OS account.

Packaged screenshots use an empty synthetic profile and contain no challenge instructions or learner source:

- [1280 dark](screenshots/skills-1280-dark.png)
- [1280 light](screenshots/skills-1280-light.png)
- [760 dark](screenshots/skills-760-dark.png)
- [760 light](screenshots/skills-760-light.png)

### Reproducible checks

Run with Java 21 and isolated test databases:

```sh
mvn -B -Dtest=PracticeSkillsIntegrationTest test
xvfb-run -a mvn -B test
cd desktop
npm run build
npm run prepare:java
npm test
npm run package
CODEFIT_DESKTOP_EXECUTABLE="$PWD/release/CodeFit-Gate0-linux-x64/codefit-gate0" xvfb-run -a npm run test:e2e
npm audit
```

The focused Java suite passes 15 tests; Node passes 89; focused packaged Electron passes 3. The PR records final full-suite counts and CI links on its exact commit. `npm audit` reports one pre-existing low-severity esbuild development-tool advisory; no dependencies or lockfile changed. Visual inspection confirms readable light/dark cards at 1280 and 760 pixels, with single-column narrow layout and visible keyboard focus. Initial local Node invocation omitted fixture `JAVA_HOME` and overlapped execution tests; the complete correctly configured standalone rerun passed without changing assertions or deadlines.

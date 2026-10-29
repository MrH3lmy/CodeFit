# Skill Analytics — historical, query-only Java projection (#283)

## Side-effect audit before implementation

`EstimateRevisionRepository.find(Connection, revision)` validates the saved input checksum, policy and exact identity before replaying the pure ordinal policy over the **saved** input. It neither captures current evidence nor publishes a revision. `EstimateCodec` contains metadata and references, not artifacts. `EvidenceRepository.find` validates canonical evidence identity; source heads identify subsequent corrections/withdrawals. Mapping rules associate observations with exact skills, not behavior-level proof. `DurableEvaluationRepository` owns frozen submissions and reports; analytics does not hydrate or expose those private artifacts. A persisted evidence observation is not fresh re-verification of its source.

`LearnerSkillProjection` already bounds graph hydration and reports historical source/mapping/epoch warnings. The adapter reuses these validations, without using its latest-only projection as a history store. Three helpers become package-visible; their behavior is unchanged. `PracticeSkillsService` publishes estimates and receipts only on explicit confirmation and is excluded from analytics. `PracticeEvaluationService` has read-only debrief paths but also prepare/resume/cancel writes: none are needed here. `MasteryEstimateService.estimate/pilot`, `AdaptiveQuestService`, `PlayerSystemService.overview` and progression reconciliation are not called.

The adapter opens only the already authorized, locked Linux experimental practice profile's query-only connection. One read transaction captures graph, saved interpretations and canonical observation metadata. No schema, journal, cache or learning-state writes are added. Connecting an experimental profile retains its existing explicit initialization/ownership contract; the analytics read itself initializes nothing. The real-learner snapshot remains an entirely separate identity and data source.

## Exact API and transaction

Renderer bridge: `readPracticeAnalytics({ page, snapshot }, profileId, sessionId)`.

Owning-main-frame channel: `codefit:practice:analytics`, exactly three arguments. Java command:

```json
{"v":1,"id":"request UUID","method":"practice.analytics.read","profileId":"profile UUID","sessionId":"session UUID","page":0,"snapshot":""}
```

The existing live owner checks authorization, identity, busy state and generation; the supervised Java process checks the exact active session again. No renderer paths, SQL, skill selectors, curriculum IDs or execution options are accepted. Requests use the existing deadline, cancellation, child cleanup and 512 KiB response limit. Errors use the existing redacted practice vocabulary.

`PracticeAnalytics.read` opens `ExperimentalProfile.connection(true)` (`query_only=ON`), begins a coherent read transaction, verifies the exact published pilot graph, validates input bounds, fingerprints relevant persisted inputs, loads one page, and closes the transaction. No write connection, evaluation, estimate publication or reconciliation occurs. SQLite query-only reads neither migrate nor initialize a profile. This is a point-in-time capture, not a live stream.

Response schema: `desktop/src/analytics-contract.ts`; Java serialization is an explicit `JsonGenerator` allowlist, never generic domain serialization. The success envelope adds:

- `capturedAt`, SHA-256 `snapshot`, `page`, `more`.
- Exact `graphId: java-debugging-concurrency`, `graphVersion: 1`, four versioned definitions with names and observable behaviors, three prerequisite edges.
- Up to 16 `revisions`: saved numeric ID, exact skill/graph versions, policy, UTC `asOf`, epoch, `HISTORICAL | INCOMPATIBLE | UNAVAILABLE`, warning codes. Available interpretation contains categorical band/support/freshness, contradiction, independent-family count, behavior coverage, Java reason codes/messages, exclusion codes and up to 64 contributing evidence IDs with explicit truncation.
- Up to 32 `events`: canonical evidence ID/root/revision/sequence, recorded and optional observed UTC timestamps, original/supersession/retraction, provenance/outcome/assistance/exposure/availability enums, persisted source-head flag and exact mapped skill IDs.

No source, draft, reflection, answers, checks, expected outputs, evaluation artifacts, P2P credentials or arbitrary persisted free text are serialized. Explanation messages come from the existing pure Java ordinal policy, not user-authored payloads.

### Bounds and page consistency

Page zero requires an empty digest and explicitly refreshes. Pages 1–63 require the prior digest. The digest covers estimate revisions/epochs, evidence observations/heads/interpretations, mapping definitions and mapping rules on the same read snapshot. Any change invalidates pagination with a redacted error; the user refreshes explicitly. Pages replace rather than concatenate incompatible snapshots. Returning to page zero is labeled a refresh.

Limits are 1,024 estimate revisions, 2,048 rows per other fingerprinted table, 32 columns, 1 MiB per value and 16 MiB total input bytes. SQLite size queries run before Java hydrates each value. Above these limits the read fails closed; there is no silent whole-history truncation. Results are explicitly paged; skill/date filters and comparison apply to the current page only. Evidence reference lists alone have an explicit 64-item truncation indicator. Larger-history indexing is future work, not an unbounded startup scan.

## Five visualizations and source semantics

| View | Persisted source and meaning | Interaction |
|---|---|---|
| Ordinal timeline | Saved revision `asOf` and Java band; discrete categorical events, no interpolated growth | Skill/date filters, selectable events, accessible revision ledger |
| Evidence timeline | Canonical observations ordered by actual recorded timestamp/sequence; correction/retraction retains its root | Select event; inspect source time separately, provenance, assistance, familiarity and availability |
| Behavior coverage matrix | Exact pilot observable behavior and saved policy support/contradiction/exclusions | Select skill/revision; unavailable interpretations remain unavailable |
| Prerequisite DAG | Exact published v1 graph only; edges express prerequisites, not earned unlocks | Keyboard node selection, arrow/Home/End focus, zoom and native pan, textual outline |
| Revision comparison | Two available same-skill/graph/definition/policy interpretations | Select revisions side by side; stack on narrow screens; disclose different epochs |

Every estimate is **historical**. Saved `CURRENT` freshness means current at its saved `asOf`, never current today. Head/mapping/epoch warnings are added without recalculating the band. Withdrawn support does not erase the historical record, unlock a node, or turn a former positive interpretation into a new current claim. Incompatible versions remain visible as metadata without invented bands. Corrupt revision input becomes unavailable; invalid shared graph/header/timestamp metadata rejects the read.

Evidence events are persisted observations, not a synthetic stream of exposures, attempts or mapping changes. Corrections and withdrawals are not independently counted successes. An event marked source head means the persisted canonical head; its underlying mutable source is **not freshly reverified**. Unmapped events are explicitly visible without fabricating skill associations. Activity times disclose ingestion separately from authentic source-event timestamps.

## UI, privacy and recovery

The new navigation destination uses existing typography, theme tokens and native React/SVG controls. No chart or other dependency was added. Native buttons/selects, visible focus, chart legends, tables/outlines and status messages provide keyboard and screen-reader alternatives. There are no mandatory animations. Both themes and 1280/760px layouts retain the approved design.

Connection is explicit; opening the screen does not reconnect or read. Profile/session changes, disconnect, navigation, failed reads and renderer reload clear captured state. Generation fences discard delayed responses, including a response arriving after reconnection. Electron additionally validates response correlation/page/digest. A failed refresh never restores previous details. No polling or automatic publication exists.

The dashboard holds only bounded metadata in renderer memory. It writes no local analytics cache, snapshots, journals or export files. Existing disposable-profile retention remains unchanged; this PR introduces no deletion policy. Test artifacts use synthetic private profiles, and screenshots contain only permitted metadata. Automatic traces/videos are disabled for the analytics packaged tests.

## Verification and adversarial review

See [verification.md](verification.md) for exact final commands/counts and CI. New Java tests compare every learning table before/after reads, validate saved results against the existing repository, exercise real packaged reference/mutant evaluations, correction/withdrawal, stale/contradictory history, epochs, pagination fences, incompatible versions, malformed/oversized/missing metadata and protocol rejection. Node tests use the real Java sidecar, strict schema rejection, wrong-session denial and delayed-response fences. Packaged Electron tests exercise all five visuals, selection/filter/comparison, empty/error states, focus, unauthorized frames, extra arguments, reload/reconnect and unchanged database hashes.

Review findings fixed: disconnected-response busy-state races; unavailable coverage mistakenly resembling no evidence; missing explicit accessible selector labels; row-header/source-root mismatches; revision paging corpus bound; fixed capture clock preceding real ingestion. All are addressed without policy changes. Remaining limits: Linux disposable profiles only; four-skill v1 only; bounded/page-local history and comparisons; persisted heads are not source re-verification; no cross-graph comparisons. Issue #222 owns broader graph evolution. No new evidence eligibility or mastery rules are implemented here.

## Visual evidence

Actual offline packaged Electron captures use synthetic records created through Java evaluation and explicit reconciliation, not frontend progress fixtures. Each image shows a chart and its surrounding interface.

| Chart | Desktop dark | Desktop light | Narrow dark | Narrow light |
|---|---|---|---|---|
| Ordinal timeline | [1280 dark](screenshots/chart-1-1280-dark.png) | [1280 light](screenshots/chart-1-1280-light.png) | [760 dark](screenshots/chart-1-760-dark.png) | [760 light](screenshots/chart-1-760-light.png) |
| Evidence timeline | [1280 dark](screenshots/chart-2-1280-dark.png) | [1280 light](screenshots/chart-2-1280-light.png) | [760 dark](screenshots/chart-2-760-dark.png) | [760 light](screenshots/chart-2-760-light.png) |
| Coverage matrix | [1280 dark](screenshots/chart-3-1280-dark.png) | [1280 light](screenshots/chart-3-1280-light.png) | [760 dark](screenshots/chart-3-760-dark.png) | [760 light](screenshots/chart-3-760-light.png) |
| Prerequisite DAG | [1280 dark](screenshots/chart-4-1280-dark.png) | [1280 light](screenshots/chart-4-1280-light.png) | [760 dark](screenshots/chart-4-760-dark.png) | [760 light](screenshots/chart-4-760-light.png) |
| Revision comparison | [1280 dark](screenshots/chart-5-1280-dark.png) | [1280 light](screenshots/chart-5-1280-light.png) | [760 dark](screenshots/chart-5-760-dark.png) | [760 light](screenshots/chart-5-760-light.png) |

## Follow-up boundary

Keep #261/#268/#256 open. Real-profile analytics needs its own reviewed snapshot capability; never point this live experimental owner at a real database. Coordinate multi-graph histories with #222. Cross-page comparisons and larger indexed histories need bounded, independently reviewed query contracts. Recommendations, rewards, new curricula and Gate 3 remain outside this read-only slice.

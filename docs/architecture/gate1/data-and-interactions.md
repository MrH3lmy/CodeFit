# Data truth and clickable walkthrough

The renderer is a **visual prototype**. A persistent banner says “Demonstration data. Your learner profile is not connected.” No learner identity, XP, rank, earned reward, mastery percentage or history is read or invented as authoritative. `data.ts` contains independently authored design fixtures, not copied reserved challenge prompts. These fixtures and all edits/checklists/bookmarks are memory-only and reset on restart or screen remount. There is no localStorage/database persistence.

The sole real integration is Java Library. It calls the unchanged `listDecks` / `cancelRead` bridge; `DesktopReadOnlyMain` creates a disposable seeded snapshot and invokes existing `DeckService.getDecks()`. Only descriptors cross IPC. Library stays mounted after it is first visited, so rapid navigation preserves the same pending read. The main process, preload, strict DTOs, Java process supervision, SQLite ownership and cancellation policy are unchanged.

## Widget ownership map

| Widget | What the prototype actually shows | Future authoritative owner / rule |
| --- | --- | --- |
| Explorer name/avatar | Explicitly labeled demonstration identity | Local profile choice; `SocialProfileService` only with existing privacy/consent |
| Profile level, XP, rank, rewards | Em dashes and “Not connected” | `ProgressionService` snapshot/entitlements; no TypeScript reward formula |
| Discover → practice → transfer | Illustrative path, no mastery percentage | Product framing only; actual state must use Java evidence/learning contracts |
| Mission recommendation / active session | Author-authored CF-024 example; sample Step 2 of 3 | `AdaptiveQuestService` current decisions, reasons and freshness; no claim this is a live recommendation |
| Today’s intentions | Clickable sample checklist; no XP or persisted completion | Future deliberate application command only if approved; not the auto-creating `DailyQuestService` getter |
| Recent signals / reflection | Clearly labeled example journal entries | `EvolutionJournal`, effective references and committed evaluation state; withdrawn/private evidence must not become proof |
| Boundary thinker achievement specimen | Proposed recognition in the journal dialog, explicitly “Not earned”; no eligibility/entitlement calculation | Only existing/future authoritative progression entitlement contracts; no fake achievement history |
| Integration trial | Explicit future concept; no encounter or reward exists | Only future implemented catalog/entitlement contracts after separate approval |
| Seven-node engineering atlas | Conceptual topology, not the Java pilot graph. Completed means example path state, not mastered capability | Exact versioned `SkillCatalogRepository` graph and `MasteryEstimateService` revisions |
| Evidence inspector | Current/Unknown/Stale/Contradictory specimens. Current says example DEVELOPING/LIMITED; no real evidence IDs | `EstimateRevisionRepository`, effective evidence/evaluation references; preserve UNKNOWN, support, freshness and contradiction |
| Mission catalog/details | Four original demonstration briefs; sample availability/difficulty/time | `QuestCatalogRepository` plus committed authorized `ChallengePresentationService.View` before content exposure |
| Editable workspace | Original scratch text, memory-only “Keep in this preview”; no durable save | Existing workspace/draft application services with actual save acknowledgments |
| Evaluation/recovery | Simulated transitions and DEMO-001 example frozen submission; no compiler or real retry | `ChallengeEvaluationCoordinator` durable stage/outcome keyed to frozen submission; unavailable is not learner failure |
| Java Library | Real eight seeded deck descriptors from a Java-created disposable DB | Existing `DeckService` / `DeckRepository`, isolated read-only connection |
| Theme / filters / zoom / bookmarks | Local view state | Frontend presentation only; never learning policy |

`PlayerSystemService.overview()` is deliberately not called: it invokes adaptation and reward reconciliation. The Gate 0 ADR and source audit establish that it is not a pure read. No mutating IPC, learner DB path or additional command was introduced. Gate 2/3 remains out of scope. JavaFX, P2P consent/security, recovery, autosaves, policies and schema are untouched.

## Walkthrough

1. Start on Command Center. Inspect the faceted core, example path and active mission. Toggle a sample intention; no reward is granted. Scroll to Recent signals and open the example journal. Inspect the integration-trial future concept. Open Explorer to see explicitly unconnected progression fields.
2. “Enter your next mission” opens a demonstration briefing. “View skill path” opens Ascension Tree. Select a node with mouse or keyboard, inspect prerequisites, switch Overview/Evidence, and choose stale or contradictory evidence. Branch buttons emphasize a branch while retaining context. Zoom in, pan with scrollbars/trackpad, then reset.
3. At 760 px, use “Inspect [skill]” below the map to bring the inspector into view and focus its heading. Labels do not shrink into a miniature map.
4. Open Mission Console. Search “contract”; the visible result and briefing update together. Filter Available/Completed/Blocked, toggle a bookmark and open details. Blocked examples allow inspection and prerequisite navigation, while Workspace and Evaluation tabs are disabled.
5. Enter a sample workspace and edit the text. Escape or “View skill path” after returning to Briefing asks whether to keep editing or discard. “Keep in this preview” acknowledges only current in-memory text. Closing the dialog discards it; there is no durable save claim.
6. “Simulate evaluation” plays a short local transition and shows explicitly sample feedback. Select Interrupted to inspect DEMO-001’s unknown outcome, then “Inspect example status”; select Evaluator unavailable to see the environmental failure distinction. None of these actions evaluates Java or recovers a real submission.
7. Preview states exposes populated, new learner/unknown, empty, offline, held loading and service error. Retry/reconnect/finish controls reset the specimen. Offline is explicitly simulated; the bundled screens actually work with external network routes removed. The real Java library retains its own independent loading/error/cancellation flow.
8. Toggle light/dark anywhere, use Tab/Shift+Tab and Escape through dialogs, and enable OS reduced motion. Open Java Library separately to verify real read-only deck descriptors, refresh and cancel.

[Native walkthrough recording](walkthrough.webm) shows the journey above. This is a recording of Electron, not a synthesized design animation.

# Gate 1: explicit visual approval before migration

The [Gate 1 proposal, native screenshot gallery and review checklist](gate1/README.md) are now available. **Visual approval remains pending.**

Issue #259 follows review of Gate 0 #257. Draft #255 was not approved. Gate 0's library screen proves native desktop rendering and Java communication; it is not an approved Command Center or substitute for the high-fidelity design gate. Do not start Gate 3 screen migration based on passing tests or these screenshots.

## Design deliverable

Build a local clickable React prototype using the candidate desktop shell. Present a distinctive System Command Center: clear personal identity, cinematic but restrained hero composition, layered midnight navy surfaces, cyan/violet capability signals, gold only for authoritative rewards, and a readable light theme. Use purposeful SVG character/emblem and graph artwork; no dependency on remote fonts/assets. Code editing must prioritize contrast, selection, focus and dense readable content over glow.

Prototype the complete inspection/navigation journey: Command Center → recommendation → mission details → Ascension Tree → skill evidence → Mission Console → evaluation/recovery view. Show a boss encounter concept as a labeled prototype, not an existing multi-stage gameplay claim. Provide keyboard navigation, visible focus, screen-reader names and reduced-motion alternatives. Animate only navigation/selection/status changes; no perpetual decorative motion required.

| Widget / interaction | Authoritative future source | Truth rule |
| --- | --- | --- |
| Name/avatar | Explicit local profile choice; `SocialProfileService` only where existing privacy/consent permits | No invented rank or identity inferred from P2P data. |
| XP/level/rewards | `ProgressionService` entitlement projection | Rewards measure engagement/progression, never verified mastery. No frontend formula. |
| Recommendation / alternatives | `AdaptiveQuestService` decision and reasons | Show reasons, freshness and pause/skip/change-direction actions; do not invent an AI directive. |
| Skill nodes/prerequisites | Exact `SkillCatalogRepository` graph + `MasteryEstimateService` revisions | Preserve UNKNOWN, support, freshness and contradiction; no fabricated percentage or XP-based node unlock. |
| Evidence inspector | Existing effective evidence/evaluation references, narrowly projected | No private source, hidden tests or withdrawn evidence presented as active proof. |
| Mission content/hints | `ChallengePresentationService` committed authorized view | Preview cannot become a bypass around exposure-before-content. |
| Editor/draft/save state | Existing workspace/draft application services | Distinguish acknowledged save, unsaved edit and frozen submission. |
| Evaluation/recovery | `ChallengeEvaluationCoordinator` durable view | Always identify challenge, frozen submission, stage and outcome; controls work without opening a reserved editor. Unsupported is not learner failure. |
| Boss / future achievement concept | Only implemented catalog/reward contracts when available | Otherwise label every value and interaction **prototype-only**. Do not suggest actual completion/entitlement. |

`PlayerSystemService.overview()` currently performs reconciliation; the table is a mapping plan, not permission to wire it into a read-only prototype. Gate 2 #261 owns command/projection boundaries.

## Review matrix and approval record

Deliver native desktop screenshots at **1280×900 and 760×900** in both themes, plus a short clickable-flow recording. Include populated, new learner/UNKNOWN, empty, offline, loading, service error, stale/contradictory evidence, unsaved draft, interrupted submission and evaluator-unavailable states. Keep prototype-only values visibly labeled in screenshots and interaction states. No screenshot may imply a real learner earned fabricated rewards.

Review composition, hierarchy, spacing, typography, card density, graph exploration, editor usability, focus order, contrast, motion and truthful language. Native Windows/macOS rendering and accessibility checks remain required before production signoff; Linux screenshots cannot establish them.

The PR must include a widget-to-service map, tested interactions, inaccessible/unimplemented behaviors, screenshot dimensions/theme/commit and known platform gaps. Automated checks support this review but do not approve visual quality.

**Approval must be an explicit product-owner comment on #259 or its PR**, naming the prototype commit and approved screens/themes/states, with any required revisions. Silence, green CI, technical review or merging Gate 0 is not visual approval. Record that comment link in the design document before implementing the approved frontend in Gate 3. Material visual deviations require renewed review.

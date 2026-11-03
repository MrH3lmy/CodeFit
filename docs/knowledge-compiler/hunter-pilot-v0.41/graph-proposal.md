# Effective Java Items 57–60: review-only graph proposal

**Status: AUTHOR_DRAFT_PENDING_EXTERNAL_REVIEW · 0 independent approvals · not published, selectable, or practice-eligible.**

This proposal is limited to the exact author-supplied v0.41 metadata in
`preview-catalog.json`. The source revision and four section locators are copied
into [`graph-proposal.json`](graph-proposal.json) and checked against the pinned
input by `HunterPilotV041GraphProposalTest`. The section digests are locators, not
proof that the unavailable source bodies or claims are accurate. No book text,
examples, prompts, answers, reviewer records, or learner data are included.

## Existing graph and version boundary

The only existing seeded Java graph in scope is
`GraphRef("java-debugging-concurrency", 1)`. It contains exact version-1 refs for
`java.debugging.reproduce`, `java.concurrency.atomic-update`,
`java.concurrency.invariant`, and `java.concurrency.liveness`. None matches the
four compiler IDs or six external prerequisite IDs below. No alias, stripped
suffix, behavior similarity, or newly fabricated definition can close that gap.

| Source item | Exact compiler ID | Source title | Pinned graph / disposition |
| --- | --- | --- | --- |
| `EJ-ITEM-057` | `java.general.local.scope.v1` | Limit local state to the smallest meaningful scope | `java-debugging-concurrency@1`: `MISSING_REVIEW_REQUIRED`; no exact `SkillRef` |
| `EJ-ITEM-058` | `java.general.enhanced.iteration.v1` | Choose enhanced iteration without hiding mutation and pairing requirements | `java-debugging-concurrency@1`: `MISSING_REVIEW_REQUIRED`; no exact `SkillRef` |
| `EJ-ITEM-059` | `java.general.library.selection.v1` | Prefer documented library primitives over ad hoc algorithms | `java-debugging-concurrency@1`: `MISSING_REVIEW_REQUIRED`; no exact `SkillRef` |
| `EJ-ITEM-060` | `java.general.exact.decimal.v1` | Model exact amounts with explicit decimal scale and rounding | `java-debugging-concurrency@1`: `MISSING_REVIEW_REQUIRED`; no exact `SkillRef` |

The proposed topology preserves the one internal edge
`java.general.local.scope.v1 → java.general.enhanced.iteration.v1`. The topology
is not yet a CodeFit `SkillGraph`: external endpoints and exact immutable
`SkillRef`s are unresolved. `candidateGraphRef` is deliberately `null`; this
packet does not reserve a graph ID/version or assert that definitions exist.

## External prerequisite dispositions

Each row is an edge from the exact source prerequisite to its dependent compiler
ID. The disposition means no exact existing Java `SkillRef` has been verified;
it is not a request to omit the edge.

| Dependent compiler ID | Exact prerequisite ID | Disposition |
| --- | --- | --- |
| `java.general.local.scope.v1` | `java.control.flow` | `MISSING_REVIEW_REQUIRED` |
| `java.general.local.scope.v1` | `java.exceptions.checked` | `MISSING_REVIEW_REQUIRED` |
| `java.general.enhanced.iteration.v1` | `java.collections.iteration` | `MISSING_REVIEW_REQUIRED` |
| `java.general.library.selection.v1` | `java.numeric.integer.overflow` | `MISSING_REVIEW_REQUIRED` |
| `java.general.library.selection.v1` | `java.api.contracts` | `MISSING_REVIEW_REQUIRED` |
| `java.general.exact.decimal.v1` | `java.numeric.integer.overflow` | `MISSING_REVIEW_REQUIRED` |
| `java.general.exact.decimal.v1` | `java.api.preconditions.v1` | `MISSING_REVIEW_REQUIRED` |

There are seven external edge occurrences and six distinct external prerequisite
IDs. In particular, `java.numeric.integer.overflow` remains attached to both
source skills. The exact IDs and edge endpoints come from the pinned input; the
existing graph's exact definitions are from `JavaDebuggingSkillSeed.graph()`.

## Review questions — independent gates

### Technical / Java 21

- For each of the four source sections, can an independent reviewer verify the
  intended claim and boundaries against the authorized source without treating
  the checksum or author title as evidence of correctness?
- Which precise Java 21 language/library behaviors, API contracts, and edge
  cases must a future definition cover? Do not infer behavior statements from the
  titles alone.
- Which exact existing `SkillRef`—if any—matches each external prerequisite?
  Require definition/version and graph membership evidence. If none exists,
  identify separately reviewed definitions and exact versions needed; never
  substitute a related skill.
- Does preserving the local-scope → enhanced-iteration dependency make sense
  under reviewed Java 21 behavior and the full source scope? Are any additional
  dependency edges warranted, and what evidence supports each?
- What deterministic Java 21 checks would be needed for future assessment, and
  what remains unsupported or requires human judgment?

### Pedagogical

- Are each objective's boundaries observable, appropriately scoped, and distinct
  from the other three without overclaiming what the supplied metadata supports?
- Are all six external prerequisites genuinely necessary and sufficient for
  entry? Is the retained internal edge useful without being mistaken for an
  automatic learner unlock?
- What misconceptions, varied contexts, accessibility/fairness checks, and
  calibrated difficulty evidence would a future mission need? The supplied
  stage labels do not provide stage instructions or difficulty.
- Which responses can be evaluated deterministically, which require a rubric or
  human judgment, and which must remain unsupported?

Technical and pedagogical reviewers must be independent of the author and
review the exact immutable proposal/content revision. Their decisions, reviewer
identity/credentials/conflicts, and owner adjudication remain external; none is
created or implied here. #253 review of other Effective Java items does not
approve Items 57–60. #269 grader readiness is a separate gate.

## Versioning, selection, and release boundary

`SkillRef` pins an exact definition version; `GraphRef` independently pins an
immutable graph snapshot. The existing catalog rejects conflicting reuse and
does not infer “latest”. The #222 selection contract requires an explicit,
revision-checked choice for new work; publication does not rewrite history or
automatically select a graph. Selection eligibility is not content approval:
the current generic graph-selection API does not encode this draft's review
status. Therefore a future publication must be separately authorized only after
the review gates below, and activation must remain a separate explicit decision.

Before any immutable graph publication, a separate reviewed change must provide:

1. Owner authorization and independently adjudicated technical and pedagogical
   decisions bound to the exact proposal revision.
2. Actual definition content and observable behaviors supported by reviewed
   source evidence, with each compiler ID mapped to a distinct exact `SkillRef`
   and an explicitly chosen `GraphRef` version. Definition versions and graph
   versions are independent; revisions require new immutable versions.
3. Exact verified refs for all six external prerequisite IDs, the preserved
   internal edge, complete endpoint membership, no mapping collisions, and a
   validated acyclic graph. Missing refs block publication; they are not dropped.
4. A separate evidence-mapping and assessment design with real authorized
   evidence, supported checks, exposure controls, and independent #269 review.
   Mapping metadata alone is not a grader or evidence.
5. Explicit publication, followed by separate opt-in graph selection under
   #222. No selection, practice, mastery, recommendations, XP, rewards, or
   learner-history migration is authorized by this proposal.

The proposal stays outside `src/main/resources`; no production graph, evidence
mapping, catalog registration, selection, Ascension node, eligible practice,
grader, or learner state is added or changed. Existing JavaFX behavior remains
untouched. The test-only disposable fixture checks the proposed topology and
mapping invariants, stale selection rejection, and absence/refusal of an
unregistered candidate. Those structural checks do not approve curriculum.

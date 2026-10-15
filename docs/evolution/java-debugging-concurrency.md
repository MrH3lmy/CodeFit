# First vertical slice: Java Debugging & Concurrency

**Design fixture, not implemented content or observed learner improvement.** #213 authors original
assets; #214 validates harnesses and sample solutions; #212 computes the baseline/estimates; #215
selects tasks; #216 delivers the UI. The example deliberately does not claim general intelligence,
production certification or proof against every JVM schedule.

## Goal and minimal skill graph

Goal: "Diagnose and repair shared-state bugs in a small Java service, then preserve invariants in a
new service context without a supplied solution." Budget: 20 active minutes, learner-controlled.

| Skill ID (definition v1) | Observable behavior | Prerequisites |
| --- | --- | --- |
| `java.debugging.reproduce` | Converts a failing example/trace into a minimal regression check and correctly localizes the fault | None |
| `java.concurrency.atomic-update` | Distinguishes visibility from compound atomicity; repairs lost updates and preserves exact totals under the specified checks | reproduce |
| `java.concurrency.invariant` | Preserves a multi-field invariant under competing updates; selects an atomic boundary that covers the whole invariant | atomic-update |
| `java.concurrency.liveness` | Identifies a lock-order cycle and repairs termination without removing required safety behavior | invariant |

No edge says passing one skill automatically proves its dependent. `adaptation` is not a scored
cross-domain skill here. The graph is validated before any task can be recommended.

## Catalog and hold-out plan

IDs below denote immutable version-1 manifests, not file paths that already exist. Each executable
variant binds one non-roadmap `Problem`; prompts/checks remain original bundled assets. A task has
explicit target skills/check mappings, constraints, difficulty, effort and fair hint ladder. No imported
workbook text is copied. Equivalent skill coverage does not imply interchangeable exposure families.

| Task / family / pool | Content and required observation | Isolation from practice |
| --- | --- | --- |
| `baseline-trace` / trace-diagnostic / BASELINE | Predict a provided interleaving: two reads of 0, each writes 1; expected final value 1, expected serial total 2. Choose atomicity vs visibility explanation using deterministic keyed items. Separate assertion-localization exercise. | Never returned by ordinary review or practice selector; outcome/hints captured at presentation |
| `baseline-counter` / diagnostic-counter / BASELINE | Repair a request count with distinct requests from concurrent workers; record regression and exact final count | Separate from all practice and transfer families |
| `counter-repair` / metric-counter / PRACTICE | Repair lost updates in a telemetry counter; rule out `volatile` alone; add regression for missed increments | Hints available; repeated variants stay same family |
| `atomic-boundary-drill` / compound-update / PRACTICE | Diagnose a new operation trace, choose correct atomic boundary, then repair bounded counter update | Lower-complexity prerequisite after repeated failure |
| `remaining-capacity` / bounded-capacity / PRACTICE | Independently preserve a nonnegative capacity invariant while accepting concurrent reservations | Distinct family, same atomic-update behavior plus additional invariant checks |
| `distinct-id-accumulator` / deduplicated-accumulator / PRACTICE | Independently count accepted unique IDs despite duplicate concurrent requests; distinguish safe compound updates from safe individual operations | Second independent practice family; never baseline or transfer |
| `inventory-transfer` / inventory-reservation / TRANSFER | Implement cancel/reserve for unique order IDs with no oversell, duplicate acceptance or lost cancellation | Reserved unseen context; not disclosed as a walkthrough or a renamed counter |
| `queue-retention` / delayed-queue-accounting / RETENTION | After a gap, repair independent queue accounting under producers/consumers and duplicate acknowledgments | Reserved from daily catalog; no recent same-behavior rehearsal |
| `lock-order-repair` / two-resource-order / PRACTICE | Diagnose lock cycle in a two-resource operation; preserve sum and termination | Next harder liveness opportunity, not automatic mastery from a counter pass |

Additional baseline/post-test and retention variants must be reserved by #213/#218 before a pilot.
There is no infinite unseen supply: on depletion, label a repeat and withhold transfer claims. An item
counts as presented even if skipped, shown through a non-Evolution route or its solution is revealed.
Existing #104 variants rotate and eventually wrap; use that mechanism only with an exposure guard,
not as proof that the chosen variant is unseen.

## Evaluation design

Use a bundled Java 21 harness and learner-editable method/class fragment assembled into a single
public `Main` source, compatible with `CodeRunner.compile(source, mainClassName)` today. Preserve
the exact assembled source and harness digest. There is no requirement for Maven inside the app,
remote libraries, multi-file project execution or containers. Those would exceed the current runner.

For `counter-repair`, initial reference fixture:

- Initial value 0; 8 workers each process 1,000 distinct requests. Required final value: 8,000; a
  sequential check covers 0, 1 and multiple requests; boundary check uses a different initial value.
- Separate deterministic operation-trace checks establish the failure mechanism: `read A=0`,
  `read B=0`, `write A=1`, `write B=1`. Ask whether `volatile` changes atomicity; fixed-key grading
  can verify this bounded explanation. Free-text reasoning is retained as reflection, not AI-graded proof.
- Run concurrent workloads for predeclared seed/input sets and iterations, with coordinated worker
  starts and join bounds. Check exact totals, no uncaught exceptions and termination for each case.
  Use barriers/latches rather than sleep to coordinate starts. A seed controls work allocation, **not**
  JVM scheduling. Stress passes support only the sampled checks.
- Before shipping, #214 must demonstrate that its required rubric reliably rejects known wrong
  solutions (`volatile` read/modify/write, synchronized read and write separately, constant output,
  dropped requests), and accepts at least two valid fixes (`AtomicInteger` atomic update and a correct
  shared lock). If stress alone is flaky, add test-only controlled schedule instrumentation around
  the vulnerable operations or narrow the claim to what deterministic trace checks establish.
  Do not ship a flaky stress check as a deterministic mastery gate.
- Reference and harness instrumentation must not require valid synchronized/atomic repairs to wait
  at a barrier while holding a lock. Instrumentation must preserve the behavior being tested; its
  mutant/valid-fix checks are part of acceptance, not an assumption that all schedules are controllable.

The rubric reports separate checks: `trace.atomicity`, `sequential.total`, `concurrent.total`,
`termination`, `boundary.initialValue`. Only direct mapped checks affect the corresponding skill.
A conceptual trace answer alone cannot satisfy the code-repair behavior, and passing totals without
the atomicity diagnostic cannot establish the complete `atomic-update` definition.

For inventory transfer, the harness accepts arbitrary predeclared command streams; compare the
concurrent result against invariants and a legal sequential reference where deterministically
checkable. At capacity 3 with 12 unique order IDs competing, at most 3 active reservations and no
negative stock; duplicate reserve/cancel must be idempotent; cancel releases at most once. Also test
zero stock, repeated IDs, mixed cancel/reserve and termination. Do not claim a full linearizability proof
from final counts alone. If linearization order isn't captured, report invariant/observed checks only.

For liveness, include a deterministic lock dependency trace plus coordinated competing operations
with a published deadline. Exceeding the deadline can be an observed termination failure; distinguish
host/harness errors. A solution that avoids deadlock by dropping updates fails safety checks.

Public tests teach the contract. Additional withheld-by-UI cases test changed inputs/edge conditions;
they are not secure secrets on a learner-owned machine. All checks are local, original or licensed,
versioned, and explainable after evaluation. No external problem gets an AC from this harness.

## Example journey with evidence and decisions

Learner Mira's following results are hypothetical. Each row creates explicit observation references;
check counts are not independent sample counts. No event awards skill points.

| Step and learner action | Observation and estimate | Persisted next-decision explanation |
| --- | --- | --- |
| 1. Enters offline, selects Java track and goal | No evidence: all skills UNKNOWN; XP ignored | Offer baseline, skip available |
| 2. Localizes assertion failure independently, then predicts counter trace incorrectly and submits visibility-only repair | `E1` reproduce checks pass in one baseline family; `E2` atomicity diagnostic FAIL and concurrent-total FAIL. reproduce provisional DEVELOPING; atomic-update DEVELOPING | `D1`: compound atomicity is the observed gap, but reproduction has only one supported family; recommend a short independent regression-localization practice first |
| 2a. Independently localizes and writes the regression for a different original sequential bug | `E1b` second reproduce family, outside baseline; reproduce INDEPENDENT | `D1b`: prerequisite now supported; recommend `counter-repair` for the observed atomicity gap |
| 3. Attempts `counter-repair` and fails totals without hints | `E3` eligible task failure on atomic-update; second consecutive deliberate failure after `E2` | `D2`: two failures → lower complexity `atomic-boundary-drill`, allow pause/alternate task, no punishment |
| 4. Takes clarification and approach hints; uses atomic update, passes drill's checks | `E4` LOCAL_CHECK PASS, assistance HINT; useful practice, no independent unit | `D3`: "Passed with approach hint; validate independently on a different family"; recommend return to counter repair or fresh equivalent practice by choice |
| 5. Repairs `counter-repair` after prior exposure and passes | `E5` REPEAT/RELATED, earlier guidance exposure retained; cannot be counted as new independent proof even if no hint button opened this session | `D4`: choose distinct-family `remaining-capacity`; no harder-difficulty unlock from the repeat |
| 6. Solves capacity task independently, trace diagnostic and execution both pass | `E6` one independent fresh-family atomic-update success. Still DEVELOPING, LIMITED; reports improvement on checked behavior without pretending sufficient diversity | `D5`: same difficulty, new independent family; not another telemetry-counter variant |
| 7. Solves `distinct-id-accumulator`, the second original practice family, independently | `E7` two independent practice families cover required behaviors → INDEPENDENT/LIMITED | `D6`: offer reserved `inventory-transfer`; no walkthrough first |
| 8. Solves unseen inventory transfer without aid | `E8` independent held-out check pass in new context → TRANSFER_SUPPORTED/DIVERSE for mapped atomic-update behavior; invariant skill remains provisional until its own required evidence accumulates | `D7`: schedule delayed retention ≥7 days after instruction/rehearsal; offer optional liveness practice at suitable difficulty |
| 9. Returns ≥7 days later without relevant intervening rehearsal; solves reserved queue-accounting check independently | `E9` DELAYED_RETENTION pass → RETENTION_SUPPORTED for mapped covered skill, with scope/recency shown | `D8`: next liveness practice, or change goal. XP level remains an engagement label |
| 10. Later fails a fresh independent atomic-update validation | `E10` direct contradictory failure → current support uncertain, retain historical supported band in journal | `D9`: validate/decompose, no fake permanent mastery. Two failures cap current estimate; restoration requires new diverse independent checks |

The practice family in step 7 must ship in #213's pack; the catalog must contain enough
families for the stated policy before #216 claims a complete vertical slice. If baseline-counter had
passed independently, it could supply one family, but it cannot replace later held-out transfer.

## Preventing memorization from appearing as capability

- Persist family/cohort/presentation before display, not just a success count. Two renamed telemetry
  counters remain related; new code digest alone does not make a task unseen.
- Hints, prior solution reveal and familiarity survive retries/reset. Honest disclosure leads to a
  different validation task, never a penalty or reward deduction.
- Test general behavior on multiple inputs; constant outputs/canned sequences fail explicit mutants.
  Count each deliberate opportunity once regardless of runs/checks/backfills.
- Separate training, baseline, pilot post-test, transfer and retention pools. No #104 bank item is
  mirrored into daily review. New held-out executable tasks never appear in Topics or sample lists.
- Compare independent correctness, invariant quality, hints, active time and transfer separately.
  More practice XP, faster repeats or passing visible examples do not prove improvement.
- A tampered local installation can defeat checks. This is a personal learning system, not anti-cheat
  certification; no verified-local label claims proctoring or honest authorship.

## Slice acceptance fixtures

#213/#214 hand-test valid and invalid solutions and record exact rubric/check versions. #212/#215
fixture tests run the hypothetical sequence, unknown/skipped baseline, assisted success, wrong
`volatile` repair, duplicate command, depleted held-out catalog, stale estimate and contradiction.
#216 integration starts with an isolated fresh DB, completes baseline → quest → evaluation → evidence
→ estimate → next decision offline, restarts after each durable boundary, and verifies source/award
counts do not increase on replay. Existing workbook/import, review intervals and P2P privacy regressions
must stay passing. #218 evaluates actual learning gains; this example is not a substitute for a pilot.

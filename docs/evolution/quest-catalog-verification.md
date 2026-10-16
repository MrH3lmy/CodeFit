# EV-05 verification and adversarial review

Companion to [quest-catalog](quest-catalog.md), implementing #213 under #208.
Tests use Java 21 and isolated SQLite databases; no root learner database is created or changed.

## Commands and scope

```sh
mvn -B -Dtest='*Quest*,ChallengePresentationIntegrationTest,JavaTrainingPackAuthorTest,SkillCatalogMigrationTest,EvidenceMigrationTest,SchemaMigratorTest,ImportToPracticeCriticalPathTest,WeeklyAssessmentSelectionServiceTest' test
mvn -B clean test
git diff --check
```

Local environment: JDK 21.0.12.1, Maven 3.9.11. Maven settings, proxy CA trust and toolchains were
configured outside the repository. GitHub Actions uses its existing Temurin 21 / `mvn -B test` job.
Focused result: **88 passed**, zero failures/errors/skips. Clean Java 21 suite: **1,400 passed**
in **185 test classes**, zero failures/errors/skips. This includes 50 new tests.
Final LF fixture output and a stronger existing migration preservation assertion are also rechecked
with the focused command after the clean run.
No test is disabled or weakened. Existing old-schema fixtures now include parents needed by content
seeding, remove later additive structures when reconstructing old versions, and retain their original
source-data preservation assertions. The workbook journey still checks exact legacy import/order
counts and now separately accounts for reserved catalog Problems.

## Test inventory

| Test | Evidence |
| --- | --- |
| `QuestCatalogContractTest` | All manifests/assets round-trip and hash, exact skill/graph/prerequisite/behavior validation, mappings/checks/primary roles, immutable lists, malformed/duplicate/unknown keys, cohort/family counts, stress limitations and EV-03-compatible metadata tags. |
| `QuestCatalogIntegrationTest` | Seed replay, immutable challenge and independently versioned rubric conflicts, exact graph/cohort/variant validation, versioned backing identity, late rollback, SQL guards, stable family/variant across versions, concurrent publishers. |
| `ChallengePresentationIntegrationTest` | Clock-pinned receipt, lost-response/restart recovery, idempotent/conflicting requests and aid events, related/repeated versions, monotone familiarity, legacy drafts, reserved keyed assessments, wrong cohort/candidate/missing assets, pre-commit crash rollback, concurrent requests, no attempt/evidence/XP/peer writes. |
| `QuestDiscoveryIsolationTest` | All cohorts hidden from ordinary topics/search/distinct filters/revisit/curriculum suggestions, direct navigation/workspace/draft/guidance/flashcard routes blocked, active session isolation, reserved assessment exclusion and actual weekly selector compatibility, prior bank attempts retained as familiarity. |
| `JavaTrainingPackAuthorTest` | 14 dynamic fixtures: 12 Java references pass each manifest-declared exact I/O case; monitor and atomic alternatives; 12 deterministic functional mutants fail; two keyed responses match; starter/trusted-harness suffix integrity. No production grading or observations. |
| `QuestCatalogMigrationTest` | Populated v14 → v15, every original noncatalog row compared, repeated startup, migration DDL/data/version rollback/retry, correct backing counts and FK integrity. |
| Existing suites | Skill/evidence migration, workbook import/restart and ordering, assessment rotation/validators, Java execution, source attribution, dashboards, JavaFX/FXML/navigation and P2P/privacy/transport regressions. |

## Review findings and decisions

| Risk / attack | Result |
| --- | --- |
| Generic route leaks a reserved prompt or reference | All Evolution Problems are excluded, including practice variants, and direct rendering routes reject reserved IDs. Bound headers contain no task statement/link. Reserved diagnostics use a filtered #104 read path. Only committed presentation receipts authorize catalog View content. |
| New ID/version disguises repeated work as transfer | Family/cohort and challenge family/variant/domain identities are stable across versions; global variant identity checked within family. Exposure queries span every version. New variants retain family counts and prior aid. First local registration is never an unseen claim. |
| Blank/fresh state overwrites learner draft | Existing single draft/session rows are reused under the presentation transaction. New starter/session inserted only when missing. Legacy draft/attempt/hint state marks familiarity conservatively. |
| Hint/solution returned before write or replayed differently | Asset validated locally, then append-only exposure event committed before return. Event command conflicts reject. Highest hint, solution reveal and familiarity remain known across family and restart. |
| Concurrent requests or crash leave half a workspace/receipt | FK-enabled writer lock precedes dependent reads; publication and presentation use one caller-owned transaction. Unique command IDs, replay equality, injected rollback and two-thread tests pass. Committed-but-unrendered exposure remains conservatively recorded. |
| Silent publication/content/rubric mutation | Exact canonical payload/digest comparison, immutable SQL guards, asset verification at publication and presentation. Historical paths retained; corrupt/missing assets block instead of resolving latest. |
| One sample/stress pass becomes mastery or authoritative evidence | No evaluation/evidence/reward writer is called. Required operation-boundary specification cannot be satisfied by stress; #214 must implement a trusted handler or report unsupported/inconclusive. One deliberate future submission has one authoritative observation with multiple checks. |
| Constant-output or edited harness falsely passes | Author fixtures are not production grading. #214 must bind the trusted harness and frozen source, reject harness edits, vary inputs and validate mutants before scoring. Starter suffix and manifest cases are checked now. |
| Reserved assessment overwrites #104 or leaks weekly | Separate item/variant source rows, exact answer validator metadata, immutable reservation; regular bank queries/weekly selection exclude reserved rows. Existing categories, normal rotation and history persist. |
| New data enters P2P through legacy aggregates | Catalog writes no attempt or assessment result and adds no publication path. No new wire fields, consent scopes or peer services. #214 still owns mandatory exclusion of future challenge attempts from existing aggregate queries before it records any. |
| Migration/P2P schema collision | Fetched main is `85551f15a7a6df6f2e08d24e255afb00738e4d0e`, head migration 14. This branch uses 15; recheck before review/merge if main advances. All current source tables preserved; no workbook reorder or learner reset. |

No confirmed remaining P0/P1 blocker was identified within #213's catalog scope. Remaining limits
are intentional owner-ticket work, not hidden acceptance claims:

- #214: durable authoritative evaluation/submission, controlled atomicity/liveness checks, reliable
  volatile/lock-cycle mutant rejection, trusted harness assembly, non-judge attempt marker and P2P
  aggregate exclusion. Existing runner is not a secure sandbox.
- #216: explicit Evolution UI calls the presentation boundary before any prompt/hint/solution frame.
  The current generic workspace is deliberately blocked for catalog IDs, including after exposure.
- #212/#215: prerequisite eligibility, mastery policy, independent evidence interpretation, retention
  timing/intervening practice and clock anomalies, explainable adaptation and user controls.
- #218: independent/counterbalanced pilot outcome forms and protocol validation; reserved pools are
  small, finite and not claimed psychometrically equivalent. No pilot or learning gains were measured.

Crash checks inject transaction failures and reopen SQLite; physical power loss is not tested.
The test environment and CI are Linux. The new Evolution UI is not exercised because it belongs to
#216; existing JavaFX/FXML/navigation tests remain in the full suite.

These existing issues are sufficient; no duplicate follow-up issue or incidental implementation of
#221/#222/#224/#225 is needed. Implement **#214 next**: it turns the pinned source/check/presentation
contracts into trustworthy durable evaluation inputs required before baseline estimates and adaptation.

## Final verification

Clean suite: **1,400 tests passed**, 0 failures/errors/skips (185 classes). Focused suite: **88 passed**.
`git diff --check` and relative documentation link checks pass. Root `codefit.db` remains absent.
GitHub CI status is recorded in the PR; the PR remains unmerged for review.

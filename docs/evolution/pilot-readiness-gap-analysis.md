# EV-10 pilot readiness gap analysis (#243)

Audit base: `49dd19dc44c72eefcf28bdebe889579b342de34e` (merged #240/#241/#242),
SQLite migration **25**. This matrix was written before the readiness implementation.
No participant records, recruitment, allocations, outcomes or pilot execution are authorized here.
#218 closed the protocol-document task; it did not approve or execute a study.

## Pre-execution checklist, traced to implementation

The row identifiers below correspond, in order, to every checkbox in
[the protocol's pre-execution checklist](learning-validation-protocol.md#pre-execution-verification-and-review-checklist).
“Present” means implementation exists, not that an independent reviewer signed it off.

| Gate | Confirmed implementation and evidence | Missing evidence/functionality; severity | Owner and bounded disposition |
| --- | --- | --- | --- |
| C1 Protocol commit/version/hash and analysis freeze | [Protocol](learning-validation-protocol.md#preregistration-and-freeze-gates) declares commit/digests, allocation owner and a seeded participant bootstrap; #241 documents no outcomes | No completed freeze record, dates, seed, assignment list, analysis artifact or preregistration approval. **Execution blocker** | Independent study owner/reviewer; no enrollment or bootstrap execution in #243 |
| C2 Equivalent baseline/post behavior and invariant map | [Pack index](../../src/main/resources/evolution/packs/java-debugging-concurrency/v1/index.txt), `ChallengeSpec`, `ChallengeRubric`, [author tests](../../src/test/java/com/codefit/evolution/quest/JavaTrainingPackAuthorTest.java), [evaluator tests](../../src/test/java/com/codefit/evolution/evaluation/JavaChallengeEvaluatorTest.java) pin original content/checks | `baseline-counter/v1` maps atomic-update behavior 0 only; both posts also require invariant behavior 0. Both trace baselines share `trace-diagnostic`. No complete equivalent baseline/post form set. **Execution blocker** | #243: independent-inspectable mapping comparison and one unpublished original invariant-capable candidate; additional forms/publication and human equivalence approval remain follow-up work |
| C2 Separate transfer/delayed pools | `inventory-transfer/v1` and `queue-retention/v1` are separate families/cohorts; `EstimateInputReader.equivalent` explicitly pins only those known refs | Only one family/task per role; no reviewed alternative form/assignment coverage or general transfer/retention evidence. **Execution blocker** | Content-authoring follow-up and independent review; do not infer equivalence from cohort labels |
| C3 Ordinary browse/recommendation isolation | [ProblemVisibility](../../src/main/java/com/codefit/service/ProblemVisibility.java), [QuestDiscoveryIsolationTest](../../src/test/java/com/codefit/evolution/quest/QuestDiscoveryIsolationTest.java), `ProblemLibraryService`, `WeeklyAssessmentSelectionService`, `NavigationService.showSolvingWorkspace`, `ProblemFlashcardService` exclude reserved sources | Current Evolution recommendations may legitimately offer baseline/transfer/retention learning tasks. There is no study-specific frozen PRACTICE-only assignment filter. **Execution blocker** | Assignment/embargo follow-up; #243 verifies candidate content is uninstalled and cannot enter existing routes |
| C3 Practice execution boundary | `ChallengePresentationService.beginPractice` and `PlayerSystemService.practice` restrict receipt-backed practice to BASELINE/PRACTICE; exposure commits before execution | `JavaChallengeEvaluator.practice` itself has no cohort check; direct callers can request installed post/transfer/retention PUBLIC cases. **Confirmed implementation gap** | #243 adds the same fail-closed cohort restriction at this existing evaluator entry, preserving ordinary authorized baseline diagnostics |
| C3 Prior exposure and honest classification | `ChallengeExposureRepository`, `ChallengePresentationService.contextForSubmission`, `EvidenceObservation`, [presentation tests](../../src/test/java/com/codefit/evolution/quest/ChallengePresentationIntegrationTest.java), [mastery tests](../../src/test/java/com/codefit/evolution/mastery/MasteryIntegrationTest.java) preserve family/variant/hint/solution/familiarity, aliases and corrections | Unknown off-app familiarity/aid cannot establish unseen performance. Study assignment, feedback-view events and contamination reconciliation are not implemented. **Execution blocker** | Future study flow plus human exposure audit; no inferred absence of aid |
| C4 Skill graph/evidence foundations (#210/#211) | `SkillCatalogRepository.findGraph` validates exact graph/definition membership; [bulk tests](../../src/test/java/com/codefit/repository/SkillCatalogRepositoryTest.java) retain #242 behavior. `EvidenceRepository` provides effective/retracted/mapped observations; [evidence tests](../../src/test/java/com/codefit/repository/EvidenceRepositoryTest.java) exercise backfill and aliases | Need exact frozen commit, mappings and runtime verification; local signatures/XP are not learning evidence | Regression checks in #243; no graph, evidence schema, mapping, alias or bulk-read changes |
| C4 Evaluation and interpretation (#212/#214) | `JavaMechanismVerifier`, `JavaChallengeEvaluator`, `ChallengeEvaluationCoordinator`, `DurableEvaluationRepository`, `MasteryEstimateService`; [evaluation integration tests](../../src/test/java/com/codefit/evolution/evaluation/ChallengeEvaluationIntegrationTest.java) cover restart/replay/atomic source+evidence; `JavaChallengeEvaluatorTest` covers reviewed mechanisms and mutants | Controlled checks admit a bounded set of Java forms. Stress success is not universal safety; reflection is ungraded. Unsupported/inconclusive/error/partial categories must remain distinct. No human outcome-quality rubric. **Freeze blocker** | #243 uses existing verifier/runner on author fixtures and preserves installed adapter rejection for unpublished content; no second grader |
| C4 Adaptation/UI/journal (#215/#216) | `AdaptiveQuestService`, `EvolutionController`, `EvolutionJournal`; [real FXML tests](../../src/test/java/com/codefit/controller/EvolutionControllerTest.java) cover saved recovery separated from authorized editor | Ordinary learner UI deliberately shows saved outcomes/checks and exposes recorded hints/solutions. It is **not** a cohort-wide embargo or assigned-assessment UI. Assessment result links, hints, rewards/evidence/journal and exports need a single study authorization contract. **Execution blocker** | Assignment/feedback-embargo follow-up; normal learner UI must remain usable |
| C5 Reward policy and initial state | `ProgressionService`/`ProgressionPolicy`, [reward tests](../../src/test/java/com/codefit/evolution/progression/ProgressionServiceTest.java), `ProgressionEvaluationIntegrationTest`, `ProgressionAtomicityIntegrationTest`, `ProgressionBackfillIntegrationTest`; migrations 20–25 preserve opening XP, opt-out and bounded recovery | No frozen equal-arm reward condition/initial state; ordinary engagement does not prove capability. **Freeze blocker** | Study owner chooses equal pinned conditions or rewards disabled in both arms; #243 regression-tests #240 without changing it |
| C5 Allocation, schedule and fixed/adaptive pool | Protocol lists seven PRACTICE refs and four 30-minute sessions; existing adaptive decisions persist exact inputs/exclusions | No durable study assignment/counterbalance/allocation service; installed adaptive policy can select other cohorts. **Execution blocker** | Separate study orchestration follow-up, not a new selector in #243 |
| C5 Active-time equivalence | [ProblemSolvingSession](../../src/main/java/com/codefit/model/ProblemSolvingSession.java) stores phase counters/pause state; `ProblemSolvingSessionService` and ordinary workspace timer have existing tests | No verified Player System timer for coding + hints + feedback + reading solutions, consistent idle/pause/evaluator-runtime exclusion, or study-session budget freeze. Existing phase counters alone do not satisfy this gate. **Execution blocker** | Dedicated active-time instrumentation follow-up; no inaccurate timer claim |
| C6 Independent equivalence/rubric signoff | Published manifests/check IDs and exact asset digests are inspectable; protocol explicitly requires independent outcome-blind review | No signed human approval of form difficulty, demands, invariant equivalence, scoring scope or missing-outcome handling. **Mandatory human gate** | Independent reviewer, never self-certified by matching metadata or passing authored references |
| C7 Consent/local collection/withdrawal/export | Current storage is local SQLite; `SnapshotPublicationService`/P2P schemas do not export study records; `FlashcardImportExportService` is an ordinary learning export, not a study export. [EvolutionPeerPrivacyTest](../../src/test/java/com/codefit/service/EvolutionPeerPrivacyTest.java) and [SnapshotLeakageTest](../../src/test/java/com/codefit/service/SnapshotLeakageTest.java) protect signed boundaries | No study consent/retention/deletion contract, participant-chosen pseudonym, bounded summary preview/allowlist or authorized-pooling validation. **Execution blocker** | Dedicated opt-in study-data follow-up and privacy review; no automatic export/network/telemetry added |
| C8 Sample/attrition/confounders/uncertainty/go-no-go approval | Protocol specifies 16 target/20 cap, minimum completers, missing-data bounds, participant-level uncertainty and inconclusive outcomes | No reviewer-approved completed freeze record or analysis fixture artifact. **Mandatory human/freeze gate** | Independent study owner/reviewer; no simulated participant data presented as results |
| C9 After execution: disclose outcomes and limitations | Protocol requires complete flow/deviations/attrition/uncertainty/null/harmful findings before expansion | **Not applicable before execution; not completed.** No outcomes exist in this work | Future authorized study execution/reporting only; #243 must leave this checkbox empty |

## Bounded implementation decision

A full equivalent curriculum plus participant assignment, cohort-wide embargo, timer, consent/export
and human review is not one safely reviewable change. This PR therefore prepares one original
**unpublished** baseline Java form with atomic-update and invariant mappings, compares its required
behavior/check profile with the installed posts, validates its reference and adversarial mutants
through the existing local runner and structural verifier, and closes the direct practice-cohort gap.
It does not seed a Problem, expose the form in learner navigation, assign participants or start a pilot.
Published v1 manifests/assets, evaluator version and persistence remain unchanged. Matching mappings
are **necessary structural parity only**; concrete invariant equivalence and human difficulty/demand
review remain explicit in the equivalence matrix.

## Status at audit

| Area | Status |
| --- | --- |
| Overall technical implementation for protocol execution | **NOT READY** — assessment assignment/embargo, complete reviewed forms, timer and consent/export gates missing |
| Bounded #243 readiness slice | Implementation/verification pending at this audit commit; completion evidence will be appended after checks |
| Protocol freeze | **NOT READY** — no independent signoff or completed freeze record |
| Actual pilot execution | **NOT READY / PROHIBITED** — no recruitment, allocation, participant collection or outcome analysis authorized |

Linked residual issues and exact verification evidence are added after implementation. This initial
matrix remains the record of what was known before code changes.

## Implementation completion record

The audit above was committed first in `3195e30`, before implementing the slice.
[Assessment readiness](pilot-assessment-readiness.md) now supplies the original unpublished form,
exact behavior/check/declared-invariant comparison, installed inventory, digests and inspectable
reference/mutant rationale. `AssessmentFormReadiness` is read-only; `PilotAssessmentForms` is an
author loader, not publication. The installed evaluator cannot grade this unregistered candidate.

The confirmed direct practice gap is fixed in `JavaChallengeEvaluator.practice`: it now rejects
installed post/transfer/retention forms before runner discovery, matching the existing
`ChallengePresentationService.beginPractice`/`PlayerSystemService.practice` restriction. Running the
new boundary tests against the original production method reproduced **4 failures out of 6**, one
per held-out form reaching forbidden runner discovery. The corrected method passes all six. Neither
existing ordinary baseline/practice admission nor evaluation policy is weakened.

The remaining execution blockers have concrete follow-up owners/acceptance criteria:

| Work | Owner / issue |
| --- | --- |
| Complete independent equivalent forms, reviewed publication/adapters and extra held-out families | Content/evaluator implementation and independent equivalence reviewer, [#245](https://github.com/MrH3lmy/CodeFit/issues/245) |
| Frozen assignment, PRACTICE-only study arms, cohort-wide feedback embargo across all routes | Application-service/UI owner, [#246](https://github.com/MrH3lmy/CodeFit/issues/246) |
| Equal-arm active-time accounting with verified exclusions and recovery | Session/UI measurement owner, [#247](https://github.com/MrH3lmy/CodeFit/issues/247) |
| Opt-in consent, local withdrawal/deletion and allowlisted intentional export | Local privacy/application-service owner and independent privacy reviewer, [#248](https://github.com/MrH3lmy/CodeFit/issues/248) |
| Complete freeze record, analysis artifact/preregistration, equivalence/rubric/missing-data/sample signoff | Independent study owner/reviewer; human approval cannot be supplied by this implementation |

### Verification record

Local platform: Debian 13 Linux x86_64, Temurin **21.0.12.1+1**, Maven **3.9.11**, JavaFX **21.0.5**,
real FXML/controller execution under Xvfb display `:99`. Commands run from the dedicated worktree.
`/workspace/run216` is an environment-only wrapper executing the installed Maven with
`-s /workspace/tools/maven-settings.xml -B -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts`;
it sets `JAVA_HOME=/workspace/tools/jdk-21.0.12.1+1` and adds that JDK to PATH. Settings/truststore
are build-download configuration, not a new application dependency. Ordinary reproduction on a
Java 21 workstation can use `xvfb-run -a mvn -B ... test`.

Extended focused regression command (**206 tests, 0 failures/errors/skips**, 2m54s):

```sh
DISPLAY=:99 TZ=Pacific/Honolulu /workspace/run216 -Dtest=AssessmentFormReadinessTest,PilotCandidateAuthorTest,HeldOutPracticeBoundaryTest,PilotCandidateIsolationTest,JavaChallengeEvaluatorTest,ChallengeEvaluationIntegrationTest,QuestCatalogIntegrationTest,QuestDiscoveryIsolationTest,ChallengePresentationIntegrationTest,SkillCatalogRepositoryTest,ProgressionServiceTest,ProgressionEvaluationIntegrationTest,ProgressionAtomicityIntegrationTest,ProgressionBackfillIntegrationTest,EvolutionControllerTest,EvolutionNavigationTest,FxmlLoadingTest,EvolutionPeerPrivacyTest,SnapshotLeakageTest,DatabaseIsolationRegressionTest,ProgressionMigrationTest,ChallengeEvaluationMigrationTest,ProblemSolvingSessionServiceTest,JavaSolutionWorkspaceServiceTest test
```

After strengthening the starter to include all advertised bugs and adding its regression fixture,
the final readiness set passed **28 tests, 0 failures/errors/skips**, 20.702s:

```sh
DISPLAY=:99 TZ=Pacific/Honolulu /workspace/run216 -Dtest=AssessmentFormReadinessTest,PilotCandidateAuthorTest,HeldOutPracticeBoundaryTest,PilotCandidateIsolationTest test
```

Full final Java 21 suite:

```sh
DISPLAY=:99 TZ=UTC /workspace/run216 test
```

**1,782 tests, 0 failures, 0 errors, 0 skips; BUILD SUCCESS**, 8m44s. This full run includes
the final starter regression (13 author cases / 28 total new readiness tests).

The focused set includes real JavaFX controller (18), navigation (1), and FXML loading (2) tests;
#242 bulk-read tests (13); installed evaluator/reference/mutant tests (49); durable evaluation
integration/restart/replay tests (25); reward policy/evaluation/atomicity/backfill; session/draft,
catalog/exposure, migration and signed P2P privacy regressions. The full suite also exercises all
remaining evidence/mastery/adaptation, populated migrations, imports and multi-process P2P fixtures.
All 17 candidate asset hashes were independently recomputed; local document file links and
`git diff --check` passed. The repository-root learner `codefit.db` remains absent.

Adversarial review: the candidate has no installed Problem/quest/presentation route, no automatic
grading/evidence/XP and no journal/export/P2P addition. Whole-table snapshot tests preserve existing
authorized drafts/sessions/exposure and all other local rows over inspection, restart and rejected
hint/solution/practice/submission paths. Existing saved-result recovery remains usable; ordinary
learning's hint/result access still prevents pilot execution until #246. No existing test assertions,
fixture isolation, skips or evaluator/redaction policy were weakened. Linux/Xvfb is not native
Windows/macOS/accessibility validation; human form equivalence and all approval gates remain unverified.

| Final area | Status |
| --- | --- |
| Bounded #243 slice | **READY for draft review**, subject to exact-head CI recorded in the PR |
| Overall technical implementation for protocol execution | **NOT READY**, #245–#248 remain blockers |
| Protocol freeze | **NOT READY**, independent approval and complete freeze record absent |
| Actual pilot execution | **NOT READY / PROHIBITED**, no participant collection or learning pilot executed |

The draft PR records the final commit SHA, exact-head GitHub Actions run and final CI totals.
It must remain unmerged; no checkbox in the protocol is automatically completed by these tests.

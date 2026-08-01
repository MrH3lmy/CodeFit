# Epic #164 acceptance: guided problem-solving curriculum

This is the final acceptance summary for epic #164 — import, guided practice, hints, and the Java
runner — covering the supported journey, the verified numbers, the persistence and safety boundaries,
and what remains a known limitation. It complements (not replaces) the feature-level docs already in
this directory: `problem-solving-workbook-import.md`, `problem-solving-guided-practice.md`,
`problem-solving-hint-ladder.md`, `problem-solving-java-runner.md`, `problem-solving-workspace.md`,
`problem-solving-submissions-reflection.md`, `problem-solving-source-attribution.md`, and
`junior-training-sheet-fixture.md`.

## Supported import-to-practice journey

1. The learner picks the approved workbook (`data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx`).
2. CodeFit **analyzes** it purely in memory (`TrainingSheetAnalyzer` → `AnalyzedTrainingWorkbook`) — no
   database connection is opened, so selecting a file can never mutate anything.
3. The **preview** screen renders that exact analyzed snapshot (`TrainingSheetImportService#previewOf`):
   detected profile/version, per-stage counts, hyperlink/platform coverage, topics/quality metadata,
   and every skipped row grouped by reason, with blocking errors gating the Import button.
4. Confirming **imports the exact reviewed snapshot** transactionally
   (`TrainingSheetImportService#importAnalyzed`) — one JDBC transaction, committed only if every row
   applies cleanly, rolled back completely otherwise. The source file is never re-read between preview
   and import, so what was reviewed is exactly what gets written.
5. A successful import lands on the Problem Library/curriculum dashboard with a next recommended
   problem already computed.
6. The learner starts a structured session (Reading → Thinking → Coding → Debugging), can unlock
   progressive hints, write and run Java locally, record the external judge's verdict, and reflect.
7. Failed or hint-dependent solves stay visible through the revisit queue without disturbing the main
   roadmap order or completion state.

## Verified workbook counts

Against the one approved fixture, both preview and a real import produce:

- **923** unique problems
- **926** roadmap memberships across all seven stages (A, B, C1, C2, D1, D2, D3)
- **172** Stage B memberships
- Re-importing the same workbook creates **zero** new problems or memberships (idempotent)

These are asserted directly by `RealJuniorTrainingSheetImportTest` and re-verified end-to-end (analyze
→ preview → import → re-import) by `ImportToPracticeCriticalPathTest`.

The approved fixture also carries **10 real pre-existing AC records** from the workbook's own history
— by design, these are exactly Stage A's first 10 rows, which is also why they were chosen as the
#171 pilot-guidance set. Concretely, this means the curriculum's actual "next recommended problem"
immediately after import is Stage A's **11th** row, not its first — the import correctly preserves
that pre-existing progress rather than resetting it.

## Persistence boundaries

Everything below survives an application restart because every write goes straight through SQLite via
a repository, with no in-memory cache in any service:

- Roadmap/problem catalog data (via `import_batches`/`roadmap_entries`/`problems`)
- Phase timers, notes, and the current phase of an in-progress session (`problem_solving_sessions`) —
  cleared only when a session is explicitly finished, not on restart
- Finalized attempts, including phase-time breakdown and the recorded judge verdict (`problem_attempts`)
- Post-solve reflection (difficulty, complexity, mistakes, lessons) and assistance level (`problem_progress`)
- Saved Java drafts and named local test cases (`java_solution_drafts`, `java_test_cases`)
- Progressive-hint guidance content and its provenance (`problem_guidance`, plus a separate learner
  override table so editing never overwrites CodeFit/imported/provider content)

## Hint and assistance behavior

- Four levels — Clarify, Observation, Approach, Explanation — unlocked one at a time
  (`ProblemGuidanceService#openNextHintLevel`); a caller can never skip ahead.
- The highest level opened in the current attempt is tracked on the session and reset only when that
  attempt finishes, so a fresh attempt starts its hint usage from zero.
- Assistance is inferred automatically at finish time: no hint opened → `SELF`; any of the first three
  levels → `HINT`; the full Explanation → `EDITORIAL`. `SOLUTION` stays a manual reflection value,
  never inferred.
- Missing guidance is reported honestly (`hasContent() == false`, no fabricated text) rather than
  invented — most curriculum problems have no authored guidance yet, by design (see "Known
  limitations" in `problem-solving-hint-ladder.md`); only a 10-problem Stage A pilot set has complete,
  original, `CODEFIT`-sourced content today.
- Guidance provenance survives the workbook import merge: importing (or re-importing) the approved
  workbook never overwrites the Stage A pilot set's seeded `CODEFIT` guidance, verified end-to-end.

## Java runner safety boundaries

**Local execution is process isolation, a hard wall-clock timeout, a capped child-JVM heap, and
output-size truncation — it is explicitly not a security sandbox.** This has been the documented
threat model since #163 and is restated here rather than re-litigated: appropriate for a trusted
single-user app running the learner's own code on their own machine, not for untrusted third-party
submissions. See `problem-solving-java-runner.md` for the full caveat.

Within that model: compile/run never blocks the JavaFX thread (both spawn a background thread and
marshal results back via `Platform.runLater`), a run is cancellable and kills the full child-process
tree, and a `CompileOutcome`'s temporary directory is always cleaned up — on success, failure,
cancellation, navigating away, or normal application shutdown.

## External judge responsibility

The external online judge remains the authoritative verdict source. CodeFit's Java runner is for
local iteration only; there is no automatic submission to any judge (explicitly out of scope). The
workspace's Finish action (Submitted / Accepted / Could Not Solve, with a picked verdict) is how a
learner manually records that external verdict — recording the verdict and finishing the session are
the same action by design, not two separate steps.

## Revisit and recommendation behavior

- The recommendation always prefers untouched **mandatory** curriculum work, in roadmap (Blind Order)
  sequence, before falling back to untouched optional work, then to unfinished mandatory/optional work.
- Finishing a problem successfully — even with hint/editorial assistance — immediately advances the
  recommendation to the next untouched position.
- A failed attempt moves a problem to `NEEDS_REVISIT` and the recommendation advances past it rather
  than trapping the learner on the same failed row.
- Both failed and hint/editorial/solution-assisted **solved** problems remain visible through the
  revisit queue without changing their underlying roadmap completion state.

All of the above — advance-after-success, advance-after-failure, and revisit-queue membership for both
cases — is verified end-to-end in `ImportToPracticeCriticalPathTest`, not just at the unit level.

## Automated tests protecting the critical path

`ImportToPracticeCriticalPathTest` (`src/test/java/com/codefit/service/`) is the one required
end-to-end regression test for this epic. It runs entirely against a fresh, isolated, throwaway SQLite
database via `DatabaseConfig#useDatabaseFile` — **never** the shared local `codefit.db` — so it is safe
to run alone, as part of the full suite, repeatedly, and in any class order. It exercises, using only
public service APIs (no re-implemented import/recommendation/hint/runner logic):

clean-database start → pure in-memory analyze → preview counts match the approved fixture → confirmed
transactional import → exact expected counts (923/926/172) → idempotent re-import → seeded pilot
guidance provenance → next-recommended-problem availability → guided session phase tracking across
all four phases → survives a simulated restart → progressive hint reveal without fabricating missing
content → persisted highest hint level → correct assistance-level calculation → Java draft persistence
→ compile and run (skipped gracefully when no compatible JDK is present) → a passing local test case →
recording an external-judge verdict via finish → post-solve reflection → recommendation advances after
a successful, hint-assisted solve → that solve remains in the revisit queue → a failed attempt moves to
`NEEDS_REVISIT` and the recommendation still advances → all of the above state survives a second
simulated restart.

This complements, rather than replaces, the existing narrower test suites: `RealJuniorTrainingSheetImportTest`
and `TrainingSheetAnalyzerTest` (import), `GuidedPracticeServiceTest`/`ProblemDashboardServiceTest`
(recommendation), `ProblemGuidanceServiceTest`/`StageAPilotGuidanceSeedTest` (hints/guidance),
`JavaCodeRunnerTest`/`ProblemSolvingWorkspaceJavaRunnerBusyStateTest` (Java runner), and the FXML/
controller tests that cover UI wiring separately.

## Known limitations

- Local Java execution is **not** a security sandbox (see above) — process isolation and resource caps
  only.
- The Java editor is a plain monospace `TextArea`, not a real syntax-highlighting code editor; and
  "incompatible JDK" isn't a distinct detected condition beyond *missing* `java`/`javac` — both remain
  open, explicitly documented gaps from #163 (see `problem-solving-java-runner.md`).
- Only a 10-problem Stage A pilot set has authored hint-ladder/explanation content today; the rest of
  the curriculum has the mechanism but no authored guidance yet.
- Aside from the new `ImportToPracticeCriticalPathTest`, this suite's other integration tests still
  share the local `codefit.db` file rather than an isolated database each — a pre-existing convention,
  tracked for a broader retrofit in a follow-up issue, not a product defect.
- A cross-workbook roadmap-slot conflict (a *different* workbook than the approved one contesting an
  already-imported slot) is only caught during the transactional import itself, not during preview.

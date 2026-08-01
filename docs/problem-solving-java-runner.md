# Embedded Java editor, compiler, and test runner (#163)

The Solving Workspace's "Java Runner" panel lets a learner write, compile, and run a Java solution
against custom input without leaving the app, while the external judge remains the authoritative
result — the panel says so explicitly, right above the editor.

## Pluggable runner architecture

`CodeRunner` is the abstraction: `isAvailable()`/`getUnavailabilityReason()`, `compile(source,
className)` returning a `CompileOutcome`, and `run(compiled, stdin, limits, cancellationToken)`
returning a `RunResult`. `JavaCodeRunner` is the only implementation today, but nothing about the
interface is Java-specific — a future language's runner slots in without the workspace controller
needing to know which one it's talking to.

This is a separate class from the existing `JavaSandboxRunner` (which grades a bounded
fill-in-the-blank flashcard exercise against one expected stdout/exception, #111-era). `JavaCodeRunner`
compiles and runs a learner's **complete, arbitrary** program, with their own custom standard input,
and is designed to compile once and run several times against different inputs — genuinely different
requirements from the flashcard grader, so it's a fresh implementation rather than a forced
generalization of that one.

**Not a security sandbox.** Process isolation, a wiped child environment (only `PATH` survives, so
the child never inherits application config or credentials), a hard wall-clock timeout, a capped
child-JVM heap, and output-size truncation are all local-process hygiene, not containment. This is
appropriate for a trusted local single-user app running the learner's own code on their own machine —
the same threat model, and the same documented caveat, `JavaSandboxRunner` already carries.

## Compile once, run many times

`compile()` returns a `CompileOutcome` (`AutoCloseable`) holding the temporary work directory the
compiled `.class` files live in. A caller can `run()` against the same `CompileOutcome` as many times
as it wants (different stdin each time) before closing it.

"Run multiple local test cases" now has a real UI on top of that architecture: `JavaTestCase` (a
genuine one-to-many table, `java_test_cases`, ordered by `position` — unlike the single-row
`java_solution_drafts`) holds any number of named stdin/expected-output pairs per problem, managed
through `JavaSolutionWorkspaceService#listTestCases`/`addTestCase`/`updateTestCase`/`removeTestCase`.
The workspace's "Test Cases" section lets a learner add/edit/remove them and run each independently
(or all of them via "Run All Test Cases") against whatever's currently compiled, each showing its own
PASS/FAIL/output — separate from the original single quick-run stdin/expected-output pair above it,
which still exists for a fast one-off check.

Closing a `CompileOutcome` deletes its work directory whether compilation succeeded, failed, or the
caller never got around to running against it — `JavaCodeRunnerTest` verifies this for both the
success and failure paths. `CompileOutcomeRegistry` (see below) is what actually triggers that close
in the real app, rather than the controller managing it ad hoc.

## Compiler diagnostics linked to the editor

`JavaCodeRunner.parseDiagnostics` turns javac's plain-text output into structured `CompileDiagnostic`s
(file, line, column when derivable from the caret line javac prints under a diagnostic, severity,
message). The workspace renders each as a clickable row; clicking selects the source `TextArea`'s
entire reported line (landing the caret at its end), which both scrolls it into view and visually
highlights it — not just an invisible caret move to the right line/column.

## Timeout, cancellation, and process-tree cleanup

`RunLimits` carries the wall-clock timeout, heap cap, and output byte cap. The timeout is a learner
preference now (`user_progress.java_run_timeout_seconds`, a "Timeout:" dropdown next to the class-name
field in the workspace, 5/10/15/30/60 seconds) rather than a fixed constant nothing in the UI could
change — `JavaSolutionWorkspaceService#currentRunLimits()` builds a `RunLimits` from it, keeping the
memory and output-byte caps at their defaults (the issue only calls out the timeout as needing to be
configurable). `RunCancellationToken` is a one-shot handle: the UI thread holds one per in-flight run
and calls `cancel()` from a "Cancel" button while the actual compile/run blocks on a background
thread; both a timeout and an explicit cancellation call `RunCancellationToken.killTree`, which
destroys the process's descendants (via `ProcessHandle`) before force-destroying the process itself —
the "kill the complete child-process tree" requirement.

## Compiled temp directories don't leak on navigate-away or quit

`CompileOutcomeRegistry` is the single owner every fresh `CompileOutcome` gets registered with
(`ProblemSolvingWorkspaceController#onCompileFinished`), closing whatever was previously registered —
the same "one shared owner, always closable" shape `BackgroundImportExecutor` uses for the import
worker thread. `goProblems()` and `goToNextRecommended()` (this workspace's only ways to leave for a
different screen/problem) close the registry before navigating, and `CodeFitApplication#stop()` closes
it on normal application exit. Previously, a `CompileOutcome` from the *last* compile of a session was
only ever closed by a *subsequent* compile — never by leaving the screen or quitting — so its temp
directory under the OS temp dir leaked permanently in both of those cases.

## Never blocks the JavaFX UI thread

`ProblemSolvingWorkspaceController#compileJavaSolution`/`#runJavaSolution` each spawn a daemon
background `Thread` for the actual `CodeRunner` call and marshal the result back via
`Platform.runLater` — the UI stays responsive (and the Cancel button reachable) for the whole
duration of a compile or run, including one that's stuck in an infinite loop until its timeout fires.

## Draft persistence and autosave

`JavaSolutionDraft` (one row per problem, `UNIQUE(problem_id)`) holds the source code, class name,
stdin, and expected output. `JavaSolutionWorkspaceService#saveDraft` is a plain upsert — the workspace
calls it on a short (800ms) debounce after any edit to the class name/source/stdin/expected-output
fields, so "autosaved locally" doesn't mean "saved on an explicit button" and surviving an application
restart is exactly what loading the same row back does.
`JavaSolutionDraft.template(problemId)` is the "configurable class/template generation" starting
point for a problem with no saved draft yet — a minimal runnable `public class Solution { public
static void main(...) {} }`, with the class name itself editable in the workspace.

## Missing/incompatible JDK detection

`JavaCodeRunner`'s constructor resolves `java`/`javac` under `java.home` the same way
`JavaSandboxRunner` already does; when either is missing (e.g. a JRE-only install), `isAvailable()` is
false and `getUnavailabilityReason()` names the problem and says what to do about it ("install a JDK
… and set JAVA_HOME, or point CodeFit at one, then restart") rather than a bare "not found". The
workspace disables Compile/Run and shows this message directly in the panel instead of silently doing
nothing when clicked.

## External judge workflow unchanged

"Copy Solution" copies the editor's current source to the clipboard for pasting into the external
judge. Recording the judge's verdict manually was already fully covered by the Solving Workspace's
existing Finish section (#145) — Submitted/Accepted/Could Not Solve with a picked verdict — so no new
UI was needed for that half of the requirement.

## Known limitations

- The editor is a plain monospace `TextArea` (`.code-editor` style), not a real syntax-highlighting
  code editor control — JavaFX has no built-in one, and pulling in a third-party rich-text/code editor
  component remains out of scope; still the single largest gap against the issue's stated workspace
  features.
- "Incompatible JDK" isn't really a distinct detected condition: `JavaCodeRunner` always compiles with
  whatever JVM CodeFit itself is running on (`java.home`), so it can report *missing* `java`/`javac`
  with actionable guidance, but there's no minimum-version check or fallback to a separately installed
  JDK if the running JVM happens to be a JRE.
- `CompileOutcomeRegistry.closeCurrent()`'s `shutdownNow()`-adjacent best-effort semantics apply here
  too: an abrupt JVM kill (not a normal quit) can still skip the close, the same caveat
  `BackgroundImportExecutor` already documents for its own shutdown path.

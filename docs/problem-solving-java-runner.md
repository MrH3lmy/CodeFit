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
as it wants (different stdin each time) before closing it — this is what "run multiple local test
cases" means architecturally, even though today's workspace UI only wires up one configured
stdin/expected-output pair at a time (see Known limitations).

Closing a `CompileOutcome` deletes its work directory whether compilation succeeded, failed, or the
caller never got around to running against it — `JavaCodeRunnerTest` verifies this for both the
success and failure paths, and `ProblemSolvingWorkspaceController` always closes the previous
`CompileOutcome` before replacing it with a new one from a re-compile.

## Compiler diagnostics linked to the editor

`JavaCodeRunner.parseDiagnostics` turns javac's plain-text output into structured `CompileDiagnostic`s
(file, line, column when derivable from the caret line javac prints under a diagnostic, severity,
message). The workspace renders each as a clickable row; clicking moves the source `TextArea`'s caret
to that exact line/column, which is the "link to the corresponding editor line" requirement — no
separate line-number gutter or full IDE integration needed for that specific behavior.

## Timeout, cancellation, and process-tree cleanup

`RunLimits` carries the wall-clock timeout, heap cap, and output byte cap. `RunCancellationToken` is a
one-shot handle: the UI thread holds one per in-flight run and calls `cancel()` from a "Cancel"
button while the actual compile/run blocks on a background thread; both a timeout and an explicit
cancellation call `RunCancellationToken.killTree`, which destroys the process's descendants (via
`ProcessHandle`) before force-destroying the process itself — the "kill the complete child-process
tree" requirement.

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

- The UI wires up exactly one configured stdin/expected-output pair per run, even though the
  underlying `compile-once-run-many` architecture (and `JavaCodeRunnerTest`) already supports running
  several test cases against one compilation. A multi-case list UI is a follow-up.
- The editor is a plain monospace `TextArea` (`.code-editor` style), not a real syntax-highlighting
  code editor control — JavaFX has no built-in one, and pulling in a third-party rich-text/code editor
  component was out of scope for this iteration.
- `CompileOutcome`/temp-directory cleanup on an unclean shutdown, or on navigating away from the
  workspace entirely without triggering another compile, relies on the same best-effort lifecycle the
  rest of this controller already has (e.g. the phase timer's `Timeline` isn't explicitly torn down on
  navigation either) — not a regression introduced here, but not newly solved either.

package com.codefit.service;

import com.codefit.model.JavaSolutionDraft;
import com.codefit.model.JavaTestCase;
import com.codefit.model.UserProgress;
import com.codefit.repository.JavaSolutionDraftRepository;
import com.codefit.repository.JavaTestCaseRepository;
import com.codefit.repository.UserProgressRepository;

import java.util.List;
import java.util.Optional;

/**
 * Owns a learner's per-problem {@link JavaSolutionDraft} (#163) — loading it (or a fresh template)
 * and autosaving edits — separate from actually compiling/running it, which is
 * {@link JavaCodeRunner}'s job. Kept as its own thin service (rather than folded into the runner)
 * because draft persistence has nothing to do with process execution: a controller can autosave on
 * every keystroke without ever touching the runner, and can run code without that implying a save.
 */
public class JavaSolutionWorkspaceService {

    private final JavaSolutionDraftRepository draftRepository;
    private final JavaTestCaseRepository testCaseRepository;
    private final UserProgressRepository userProgressRepository;
    private final CodeRunner codeRunner;

    public JavaSolutionWorkspaceService() {
        this(new JavaSolutionDraftRepository(), new JavaTestCaseRepository(), new UserProgressRepository(), new JavaCodeRunner());
    }

    public JavaSolutionWorkspaceService(JavaSolutionDraftRepository draftRepository, JavaTestCaseRepository testCaseRepository,
                                        UserProgressRepository userProgressRepository, CodeRunner codeRunner) {
        this.draftRepository = draftRepository;
        this.testCaseRepository = testCaseRepository;
        this.userProgressRepository = userProgressRepository;
        this.codeRunner = codeRunner;
    }

    /** The problem's saved draft, or a fresh minimal template if none has been saved yet. */
    public JavaSolutionDraft loadDraft(long problemId) {
        return draftRepository.findByProblemId(problemId).orElseGet(() -> JavaSolutionDraft.template(problemId));
    }

    /** Creates or overwrites the problem's one draft row in place — this is "autosave": call it as
     *  often as needed (e.g. on every edit, or on a debounce timer), it never accumulates history. */
    public JavaSolutionDraft saveDraft(long problemId, String mainClassName, String sourceCode, String stdin, String expectedOutput) {
        Optional<JavaSolutionDraft> existing = draftRepository.findByProblemId(problemId);
        if (existing.isPresent()) {
            JavaSolutionDraft draft = existing.get();
            draft.setMainClassName(mainClassName);
            draft.setSourceCode(sourceCode);
            draft.setStdin(stdin);
            draft.setExpectedOutput(expectedOutput);
            draftRepository.update(draft);
            return draftRepository.findByProblemId(problemId).orElseThrow();
        }
        return draftRepository.save(new JavaSolutionDraft(0, problemId, mainClassName, sourceCode, stdin, expectedOutput, null));
    }

    // ---- Configurable run limits (#163's "configurable timeout") --------------------------------

    public int getRunTimeoutSeconds() {
        return userProgressRepository.getProgress().getJavaRunTimeoutSeconds();
    }

    public void setRunTimeoutSeconds(int timeoutSeconds) {
        UserProgress progress = userProgressRepository.getProgress();
        progress.setJavaRunTimeoutSeconds(timeoutSeconds);
        userProgressRepository.save(progress);
    }

    /** {@link RunLimits#defaults()} with the learner's own timeout preference in place of the fixed
     *  default — memory and output-byte caps stay at their defaults, since the issue only calls out
     *  the timeout as needing to be configurable. */
    public RunLimits currentRunLimits() {
        RunLimits defaults = RunLimits.defaults();
        return new RunLimits(getRunTimeoutSeconds(), defaults.memoryLimitMb(), defaults.maxOutputBytes());
    }

    public boolean isRunnerAvailable() {
        return codeRunner.isAvailable();
    }

    public String getRunnerUnavailabilityReason() {
        return codeRunner.getUnavailabilityReason();
    }

    public CompileOutcome compile(String sourceCode, String mainClassName) {
        return codeRunner.compile(sourceCode, mainClassName);
    }

    public RunResult run(CompileOutcome compiled, String stdin, RunLimits limits, RunCancellationToken cancellationToken) {
        return codeRunner.run(compiled, stdin, limits, cancellationToken);
    }

    // ---- Local test cases (#163's "run multiple local test cases") -----------------------------

    public List<JavaTestCase> listTestCases(long problemId) {
        return testCaseRepository.findByProblemId(problemId);
    }

    /** Appends a fresh, blank test case after whatever the problem already has. */
    public JavaTestCase addTestCase(long problemId) {
        int nextPosition = testCaseRepository.countByProblemId(problemId);
        return testCaseRepository.save(new JavaTestCase(0, problemId, nextPosition, "", ""));
    }

    public void updateTestCase(long testCaseId, long problemId, int position, String stdin, String expectedOutput) {
        testCaseRepository.update(new JavaTestCase(testCaseId, problemId, position, stdin, expectedOutput));
    }

    public void removeTestCase(long testCaseId) {
        testCaseRepository.deleteById(testCaseId);
    }

    /** Runs one already-compiled solution against a single test case's input — a thin pass-through to
     *  {@link #run}, kept here so callers work in terms of {@link JavaTestCase} instead of raw stdin. */
    public RunResult runTestCase(CompileOutcome compiled, JavaTestCase testCase, RunLimits limits, RunCancellationToken cancellationToken) {
        return codeRunner.run(compiled, testCase.getStdin(), limits, cancellationToken);
    }
}

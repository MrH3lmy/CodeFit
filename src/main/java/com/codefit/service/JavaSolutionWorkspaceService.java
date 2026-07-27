package com.codefit.service;

import com.codefit.model.JavaSolutionDraft;
import com.codefit.repository.JavaSolutionDraftRepository;

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
    private final CodeRunner codeRunner;

    public JavaSolutionWorkspaceService() {
        this(new JavaSolutionDraftRepository(), new JavaCodeRunner());
    }

    public JavaSolutionWorkspaceService(JavaSolutionDraftRepository draftRepository, CodeRunner codeRunner) {
        this.draftRepository = draftRepository;
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
}

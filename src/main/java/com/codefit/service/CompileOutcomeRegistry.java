package com.codefit.service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks whichever {@link CompileOutcome} is currently "live" — compiled but not yet superseded or
 * explicitly closed — so its temporary work directory still gets cleaned up even if the learner
 * navigates away from the Solving Workspace or quits the application without compiling again (#163).
 * A fresh {@link com.codefit.controller.ProblemSolvingWorkspaceController} is created on every
 * navigation, so an instance field alone can't survive past that; this is the single shared owner
 * {@code CodeFitApplication#stop()} and the workspace's own navigation actions both close through,
 * the same "one owner, always closable" shape {@link BackgroundImportExecutor} uses for the import
 * worker thread.
 */
public final class CompileOutcomeRegistry {

    private static final AtomicReference<CompileOutcome> CURRENT = new AtomicReference<>();

    private CompileOutcomeRegistry() {
    }

    /** Registers {@code outcome} as the new live one, closing (and thus deleting the temp directory
     *  of) whatever was previously registered. Pass {@code null} to simply close and clear the
     *  current one without registering a replacement. */
    public static void replace(CompileOutcome outcome) {
        CompileOutcome previous = CURRENT.getAndSet(outcome);
        if (previous != null && previous != outcome) {
            previous.close();
        }
    }

    /** Closes and clears whatever is currently registered, if anything. */
    public static void closeCurrent() {
        replace(null);
    }
}

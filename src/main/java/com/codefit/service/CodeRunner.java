package com.codefit.service;

/**
 * Pluggable compile-and-run abstraction (#163): {@link JavaCodeRunner} is the only implementation
 * today, but nothing about this interface is Java-specific — a future language's runner (a
 * Python/Node interpreter invocation, say) implements the same three operations without the Solving
 * Workspace controller needing to know or care which one it's talking to.
 *
 * <p>None of these implementations are a security sandbox. They give process isolation, a
 * restricted environment, a hard wall-clock timeout, and a capped output size — appropriate for a
 * trusted local single-user app running the learner's own code on their own machine, not for
 * executing untrusted third-party submissions. See {@link JavaCodeRunner}'s class docs for the full
 * threat-model caveat.
 */
public interface CodeRunner {

    /** Whether a compatible toolchain (e.g. a local JDK with both a compiler and a launcher) was found. */
    boolean isAvailable();

    /** An actionable explanation of why {@link #isAvailable()} is false, or {@code null} if it's true. */
    String getUnavailabilityReason();

    /**
     * Compiles {@code source} (expected to declare a public class named {@code mainClassName}) into
     * a fresh temporary work directory. The returned {@link CompileOutcome} must be closed (it's
     * {@link AutoCloseable}) once the caller is done running against it — compiling once and running
     * several test cases against the same compiled artifacts is the point of keeping compile and run
     * as separate steps, rather than one recompile-per-run call.
     */
    CompileOutcome compile(String source, String mainClassName);

    /**
     * Runs the already-compiled program with {@code stdin} fed to its standard input, subject to
     * {@code limits}. If {@code cancellationToken} is cancelled while the process is running (from
     * any thread — this method is expected to be called off the UI thread, and cancellation is
     * expected to come from it), the process (and any child processes it spawned) is killed and the
     * result reports {@link RunResult#cancelled()}.
     */
    RunResult run(CompileOutcome compiled, String stdin, RunLimits limits, RunCancellationToken cancellationToken);
}

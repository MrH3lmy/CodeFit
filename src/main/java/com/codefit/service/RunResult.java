package com.codefit.service;

/**
 * The outcome of one {@link CodeRunner#run} call (#163): captured stdout/stderr, the exit code (null
 * if the process never exited normally — timed out or was cancelled), whether output was truncated
 * against {@link RunLimits#maxOutputBytes()}, and how long the run actually took.
 *
 * <p>Exactly one of {@link #timedOut()}/{@link #cancelled()} is true, or neither (a normal exit).
 * They're kept as separate flags rather than a single enum so a caller can still inspect whatever
 * partial stdout/stderr was captured either way.
 */
public record RunResult(boolean timedOut, boolean cancelled, Integer exitCode, String stdout, String stderr,
                        boolean outputTruncated, long elapsedMillis) {

    public boolean matchesExpectedOutput(String expectedOutput) {
        if (timedOut || cancelled || exitCode == null || exitCode != 0) {
            return false;
        }
        String expected = expectedOutput == null ? "" : expectedOutput.strip();
        return expected.equals(stdout == null ? "" : stdout.strip());
    }
}

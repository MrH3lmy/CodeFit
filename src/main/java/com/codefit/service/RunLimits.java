package com.codefit.service;

/** Execution limits for one {@link CodeRunner#run} call (#163): a wall-clock timeout, a heap cap
 *  passed to the child JVM, and a cap on captured stdout/stderr bytes (beyond which output is
 *  truncated rather than allowed to grow unbounded in memory). */
public record RunLimits(int timeoutSeconds, int memoryLimitMb, int maxOutputBytes) {

    private static final RunLimits DEFAULTS = new RunLimits(5, 256, 64 * 1024);

    public static RunLimits defaults() {
        return DEFAULTS;
    }
}

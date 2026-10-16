package com.codefit.service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A one-shot cancellation handle for a single {@link CodeRunner#run} call (#163): the UI thread holds
 * onto one of these and calls {@link #cancel()} from a "Cancel" button while the actual run is
 * blocking on a background thread. {@link Process#destroy}/{@link Process#destroyForcibly} are
 * documented as safe to call from any thread, so no additional synchronization is needed beyond the
 * plain volatile/atomic fields here.
 *
 * <p>One-shot: may cover a sequential compile/check group, but never reset or reuse after cancellation.
 */
public final class RunCancellationToken {

    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private volatile boolean cancelled;
    private final java.util.function.Consumer<ProcessHandle> started;

    public RunCancellationToken() { this(p -> {}); }

    /** Short durable ownership checkpoint; callback must never wait for process completion. */
    public RunCancellationToken(java.util.function.Consumer<ProcessHandle> started) {
        this.started = java.util.Objects.requireNonNull(started);
    }

    /** Called by the runner once the child process has started; if {@link #cancel()} already ran
     *  before this (a race between a very fast cancel and process startup), kills it immediately. */
    void attach(Process process) {
        processRef.set(process);
        try { started.accept(process.toHandle()); }
        catch (RuntimeException failure) { killTree(process); throw failure; }
        if (cancelled) {
            killTree(process);
        }
    }

    /** Kills the running process (and, best-effort, its descendants) immediately. Safe to call more
     *  than once, safe to call before a process has started (the eventual {@link #attach} will kill
     *  it right away instead), and safe to call from any thread. */
    public void cancel() {
        cancelled = true;
        Process process = processRef.get();
        if (process != null) {
            killTree(process);
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    static void killTree(Process process) {
        // Descendants first: a process already being torn down can still have spawned children by
        // the time destroyForcibly() is called on it, and only ProcessHandle's own API can reach them.
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroyForcibly();
    }
}

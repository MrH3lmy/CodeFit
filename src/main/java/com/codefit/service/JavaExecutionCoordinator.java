package com.codefit.service;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serializes Java compile/run work across JavaFX controller instances. A workspace controller is
 * recreated whenever navigation loads its FXML, so an instance-only busy flag cannot protect the
 * compiled temporary directory or provide reliable shutdown cancellation.
 */
public final class JavaExecutionCoordinator {

    private static final Object LOCK = new Object();
    private static Operation activeOperation;

    private JavaExecutionCoordinator() {
    }

    /**
     * Starts one application-wide Java operation, or returns {@code null} when another compile/run is
     * already active. Pass a token for cancellable program executions and {@code null} for compile.
     */
    public static Operation tryStart(RunCancellationToken cancellationToken) {
        synchronized (LOCK) {
            if (activeOperation != null) {
                return null;
            }
            activeOperation = new Operation(cancellationToken);
            return activeOperation;
        }
    }

    public static boolean hasActiveOperation() {
        synchronized (LOCK) {
            return activeOperation != null;
        }
    }

    public static void cancelActive() {
        Operation operation;
        synchronized (LOCK) {
            operation = activeOperation;
        }
        if (operation != null) {
            operation.cancel();
        }
    }

    /** Cancels the active run when possible and waits for its worker to release the operation. */
    public static boolean cancelActiveAndAwait(long timeout, TimeUnit unit) {
        Operation operation;
        synchronized (LOCK) {
            operation = activeOperation;
        }
        if (operation == null) {
            return true;
        }

        operation.cancel();
        long remainingNanos = unit.toNanos(timeout);
        long deadline = System.nanoTime() + remainingNanos;
        synchronized (LOCK) {
            while (activeOperation == operation) {
                if (remainingNanos <= 0) {
                    return false;
                }
                long millis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
                int nanos = (int) (remainingNanos - TimeUnit.MILLISECONDS.toNanos(millis));
                try {
                    LOCK.wait(millis, nanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                remainingNanos = deadline - System.nanoTime();
            }
            return true;
        }
    }

    private static void finish(Operation operation) {
        synchronized (LOCK) {
            if (activeOperation == operation) {
                activeOperation = null;
                LOCK.notifyAll();
            }
        }
    }

    public static final class Operation implements AutoCloseable {
        private final RunCancellationToken cancellationToken;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Operation(RunCancellationToken cancellationToken) {
            this.cancellationToken = cancellationToken;
        }

        public boolean isCancellable() {
            return cancellationToken != null;
        }

        public void cancel() {
            if (cancellationToken != null) {
                cancellationToken.cancel();
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                JavaExecutionCoordinator.finish(this);
            }
        }
    }
}

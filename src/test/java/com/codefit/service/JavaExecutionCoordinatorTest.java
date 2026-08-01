package com.codefit.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaExecutionCoordinatorTest {

    @Test
    void allowsOnlyOneJavaOperationAtATime() {
        JavaExecutionCoordinator.Operation first = JavaExecutionCoordinator.tryStart(null);
        assertNotNull(first);
        try {
            assertTrue(JavaExecutionCoordinator.hasActiveOperation());
            assertNull(JavaExecutionCoordinator.tryStart(new RunCancellationToken()));
        } finally {
            first.close();
        }

        assertFalse(JavaExecutionCoordinator.hasActiveOperation());
        JavaExecutionCoordinator.Operation next = JavaExecutionCoordinator.tryStart(null);
        assertNotNull(next);
        next.close();
    }

    @Test
    void cancellationIsForwardedAndShutdownCanWaitForWorkerCompletion() throws Exception {
        RunCancellationToken token = new RunCancellationToken();
        JavaExecutionCoordinator.Operation operation = JavaExecutionCoordinator.tryStart(token);
        assertNotNull(operation);

        Thread worker = new Thread(() -> {
            while (!token.isCancelled()) {
                Thread.onSpinWait();
            }
            operation.close();
        });
        worker.start();

        assertTrue(JavaExecutionCoordinator.cancelActiveAndAwait(1, TimeUnit.SECONDS));
        worker.join(TimeUnit.SECONDS.toMillis(1));
        assertTrue(token.isCancelled());
        assertFalse(JavaExecutionCoordinator.hasActiveOperation());
    }
}

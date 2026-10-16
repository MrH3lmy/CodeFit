package com.codefit.ui;

import java.util.concurrent.*;

/** Shared bounded workers; route instances never create unbounded threads. */
public final class PlayerTasks {
    private static final ThreadPoolExecutor WORKERS =
            new ThreadPoolExecutor(
                    2,
                    2,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(32),
                    runnable -> {
                        var thread = new Thread(runnable, "codefit-evolution");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    private PlayerTasks() {}

    public static void execute(Runnable work) {
        WORKERS.execute(work);
    }

    public static void shutdown() {
        WORKERS.shutdown();
        try {
            if (!WORKERS.awaitTermination(20, TimeUnit.SECONDS)) WORKERS.shutdownNow();
        } catch (InterruptedException e) {
            WORKERS.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

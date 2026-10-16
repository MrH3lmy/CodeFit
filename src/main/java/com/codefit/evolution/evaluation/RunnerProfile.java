package com.codefit.evolution.evaluation;

import com.codefit.service.RunLimits;

/** Exact persisted evaluation configuration; no mutable preferences are read during replay. */
public record RunnerProfile(
        int compileSeconds, int memoryMb, int outputBytes, int observedRepetitions) {
    public static RunnerProfile defaults() {
        return new RunnerProfile(15, 128, 32768, 3);
    }

    public RunnerProfile {
        if (compileSeconds < 1
                || compileSeconds > 30
                || memoryMb < 64
                || memoryMb > 512
                || outputBytes < 1024
                || outputBytes > 65536
                || observedRepetitions < 1
                || observedRepetitions > 5)
            throw new IllegalArgumentException("Bounded runner profile required");
    }

    public RunLimits compileLimits() {
        return new RunLimits(compileSeconds, memoryMb, outputBytes);
    }

    public RunLimits runLimits(int seconds) {
        return new RunLimits(seconds, memoryMb, outputBytes);
    }
}

package com.codefit.peer.transport;

import java.time.Duration;

/**
 * Bounded exponential backoff with jitter for outbound dialing (#182: "Retry with backoff/jitter/caps
 * and allow cancellation"). Delay doubles each attempt, capped at {@code maxBackoff}, then randomized
 * within {@code [delay*(1-jitterFraction), delay*(1+jitterFraction)]} so many peers retrying together
 * do not all hammer the network in lockstep.
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, double jitterFraction) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1.");
        }
        if (initialBackoff.isNegative() || maxBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Backoff durations must be non-negative and maxBackoff >= initialBackoff.");
        }
        if (jitterFraction < 0 || jitterFraction > 1) {
            throw new IllegalArgumentException("jitterFraction must be within [0, 1].");
        }
    }

    public static RetryPolicy standard() {
        return new RetryPolicy(6, Duration.ofSeconds(1), Duration.ofSeconds(60), 0.25);
    }

    Duration delayForAttempt(int attemptNumberStartingAtOne, java.util.random.RandomGenerator random) {
        long baseMillis = Math.min(maxBackoff.toMillis(),
                initialBackoff.toMillis() * (1L << Math.min(20, attemptNumberStartingAtOne - 1)));
        double jitterRange = baseMillis * jitterFraction;
        double jittered = baseMillis - jitterRange + random.nextDouble() * (2 * jitterRange);
        return Duration.ofMillis(Math.max(0, Math.round(jittered)));
    }
}

package com.codefit.peer.identity;

/**
 * Thrown when the local clock is at or behind a writer epoch this identity has already used (its own
 * stored value, or a restored backup's), per {@code docs/p2p/protocol-v1.md} §10.1. The recovery paths
 * are: fix the clock, or wait until it passes the earlier epoch. Nothing is persisted when this is
 * thrown, so a restore or rotation that fails this way leaves the previous identity state untouched.
 */
public class ClockBehindPreviousEpochException extends RuntimeException {
    public ClockBehindPreviousEpochException(String message) {
        super(message);
    }
}

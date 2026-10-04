package com.codefit.service;

/**
 * Test-only access to {@link PeerLocalWriteLock#MONITOR} for test-support code outside this package
 * (e.g. {@code com.codefit.testsupport}'s two-process demos) that needs to serialize its own local
 * writes against the exact same boundary production code already does - never shipped, never used
 * by production code itself.
 *
 * <p>Why a demo needs this at all: a two-process demo that records real study evidence (an ordinary
 * {@code ReviewHistoryRepository} write - deliberately outside {@link PeerLocalWriteLock}'s own
 * documented boundary, since ordinary user study activity is not expected to coincide with an
 * active connection's receive loop in real usage) immediately after establishing a live connection
 * can, unlike real usage, genuinely race that connection's own automatic receive loop on nearly
 * every run - the two happen only seconds apart by construction, not spread over a real study
 * session's whole duration. Without this, that self-inflicted contention can trip the exact
 * SQLITE_BUSY-kills-the-receive-loop failure mode {@link PeerLocalWriteLock}'s own javadoc
 * documents, for a demo-only reason that has nothing to do with the feature under test.
 */
public final class PeerLocalWriteLockTestAccess {
    private PeerLocalWriteLockTestAccess() {
    }

    public static void runLocked(Runnable action) {
        synchronized (PeerLocalWriteLock.MONITOR) {
            action.run();
        }
    }
}

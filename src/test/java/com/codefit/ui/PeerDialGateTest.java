package com.codefit.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit coverage of {@link PeerDialGate} - the mutex {@code PeerController.connectTo} checks
 * before doing anything else, so proving it here proves "two rapid Connect actions cannot produce two
 * simultaneous dial attempts" structurally, independent of any JavaFX/network timing.
 */
class PeerDialGateTest {

    @Test
    void aSecondAcquireForTheSameContactFailsWhileTheFirstStillHoldsIt() {
        PeerDialGate gate = new PeerDialGate();

        assertTrue(gate.tryAcquire(42L), "the first acquire must succeed");
        assertFalse(gate.tryAcquire(42L), "a second acquire for the same contact must fail while the first is still held");
        assertTrue(gate.isInFlight(42L));
    }

    @Test
    void releasingLetsAFutureAcquireSucceedAgain() {
        PeerDialGate gate = new PeerDialGate();

        assertTrue(gate.tryAcquire(7L));
        gate.release(7L);
        assertFalse(gate.isInFlight(7L));
        assertTrue(gate.tryAcquire(7L), "after release, a fresh acquire for the same contact must succeed");
    }

    @Test
    void differentContactsNeverContendWithEachOther() {
        PeerDialGate gate = new PeerDialGate();

        assertTrue(gate.tryAcquire(1L));
        assertTrue(gate.tryAcquire(2L), "a different contact id must never be blocked by another contact's in-flight dial");
    }

    @Test
    void releasingSomethingNeverHeldIsHarmless() {
        PeerDialGate gate = new PeerDialGate();
        gate.release(999L); // must not throw
        assertFalse(gate.isInFlight(999L));
    }

    @Test
    void clearForgetsEveryHeldContact() {
        PeerDialGate gate = new PeerDialGate();
        gate.tryAcquire(1L);
        gate.tryAcquire(2L);

        gate.clear();

        assertFalse(gate.isInFlight(1L));
        assertFalse(gate.isInFlight(2L));
        assertTrue(gate.tryAcquire(1L), "after clear, acquiring again must succeed");
    }

    /**
     * The actual "two rapid Connect clicks" race, forced deterministically: two threads are released
     * to call {@link PeerDialGate#tryAcquire} for the <em>same</em> contact id at the same instant via
     * a {@link CyclicBarrier}, repeated many times to make a missed race overwhelmingly unlikely to
     * slip through undetected. Exactly one must win every single time - never zero, never two.
     */
    @Test
    @Timeout(30)
    void concurrentAcquiresForTheSameContactNeverBothSucceed() throws Exception {
        PeerDialGate gate = new PeerDialGate();
        int rounds = 2_000;
        AtomicInteger simultaneousWins = new AtomicInteger();
        AtomicInteger neitherWon = new AtomicInteger();

        for (int round = 0; round < rounds; round++) {
            long contactId = round; // a fresh id each round so rounds never interfere with each other
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger wins = new AtomicInteger();

            Runnable attempt = () -> {
                try {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException | BrokenBarrierException | java.util.concurrent.TimeoutException e) {
                    throw new RuntimeException(e);
                }
                if (gate.tryAcquire(contactId)) {
                    wins.incrementAndGet();
                }
            };

            Thread first = new Thread(attempt);
            Thread second = new Thread(attempt);
            first.start();
            second.start();
            first.join(5_000);
            second.join(5_000);

            if (wins.get() > 1) {
                simultaneousWins.incrementAndGet();
            }
            if (wins.get() == 0) {
                neitherWon.incrementAndGet();
            }
        }

        assertEquals(0, simultaneousWins.get(), "two concurrent acquires for the same contact must never both succeed");
        assertEquals(0, neitherWon.get(), "exactly one concurrent acquire for the same contact must always succeed");
    }
}

package com.codefit.peer.sync;

import com.codefit.peer.protocol.RejectionReason;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #184: {@code PeerSyncSessionService}'s receive loop decides whether a connection can keep reading
 * further frames after a rejection purely from {@link SyncOutcome#connectionRecoverable()} - this
 * locks down that every {@link RejectionReason} maps to the identically-named outcome and that only
 * the two framing-level reasons (where the bytes read as a header cannot be trusted to be one) are
 * connection-fatal.
 */
class SyncOutcomeTest {

    @Test
    void everyRejectionReasonMapsToTheIdenticallyNamedOutcome() {
        for (RejectionReason reason : RejectionReason.values()) {
            assertEquals(reason.name(), SyncOutcome.from(reason).name());
        }
    }

    @Test
    void onlyMalformedAndUnsupportedVersionAreConnectionFatal() {
        assertFalse(SyncOutcome.MALFORMED.connectionRecoverable());
        assertFalse(SyncOutcome.UNSUPPORTED_VERSION.connectionRecoverable());

        for (SyncOutcome outcome : EnumSet.complementOf(EnumSet.of(SyncOutcome.MALFORMED, SyncOutcome.UNSUPPORTED_VERSION))) {
            assertTrue(outcome.connectionRecoverable(), outcome + " should let the connection keep reading further frames");
        }
    }

    @Test
    void onlyAcceptedIsAccepted() {
        for (SyncOutcome outcome : SyncOutcome.values()) {
            assertEquals(outcome == SyncOutcome.ACCEPTED, outcome.accepted());
        }
    }
}

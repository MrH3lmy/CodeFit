package com.codefit.peer.protocol;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Duplicate, stale, forked, expired, future-dated, unauthorized, and tombstoned handling. */
class EnvelopeAcceptancePolicyTest {
    private static final Instant NOW = ProtocolFixtures.CREATED.plus(Duration.ofMinutes(1));
    private final EnvelopeAcceptancePolicy receiverB = new EnvelopeAcceptancePolicy(ProtocolFixtures.PEER_B.id());

    private static SignedEnvelope summary(long epoch, long sequence, long revision, int metricValue) {
        EnvelopeHeader header = new EnvelopeHeader(0, ProtocolFixtures.AUTHOR, ProtocolFixtures.objectId(3), epoch, sequence,
                revision, ProtocolFixtures.CREATED, ProtocolFixtures.EXPIRES, ProtocolFixtures.toB());
        ProgressSummary base = (ProgressSummary) ProtocolFixtures.all().get("progress-summary-day-partial").body();
        ProgressSummary body = new ProgressSummary(base.window(), base.cutoff(), List.of(new MetricValue("review.attempts", 1,
                MetricUnit.COUNT, MetricAvailability.MEASURED, metricValue, metricValue, MetricProvenance.LOCAL_RECORD,
                TimestampBasis.EXACT_UTC)));
        return ProtocolFixtures.sign(new Envelope(header, body));
    }

    private static AuthorReplayState state() {
        return new AuthorReplayState(ProtocolFixtures.AUTHOR);
    }

    @Test
    void firstDeliveryIsAcceptedAndRedeliveryIsAnIdempotentDuplicate() {
        AuthorReplayState state = state();
        SignedEnvelope envelope = summary(1, 10, 1, 5);
        AcceptanceVerdict first = receiverB.evaluate(envelope, state, NOW);
        assertTrue(first.accepted());
        assertNull(first.reason());
        assertEquals(RejectionReason.DUPLICATE, receiverB.evaluate(envelope, state, NOW).reason());
        assertEquals(RejectionReason.DUPLICATE, receiverB.evaluate(EnvelopeCodec.decodeFrame(EnvelopeCodec.encodeFrame(envelope)), state, NOW).reason());
    }

    @Test
    void receiversOutsideTheAudienceRejectEvenWithValidBytes() {
        EnvelopeAcceptancePolicy receiverC = new EnvelopeAcceptancePolicy(ProtocolFixtures.PEER_C.id());
        assertEquals(RejectionReason.UNAUTHORIZED_AUDIENCE, receiverC.evaluate(summary(1, 1, 1, 5), state(), NOW).reason());
    }

    @Test
    void timeBoundsUseTheReceiverClockWithBoundedSkew() {
        SignedEnvelope envelope = summary(1, 1, 1, 5);
        assertTrue(receiverB.evaluate(envelope, state(), ProtocolFixtures.CREATED.minus(Duration.ofMinutes(4))).accepted());
        assertEquals(RejectionReason.NOT_YET_VALID,
                receiverB.evaluate(envelope, state(), ProtocolFixtures.CREATED.minus(Duration.ofMinutes(6))).reason());
        assertEquals(RejectionReason.EXPIRED, receiverB.evaluate(envelope, state(), ProtocolFixtures.EXPIRES).reason());
    }

    @Test
    void olderOrEqualRevisionsAreStaleAndNewerOnesSupersede() {
        AuthorReplayState state = state();
        assertTrue(receiverB.evaluate(summary(1, 20, 2, 5), state, NOW).accepted());
        assertEquals(RejectionReason.STALE_REVISION, receiverB.evaluate(summary(1, 19, 1, 4), state, NOW).reason());
        assertEquals(RejectionReason.STALE_REVISION, receiverB.evaluate(summary(1, 21, 2, 6), state, NOW).reason());
        assertTrue(receiverB.evaluate(summary(1, 22, 3, 7), state, NOW).accepted());
    }

    @Test
    void twoDifferentMessagesInOneSequenceSlotAreAFork() {
        AuthorReplayState state = state();
        assertTrue(receiverB.evaluate(summary(1, 30, 1, 5), state, NOW).accepted());
        assertEquals(RejectionReason.FORKED, receiverB.evaluate(summary(1, 30, 2, 6), state, NOW).reason());
    }

    @Test
    void restoreBumpsTheEpochSoRolledBackSequencesDoNotForkAndOldEpochsGoStale() {
        AuthorReplayState state = state();
        assertTrue(receiverB.evaluate(summary(1, 50, 1, 5), state, NOW).accepted());
        // Restored from an older backup: sequence rolled back to 3, but the epoch was bumped.
        assertTrue(receiverB.evaluate(summary(2, 3, 2, 6), state, NOW).accepted());
        assertEquals(2, state.highestEpoch());
        // A late message from the abandoned epoch is stale rather than a fork.
        assertEquals(RejectionReason.STALE_EPOCH, receiverB.evaluate(summary(1, 51, 3, 7), state, NOW).reason());
    }

    @Test
    void tombstonedObjectsAreNeverResurrected() {
        AuthorReplayState state = state();
        assertTrue(receiverB.evaluate(summary(1, 1, 1, 5), state, NOW).accepted());
        SignedEnvelope tombstone = ProtocolFixtures.all().get("tombstone");
        assertTrue(receiverB.evaluate(tombstone, state, NOW).accepted());
        assertTrue(state.isTombstoned(ProtocolFixtures.objectId(3)));
        assertEquals(RejectionReason.TOMBSTONED, receiverB.evaluate(summary(1, 60, 9, 8), state, NOW).reason());
        assertEquals(RejectionReason.DUPLICATE, receiverB.evaluate(tombstone, state, NOW).reason());
    }

    @Test
    void consentRevisionsOrderByRevisionAndEmptyScopesRevoke() {
        AuthorReplayState state = state();
        SignedEnvelope grant = ProtocolFixtures.all().get("consent-revision");
        SignedEnvelope revoke = ProtocolFixtures.all().get("consent-revocation");
        assertTrue(receiverB.evaluate(revoke, state, NOW).accepted());
        assertEquals(RejectionReason.STALE_REVISION, receiverB.evaluate(grant, state, NOW).reason(),
                "an older grant arriving after the revocation must not re-enable sharing");
        assertTrue(((ConsentRevision) revoke.body()).scopes().isEmpty());
    }

    @Test
    void stateIsScopedToOneAuthor() {
        assertThrows(IllegalArgumentException.class,
                () -> receiverB.evaluate(summary(1, 1, 1, 5), new AuthorReplayState(ProtocolFixtures.PEER_C), NOW));
    }
}

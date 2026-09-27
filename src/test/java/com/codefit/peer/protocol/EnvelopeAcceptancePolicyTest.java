package com.codefit.peer.protocol;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Duplicate, stale, forked, expired, future-dated, unauthorized, tombstone-cutoff, and restore handling. */
class EnvelopeAcceptancePolicyTest {
    private static final Instant NOW = ProtocolFixtures.CREATED.plus(Duration.ofMinutes(1));
    private static final Instant T0 = ProtocolFixtures.CREATED;
    private static final Instant T1 = T0.plus(Duration.ofDays(1));
    private static final Instant T2 = T1.plus(Duration.ofMinutes(10));
    private static final Instant LATER = T2.plus(Duration.ofMinutes(1));
    private final EnvelopeAcceptancePolicy receiverB = new EnvelopeAcceptancePolicy(ProtocolFixtures.PEER_B.id());

    private static SignedEnvelope summary(long epoch, long sequence, long revision, int metricValue) {
        return summaryAt(epoch, sequence, revision, metricValue, ProtocolFixtures.CREATED);
    }

    private static SignedEnvelope summaryAt(long epoch, long sequence, long revision, int metricValue, Instant createdAt) {
        EnvelopeHeader header = new EnvelopeHeader(0, ProtocolFixtures.AUTHOR, ProtocolFixtures.objectId(3), epoch, sequence,
                revision, createdAt, ProtocolFixtures.EXPIRES, ProtocolFixtures.toB());
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
    void repeatedRestoreOfTheSameBackupGetsAFreshEpochEachTime() {
        AuthorReplayState state = state();
        long backupEpoch = 1;
        assertTrue(receiverB.evaluate(summaryAt(backupEpoch, 50, 1, 5, T0), state, LATER).accepted());

        // First restore of the backup: a clock-derived epoch, then a publication.
        long firstRestore = WriterEpoch.next(backupEpoch, T1);
        assertTrue(receiverB.evaluate(summaryAt(firstRestore, 1, 1, 6, T1), state, LATER).accepted());
        // The same backup restored again later: it still holds backupEpoch = 1, yet gets a newer epoch,
        // so a different first publication does not collide with the first restore's slot.
        long secondRestore = WriterEpoch.next(backupEpoch, T2);
        assertTrue(secondRestore > firstRestore);
        assertTrue(receiverB.evaluate(summaryAt(secondRestore, 1, 1, 7, T2), state, LATER).accepted());
    }

    @Test
    void reusingAnEpochFromStaleBackupStateIsDetectedAsAFork() {
        // The rejected design ("backup epoch + 1"): two restores of one backup both choose epoch 2.
        AuthorReplayState state = state();
        assertTrue(receiverB.evaluate(summaryAt(2, 1, 1, 6, T1), state, LATER).accepted());
        assertEquals(RejectionReason.FORKED, receiverB.evaluate(summaryAt(2, 1, 1, 7, T2), state, LATER).reason());
    }

    @Test
    void aNewerEpochSupersedesRevisionsRolledBackByARestore() {
        AuthorReplayState state = state();
        assertTrue(receiverB.evaluate(summaryAt(1, 40, 5, 5, T0), state, LATER).accepted());
        // The backup only knew revision 1, so the restored device publishes revision 2 in its new epoch.
        long restored = WriterEpoch.next(1, T1);
        assertTrue(receiverB.evaluate(summaryAt(restored, 1, 2, 6, T1), state, LATER).accepted());
        // A late revision 6 from the abandoned epoch cannot overwrite it.
        assertEquals(RejectionReason.STALE_EPOCH, receiverB.evaluate(summaryAt(1, 41, 6, 7, T0), state, LATER).reason());
    }

    @Test
    void anEpochAheadOfItsOwnCreationTimeCannotBeConstructed() {
        ProtocolException failure = assertThrows(ProtocolException.class, () -> new EnvelopeHeader(0, ProtocolFixtures.AUTHOR,
                ProtocolFixtures.objectId(3), WriterEpoch.maxAt(T1) + 1, 1, 1, T1, ProtocolFixtures.EXPIRES, ProtocolFixtures.toB()));
        assertEquals(RejectionReason.INVALID_TIMESTAMP, failure.reason());
    }

    @Test
    void writerEpochsAreStrictlyIncreasingAndRefuseAClockBehindThePreviousSession() {
        assertEquals(1, WriterEpoch.maxAt(Instant.ofEpochMilli(ProtocolVersion.MIN_TIMESTAMP_MILLIS)));
        long first = WriterEpoch.next(0, T1);
        assertEquals(WriterEpoch.maxAt(T1), first);
        assertTrue(WriterEpoch.next(first, T1.plusSeconds(1)) > first);
        assertThrows(IllegalStateException.class, () -> WriterEpoch.next(first, T1), "same second: would reuse");
        assertThrows(IllegalStateException.class, () -> WriterEpoch.next(first, T0), "clock moved backwards");
    }

    @Test
    void tombstoneIsACutoffForReplaysButNotAPermanentBan() {
        AuthorReplayState state = state();
        assertTrue(receiverB.evaluate(summary(1, 1, 1, 5), state, NOW).accepted());
        SignedEnvelope tombstone = ProtocolFixtures.all().get("tombstone"); // same object, revision 2
        assertTrue(receiverB.evaluate(tombstone, state, NOW).accepted());
        assertTrue(state.isTombstoned(ProtocolFixtures.objectId(3)));
        assertEquals(RejectionReason.DUPLICATE, receiverB.evaluate(tombstone, state, NOW).reason());
        // Another pre-cutoff version (revision 1, never seen before) is still refused.
        assertEquals(RejectionReason.TOMBSTONED, receiverB.evaluate(summary(1, 60, 1, 9), state, NOW).reason());
        // A strictly newer version starts a new incarnation.
        assertTrue(receiverB.evaluate(summary(1, 61, 3, 8), state, NOW).accepted());
        assertFalse(state.isTombstoned(ProtocolFixtures.objectId(3)));
    }

    @Test
    void profileSharingResumesAfterRevokeDeleteAndRegrant() {
        AuthorReplayState state = state();
        ObjectId consentId = ConsentRevision.objectIdFor(ProtocolFixtures.AUTHOR.id(), ProtocolFixtures.PEER_B.id());
        ObjectId cardId = SocialProfileCard.objectIdFor(ProtocolFixtures.AUTHOR.id());

        SignedEnvelope grant = signed(consentId, 1, 1, new ConsentRevision(List.of(SharingScope.SOCIAL_PROFILE)));
        SignedEnvelope cardV1 = signed(cardId, 2, 1, card("Ada before"));
        SignedEnvelope revoke = signed(consentId, 3, 2, new ConsentRevision(List.of()));
        SignedEnvelope delete = signed(cardId, 4, 2, new Tombstone(MessageType.SOCIAL_PROFILE_CARD, TombstoneReason.REVOKED, true));
        SignedEnvelope regrant = signed(consentId, 5, 3, new ConsentRevision(List.of(SharingScope.SOCIAL_PROFILE)));
        SignedEnvelope cardV3 = signed(cardId, 6, 3, card("Ada after"));

        for (SignedEnvelope step : List.of(grant, cardV1, revoke, delete, regrant, cardV3)) {
            assertTrue(receiverB.evaluate(step, state, NOW).accepted(), "step rejected: " + step.body());
        }
        assertFalse(state.isTombstoned(cardId));
        // Pre-revocation cards stay dead: the exact old bytes, and an unseen pre-cutoff revision.
        assertEquals(RejectionReason.DUPLICATE, receiverB.evaluate(cardV1, state, NOW).reason());
        assertEquals(RejectionReason.STALE_REVISION,
                receiverB.evaluate(signed(cardId, 7, 1, card("Ada replayed")), state, NOW).reason());
        // Between delete and regrant, a pre-cutoff card is TOMBSTONED (checked on a fresh receiver state).
        AuthorReplayState midway = state();
        for (SignedEnvelope step : List.of(grant, cardV1, revoke, delete)) {
            assertTrue(receiverB.evaluate(step, midway, NOW).accepted());
        }
        assertEquals(RejectionReason.TOMBSTONED,
                receiverB.evaluate(signed(cardId, 8, 1, card("Ada replayed")), midway, NOW).reason());
    }

    private static SignedEnvelope signed(ObjectId objectId, long sequence, long revision, MessageBody body) {
        return ProtocolFixtures.sign(new Envelope(ProtocolFixtures.header(objectId, sequence, revision, ProtocolFixtures.toB()), body));
    }

    private static SocialProfileCard card(String name) {
        return new SocialProfileCard(name, null, "Europe/Berlin", java.time.DayOfWeek.MONDAY);
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

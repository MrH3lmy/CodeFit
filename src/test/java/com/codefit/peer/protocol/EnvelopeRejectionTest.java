package com.codefit.peer.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static com.codefit.peer.protocol.WireMutations.resign;
import static com.codefit.peer.protocol.WireMutations.unsignedPayload;
import static com.codefit.peer.protocol.WireMutations.withBody;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every malformed, oversized, unsupported, or non-canonical input maps to one stable rejection reason. */
class EnvelopeRejectionTest {

    private static void assertRejected(RejectionReason expected, byte[] frame) {
        assertReason(expected, () -> EnvelopeCodec.decodeFrame(frame));
    }

    private static void assertReason(RejectionReason expected, Executable action) {
        ProtocolException failure = assertThrows(ProtocolException.class, action);
        assertEquals(expected, failure.reason(), failure.getMessage());
    }

    private static byte[] golden() {
        return ProtocolFixtures.readGolden("social-profile-card");
    }

    // ----- framing -----

    @Test
    void badMagicIsMalformed() {
        byte[] frame = golden();
        frame[0] = 'X';
        assertRejected(RejectionReason.MALFORMED, frame);
    }

    @Test
    void otherMajorVersionsAreUnsupported() {
        for (int major : new int[] {0, 2, 255}) {
            byte[] frame = golden();
            frame[3] = (byte) major;
            assertRejected(RejectionReason.UNSUPPORTED_VERSION, frame);
        }
    }

    @Test
    void oversizedDeclaredLengthIsRejectedFromTheHeaderAlone() {
        byte[] header = Arrays.copyOf(golden(), ProtocolVersion.FRAME_HEADER_BYTES);
        header[4] = 0x7f;
        assertReason(RejectionReason.OVERSIZED, () -> EnvelopeCodec.payloadLength(header));
        byte[] justOver = new CanonicalWriter().fixed(new byte[] {0x43, 0x46, 0x50}, 3).u8(1)
                .u32(ProtocolVersion.MAX_FRAME_PAYLOAD_BYTES + 1L).toByteArray();
        assertReason(RejectionReason.OVERSIZED, () -> EnvelopeCodec.payloadLength(justOver));
    }

    @Test
    void truncatedTrailingAndMismatchedLengthsAreMalformed() {
        byte[] frame = golden();
        assertRejected(RejectionReason.MALFORMED, Arrays.copyOf(frame, frame.length - 1));
        assertRejected(RejectionReason.MALFORMED, Arrays.copyOf(frame, frame.length + 1));
        assertRejected(RejectionReason.MALFORMED, Arrays.copyOf(frame, 5));
        byte[] payloadPlusByte = Arrays.copyOf(unsignedPayload("social-profile-card"), unsignedPayload("social-profile-card").length + 1);
        assertRejected(RejectionReason.MALFORMED, resign(payloadPlusByte));
    }

    // ----- envelope header -----

    @Test
    void unknownAndReservedMessageTypesAreUnsupported() {
        for (int type : new int[] {0, 7, 8, 99}) {
            byte[] unsigned = unsignedPayload("social-profile-card");
            unsigned[WireMutations.TYPE_OFFSET] = (byte) type;
            assertRejected(RejectionReason.UNSUPPORTED_MESSAGE_TYPE, resign(unsigned));
        }
    }

    @Test
    void unknownBodySchemaVersionIsUnsupported() {
        byte[] unsigned = unsignedPayload("social-profile-card");
        unsigned[WireMutations.SCHEMA_OFFSET + 1] = 2;
        assertRejected(RejectionReason.UNSUPPORTED_SCHEMA_VERSION, resign(unsigned));
    }

    @Test
    void anyTamperedByteBreaksTheSignature() {
        byte[] frame = golden();
        for (int offset : new int[] {ProtocolVersion.FRAME_HEADER_BYTES + 60, frame.length - 70, frame.length - 1}) {
            byte[] tampered = frame.clone();
            tampered[offset] ^= 0x01;
            assertRejected(RejectionReason.BAD_SIGNATURE, tampered);
        }
    }

    @Test
    void signatureFromAnotherKeyIsRejected() {
        byte[] unsigned = unsignedPayload("social-profile-card");
        byte[] frame = resign(unsigned);
        byte[] foreign = Ed25519.sign(ProtocolFixtures.privateKey(ProtocolFixtures.PEER_B_SEED), new byte[] {1});
        System.arraycopy(foreign, 0, frame, frame.length - 64, 64);
        assertRejected(RejectionReason.BAD_SIGNATURE, frame);
    }

    @Test
    void timestampsOutsideBoundsOrOrderAreInvalid() {
        long created = ProtocolFixtures.CREATED.toEpochMilli();
        long[][] cases = {
                {ProtocolVersion.MIN_TIMESTAMP_MILLIS - 1, created + 1000},     // before protocol floor
                {created, created},                                          // expires == created
                {created, created - 1},                                      // expires before created
                {created, created + ProtocolVersion.MAX_LIFETIME_MILLIS + 1}, // lifetime too long
                {created, Long.MAX_VALUE},                                   // absurd far future
        };
        for (long[] c : cases) {
            byte[] unsigned = unsignedPayload("social-profile-card");
            WireMutations.putLong(unsigned, WireMutations.CREATED_OFFSET, c[0]);
            WireMutations.putLong(unsigned, WireMutations.EXPIRES_OFFSET, c[1]);
            assertRejected(RejectionReason.INVALID_TIMESTAMP, resign(unsigned));
        }
    }

    @Test
    void negativeSequenceAndZeroEpochOrRevisionAreMalformed() {
        byte[] unsigned = unsignedPayload("social-profile-card");
        unsigned[57] = (byte) 0x80; // sequence top bit
        assertRejected(RejectionReason.MALFORMED, resign(unsigned));
        byte[] zeroEpoch = unsignedPayload("social-profile-card");
        Arrays.fill(zeroEpoch, 53, 57, (byte) 0);
        assertRejected(RejectionReason.MALFORMED, resign(zeroEpoch));
        byte[] zeroRevision = unsignedPayload("social-profile-card");
        Arrays.fill(zeroRevision, 65, 69, (byte) 0);
        assertRejected(RejectionReason.MALFORMED, resign(zeroRevision));
    }

    // ----- audience -----

    @Test
    void audienceShapesAreEnforced() {
        byte[] unknownKind = unsignedPayload("social-profile-card");
        unknownKind[WireMutations.AUDIENCE_OFFSET] = 3;
        assertRejected(RejectionReason.INVALID_AUDIENCE, resign(unknownKind));

        // No recipients.
        assertRejected(RejectionReason.INVALID_AUDIENCE, resign(withRecipients(AudienceKind.DIRECT.code(), null)));

        // Two recipients in descending order (canonical order is ascending) -> invalid.
        List<IdentityId> sorted = List.of(ProtocolFixtures.PEER_B.id(), ProtocolFixtures.PEER_C.id()).stream().sorted().toList();
        assertRejected(RejectionReason.INVALID_AUDIENCE,
                resign(withRecipients(AudienceKind.DIRECT.code(), null, sorted.get(1), sorted.get(0))));
        // Duplicate recipient.
        assertRejected(RejectionReason.INVALID_AUDIENCE,
                resign(withRecipients(AudienceKind.DIRECT.code(), null, sorted.get(0), sorted.get(0))));
        // Author listed as a recipient.
        assertRejected(RejectionReason.INVALID_AUDIENCE,
                resign(withRecipients(AudienceKind.DIRECT.code(), null, ProtocolFixtures.AUTHOR.id())));
        // GROUP with one member, and GROUP with an all-zero id.
        assertRejected(RejectionReason.INVALID_AUDIENCE,
                resign(withRecipients(AudienceKind.GROUP.code(), ProtocolFixtures.GROUP.bytes(), sorted.get(0))));
        assertRejected(RejectionReason.INVALID_AUDIENCE,
                resign(withRecipients(AudienceKind.GROUP.code(), new byte[16], sorted.get(0), sorted.get(1))));
        // More than the maximum number of recipients.
        IdentityId[] many = new IdentityId[ProtocolVersion.MAX_RECIPIENTS + 1];
        for (int i = 0; i < many.length; i++) {
            byte[] id = new byte[IdentityId.LENGTH];
            id[0] = (byte) (i + 1);
            many[i] = new IdentityId(id);
        }
        assertRejected(RejectionReason.INVALID_AUDIENCE, resign(withRecipients(AudienceKind.DIRECT.code(), null, many)));
    }

    /** A social-profile-card payload with a hand-written audience section. */
    private static byte[] withRecipients(int kind, byte[] groupId, IdentityId... recipients) {
        byte[] unsigned = unsignedPayload("social-profile-card");
        int bodyStart = WireMutations.AUDIENCE_OFFSET + 2 + IdentityId.LENGTH; // fixture: DIRECT with one recipient
        CanonicalWriter writer = new CanonicalWriter().fixed(Arrays.copyOf(unsigned, WireMutations.AUDIENCE_OFFSET), WireMutations.AUDIENCE_OFFSET);
        writer.u8(kind);
        if (groupId != null) {
            writer.fixed(groupId, 16);
        }
        writer.u8(recipients.length);
        for (IdentityId recipient : recipients) {
            writer.fixed(recipient.bytes(), IdentityId.LENGTH);
        }
        byte[] rest = Arrays.copyOfRange(unsigned, bodyStart, unsigned.length);
        writer.fixed(rest, rest.length);
        return writer.toByteArray();
    }

    // ----- bodies -----

    private static EnvelopeHeader profileHeader() {
        return ProtocolFixtures.header(SocialProfileCard.objectIdFor(ProtocolFixtures.AUTHOR.id()), 2, 1, ProtocolFixtures.toB());
    }

    private static byte[] profileBody(String name) {
        return new CanonicalWriter().string(name, 64).bool(false).string("Europe/Berlin", 64).u8(1).toByteArray();
    }

    @Test
    void oversizedBodyLengthIsRejectedBeforeReading() {
        byte[] unsigned = withBody(profileHeader(), MessageType.SOCIAL_PROFILE_CARD.code(), 1, new byte[ProtocolVersion.MAX_BODY_BYTES + 1]);
        assertRejected(RejectionReason.OVERSIZED, resign(unsigned));
    }

    @Test
    void nonNfcDisplayNameIsNonCanonical() {
        byte[] unsigned = withBody(profileHeader(), MessageType.SOCIAL_PROFILE_CARD.code(), 1, profileBody("José"));
        assertRejected(RejectionReason.NON_CANONICAL, resign(unsigned));
    }

    @Test
    void controlAndBidiOverrideCharactersInNamesAreRejected() {
        for (String name : new String[] {"Ada\u0007", "Ada‮gnp.exe", "Ada Lovelace", ""}) {
            byte[] unsigned = withBody(profileHeader(), MessageType.SOCIAL_PROFILE_CARD.code(), 1, profileBody(name));
            assertRejected(RejectionReason.INCONSISTENT_BODY, resign(unsigned));
        }
    }

    @Test
    void invalidUtf8AndNonBinaryBooleansAreMalformed() {
        byte[] badUtf8 = new CanonicalWriter().u16(2).fixed(new byte[] {(byte) 0xC3, 0x28}, 2)
                .bool(false).string("UTC", 64).u8(1).toByteArray();
        assertRejected(RejectionReason.MALFORMED,
                resign(withBody(profileHeader(), MessageType.SOCIAL_PROFILE_CARD.code(), 1, badUtf8)));
        byte[] body = profileBody("Ada");
        body[2 + 3] = 2; // bio presence flag
        assertRejected(RejectionReason.MALFORMED,
                resign(withBody(profileHeader(), MessageType.SOCIAL_PROFILE_CARD.code(), 1, body)));
    }

    @Test
    void trailingBodyBytesAreMalformed() {
        byte[] body = Arrays.copyOf(profileBody("Ada"), profileBody("Ada").length + 1);
        assertRejected(RejectionReason.MALFORMED,
                resign(withBody(profileHeader(), MessageType.SOCIAL_PROFILE_CARD.code(), 1, body)));
    }

    @Test
    void profileCardMustUseTheAuthorDerivedObjectId() {
        EnvelopeHeader wrongObject = ProtocolFixtures.header(ProtocolFixtures.objectId(9), 2, 1, ProtocolFixtures.toB());
        assertRejected(RejectionReason.INCONSISTENT_BODY,
                resign(withBody(wrongObject, MessageType.SOCIAL_PROFILE_CARD.code(), 1, profileBody("Ada"))));
    }

    @Test
    void unsortedMetricsAreNonCanonical() {
        ProgressSummary summary = (ProgressSummary) ProtocolFixtures.all().get("progress-summary-day-partial").body();
        CanonicalWriter writer = new CanonicalWriter();
        summary.window().writeTo(writer);
        writer.i64(summary.cutoff().toEpochMilli());
        List<MetricValue> reversed = summary.metrics().reversed();
        writer.list(reversed, 32, (w, m) -> m.writeTo(w));
        EnvelopeHeader header = ProtocolFixtures.header(ProtocolFixtures.objectId(3), 3, 1, ProtocolFixtures.toB());
        assertRejected(RejectionReason.NON_CANONICAL,
                resign(withBody(header, MessageType.PROGRESS_SUMMARY.code(), 1, writer.toByteArray())));
    }

    @Test
    void consentMustBeAddressedToExactlyOneDirectRecipient() {
        EnvelopeHeader toTwo = ProtocolFixtures.header(ConsentRevision.objectIdFor(ProtocolFixtures.AUTHOR.id(), ProtocolFixtures.PEER_B.id()),
                6, 1, Audience.direct(List.of(ProtocolFixtures.PEER_B.id(), ProtocolFixtures.PEER_C.id())));
        byte[] body = new ConsentRevision(List.of(SharingScope.DAILY_SUMMARY)).encodeBody();
        assertRejected(RejectionReason.INVALID_AUDIENCE, resign(withBody(toTwo, MessageType.CONSENT_REVISION.code(), 1, body)));
    }

    @Test
    void summaryCannotDescribeDataAfterItsOwnCreation() {
        ProgressSummary summary = (ProgressSummary) ProtocolFixtures.all().get("progress-summary-day-partial").body();
        EnvelopeHeader early = new EnvelopeHeader(0, ProtocolFixtures.AUTHOR, ProtocolFixtures.objectId(3), 1, 3, 1,
                Instant.parse("2026-09-21T12:00:00Z"), ProtocolFixtures.EXPIRES, ProtocolFixtures.toB());
        assertRejected(RejectionReason.INCONSISTENT_BODY,
                resign(withBody(early, MessageType.PROGRESS_SUMMARY.code(), 1, summary.encodeBody())));
    }

    @Test
    void epochLaterThanCreationTimeIsInvalid() {
        byte[] unsigned = unsignedPayload("social-profile-card");
        Arrays.fill(unsigned, 53, 57, (byte) 0xff); // epoch 2^32-1, far beyond createdAt's epoch
        assertRejected(RejectionReason.INVALID_TIMESTAMP, resign(unsigned));
    }

    /** Hand-encoded snapshot body (bypasses the constructor) with an arbitrary declared overall/coverage/status. */
    private static byte[] snapshotBody(Integer overall, int coverage, PreparationStatus status, DomainRow... domains) {
        CanonicalWriter writer = new CanonicalWriter().string("synthetic-backend", 64).fixed(new byte[32], 32)
                .u16(1).u8(75).i64(ProtocolFixtures.CREATED.minusSeconds(60).toEpochMilli())
                .optionalU8(overall).u8(coverage).u8(status.code());
        writer.list(List.of(domains), 64, (w, d) -> w.string(d.id(), 64).u8(d.weight()).bool(d.critical())
                .optionalU8(d.threshold()).optionalU8(d.score()).u8(d.coverage()).u16(d.measured()).u16(d.total())
                .u8(d.status().code()));
        return writer.toByteArray();
    }

    private record DomainRow(String id, int weight, boolean critical, Integer threshold, Integer score, int coverage,
                             int measured, int total, DomainStatus status) {
    }

    private static byte[] signedSnapshot(byte[] body) {
        EnvelopeHeader header = ProtocolFixtures.header(ProtocolFixtures.objectId(5), 5, 1, ProtocolFixtures.toB());
        return resign(withBody(header, MessageType.PREPARATION_SNAPSHOT.code(), 1, body));
    }

    @Test
    void snapshotWhoseAggregatesContradictItsDomainsIsRejected() {
        DomainRow gate = new DomainRow("gate", 10, true, 70, 80, 100, 1, 1, DomainStatus.PASS);
        DomainRow other = new DomainRow("other", 90, false, null, 20, 100, 1, 1, DomainStatus.MEASURED);
        // Review counterexample: declared READY at 100% / 0% coverage; the engine computes 26% / 100% NOT_READY.
        assertRejected(RejectionReason.INCONSISTENT_BODY, signedSnapshot(snapshotBody(100, 0, PreparationStatus.READY, gate, other)));
        // The consistent encoding of the same domains decodes.
        SignedEnvelope ok = EnvelopeCodec.decodeFrame(signedSnapshot(snapshotBody(26, 100, PreparationStatus.NOT_READY, gate, other)));
        assertEquals(PreparationStatus.NOT_READY, ((PreparationSnapshot) ok.body()).status());
    }

    @Test
    void snapshotClaimingAPassWithNothingMeasuredIsRejected() {
        DomainRow unmeasuredPass = new DomainRow("gate", 100, true, 70, 100, 0, 0, 0, DomainStatus.PASS);
        assertRejected(RejectionReason.INCONSISTENT_BODY, signedSnapshot(snapshotBody(100, 0, PreparationStatus.READY, unmeasuredPass)));
    }

    @Test
    void authorsCannotEncodeInvalidEnvelopesEither() {
        assertReason(RejectionReason.INVALID_AUDIENCE, () -> new EnvelopeHeader(0, ProtocolFixtures.AUTHOR,
                ProtocolFixtures.objectId(1), 1, 1, 1, ProtocolFixtures.CREATED, ProtocolFixtures.EXPIRES,
                Audience.direct(List.of(ProtocolFixtures.AUTHOR.id()))));
        assertReason(RejectionReason.INVALID_TIMESTAMP, () -> new EnvelopeHeader(0, ProtocolFixtures.AUTHOR,
                ProtocolFixtures.objectId(1), 1, 1, 1, ProtocolFixtures.CREATED.plusNanos(1), ProtocolFixtures.EXPIRES,
                ProtocolFixtures.toB()));
        assertThrows(IllegalArgumentException.class, () -> EnvelopeCodec.sign(
                ProtocolFixtures.all().get("tombstone").envelope(), ProtocolFixtures.privateKey(ProtocolFixtures.PEER_B_SEED)));
    }
}

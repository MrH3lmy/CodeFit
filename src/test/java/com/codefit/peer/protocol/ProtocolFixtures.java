package com.codefit.peer.protocol;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic canonical fixtures for protocol v1. Every key is a published RFC 8032 section 7.1 test
 * vector - never a real identity - and every profile/metric value is made up. The golden frames in
 * {@code src/test/resources/peer-protocol/v1/} are exactly {@link #all()} encoded; regenerate them only
 * for a deliberate, reviewed wire change with {@code mvn -q test-compile exec:java} equivalent:
 * run {@link #main(String[])} with the test classpath.
 */
final class ProtocolFixtures {
    static final HexFormat HEX = HexFormat.of();

    // RFC 8032 section 7.1, TEST 1 / TEST 2 / TEST 3 / TEST 1024.
    static final byte[] AUTHOR_SEED = HEX.parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
    static final IdentityKey AUTHOR = key("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    static final byte[] PEER_B_SEED = HEX.parseHex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb");
    static final IdentityKey PEER_B = key("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c");
    static final byte[] PEER_C_SEED = HEX.parseHex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7");
    static final IdentityKey PEER_C = key("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025");
    static final IdentityKey TRANSPORT = key("278117fc144c72340f67d0f2316e8386ceffbf2b2428c9c51fef7c597f1d426e");

    static final Instant CREATED = Instant.parse("2026-09-21T18:30:00Z");
    static final Instant EXPIRES = Instant.parse("2026-12-20T18:30:00Z");
    static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    static final GroupId GROUP = new GroupId(HEX.parseHex("0123456789abcdef0123456789abcdef"));

    static final Path GOLDEN_DIR = Path.of("src/test/resources/peer-protocol/v1");

    private ProtocolFixtures() {
    }

    static IdentityKey key(String hex) {
        return new IdentityKey(HEX.parseHex(hex));
    }

    static PrivateKey privateKey(byte[] seed) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static ObjectId objectId(int lastByte) {
        byte[] bytes = new byte[ObjectId.LENGTH];
        bytes[0] = 0x0f;
        bytes[ObjectId.LENGTH - 1] = (byte) lastByte;
        return new ObjectId(bytes);
    }

    static EnvelopeHeader header(ObjectId objectId, long sequence, long revision, Audience audience) {
        return new EnvelopeHeader(ProtocolVersion.MINOR, AUTHOR, objectId, 1, sequence, revision, CREATED, EXPIRES, audience);
    }

    static Audience toB() {
        return Audience.direct(List.of(PEER_B.id()));
    }

    static SignedEnvelope sign(Envelope envelope) {
        return EnvelopeCodec.sign(envelope, privateKey(AUTHOR_SEED));
    }

    /** A synthetic preparation profile, deliberately not a bundled one so bundled edits don't move goldens. */
    static InterviewPreparationProfile syntheticProfile(int criticalThreshold) {
        return new InterviewPreparationProfile("synthetic-backend", "Synthetic Backend", "Fixture only", List.of(
                new InterviewDomain("concurrency", "Concurrency", null, 60, true, criticalThreshold, List.of(
                        InterviewRequirement.available("concurrency-deck", "Deck", null,
                                InterviewMaterialType.DECK, "Synthetic Concurrency"),
                        InterviewRequirement.available("concurrency-mock", "Mock", null,
                                InterviewMaterialType.MOCK_INTERVIEW, "concurrency"))),
                new InterviewDomain("sql", "SQL", null, 40, false, null, List.of(
                        InterviewRequirement.available("sql-deck", "Deck", null,
                                InterviewMaterialType.DECK, "Synthetic SQL"),
                        InterviewRequirement.planned("sql-planned", "Planned", null)))));
    }

    static PreparationSnapshot snapshot() {
        return new PreparationSnapshot("synthetic-backend", PreparationProfileFingerprint.of(syntheticProfile(70)),
                PreparationSnapshots.SCORING_VERSION, 75, Instant.parse("2026-09-21T18:00:00Z"), 78, 80,
                PreparationStatus.READY, List.of(
                new DomainSnapshot("concurrency", 60, true, 70, 80, 100, 2, 2, DomainStatus.PASS),
                new DomainSnapshot("sql", 40, false, null, 75, 50, 1, 2, DomainStatus.MEASURED)));
    }

    /** Canonical fixtures in a stable order: file stem -> signed envelope. */
    static Map<String, SignedEnvelope> all() {
        Map<String, SignedEnvelope> fixtures = new LinkedHashMap<>();
        fixtures.put("identity-binding", sign(new Envelope(
                header(objectId(1), 1, 1, Audience.direct(List.of(PEER_B.id(), PEER_C.id()))),
                new IdentityBinding(TRANSPORT, Instant.parse("2026-09-21T00:00:00Z"), Instant.parse("2026-12-20T00:00:00Z")))));
        fixtures.put("social-profile-card", sign(new Envelope(
                header(SocialProfileCard.objectIdFor(AUTHOR.id()), 2, 1, toB()),
                new SocialProfileCard("Ada Synthetic", "Practising concurrency daily.", "Europe/Berlin", DayOfWeek.MONDAY))));
        ComparisonWindow day = ComparisonWindow.day(LocalDate.of(2026, 9, 21), ZONE);
        fixtures.put("progress-summary-day-partial", sign(new Envelope(
                header(objectId(3), 3, 1, toB()),
                new ProgressSummary(day, Instant.parse("2026-09-21T18:00:00Z"), List.of(
                        new MetricValue("problem.accepted", 1, MetricUnit.COUNT, MetricAvailability.MEASURED, 2, 2,
                                MetricProvenance.LEARNER_REPORTED_OUTCOME, TimestampBasis.EXACT_UTC),
                        new MetricValue("review.attempts", 1, MetricUnit.COUNT, MetricAvailability.MEASURED, 42, 42,
                                MetricProvenance.LOCAL_RECORD, TimestampBasis.LEGACY_SQLITE_UTC),
                        new MetricValue("review.verified_correct_rate", 1, MetricUnit.BASIS_POINTS, MetricAvailability.MEASURED,
                                8125, 32, MetricProvenance.VERIFIED_LOCAL_VALIDATION, TimestampBasis.LEGACY_SQLITE_UTC))))));
        ComparisonWindow week = ComparisonWindow.week(LocalDate.of(2026, 9, 16), ZONE, DayOfWeek.MONDAY);
        fixtures.put("progress-summary-week-group", sign(new Envelope(
                header(objectId(4), 4, 1, Audience.group(GROUP, List.of(PEER_B.id(), PEER_C.id()))),
                new ProgressSummary(week, week.end(), List.of(
                        new MetricValue("mock.overall_score", 1, MetricUnit.PERCENT, MetricAvailability.UNAVAILABLE, 0, 0,
                                MetricProvenance.MOCK_SELF_SCORE, TimestampBasis.LEGACY_LOCAL_ASSUMED_ZONE),
                        new MetricValue("review.self_rated_success_rate", 1, MetricUnit.BASIS_POINTS,
                                MetricAvailability.INSUFFICIENT_DATA, 0, 4, MetricProvenance.LEGACY_SELF_RATING_FALLBACK,
                                TimestampBasis.MIXED))))));
        fixtures.put("preparation-snapshot", sign(new Envelope(header(objectId(5), 5, 1, toB()), snapshot())));
        fixtures.put("consent-revision", sign(new Envelope(
                header(ConsentRevision.objectIdFor(AUTHOR.id(), PEER_B.id()), 6, 1, toB()),
                new ConsentRevision(List.of(SharingScope.SOCIAL_PROFILE, SharingScope.DAILY_SUMMARY, SharingScope.PREPARATION_SNAPSHOT)))));
        fixtures.put("consent-revocation", sign(new Envelope(
                header(ConsentRevision.objectIdFor(AUTHOR.id(), PEER_B.id()), 7, 2, toB()),
                new ConsentRevision(List.of()))));
        fixtures.put("tombstone", sign(new Envelope(
                header(objectId(3), 8, 2, toB()),
                new Tombstone(MessageType.PROGRESS_SUMMARY, TombstoneReason.REVOKED, true))));
        return fixtures;
    }

    static Path goldenPath(String stem) {
        return GOLDEN_DIR.resolve(stem + ".frame.hex");
    }

    static byte[] readGolden(String stem) {
        try {
            return HEX.parseHex(Files.readString(goldenPath(stem), StandardCharsets.US_ASCII).replaceAll("\\s", ""));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Regenerates the golden files. Only for deliberate, reviewed wire-format changes. */
    public static void main(String[] args) throws IOException {
        Files.createDirectories(GOLDEN_DIR);
        for (Map.Entry<String, SignedEnvelope> fixture : all().entrySet()) {
            String hex = HEX.formatHex(EnvelopeCodec.encodeFrame(fixture.getValue()));
            StringBuilder wrapped = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 64) {
                wrapped.append(hex, i, Math.min(hex.length(), i + 64)).append('\n');
            }
            Files.writeString(goldenPath(fixture.getKey()), wrapped.toString(), StandardCharsets.US_ASCII);
        }
    }
}

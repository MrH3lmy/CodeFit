package com.codefit.evolution.evidence;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.*;

class LegacyEvidenceClassifierTest {
    private static final String TIME = "2026-10-10 12:00:00";

    @ParameterizedTest
    @ValueSource(strings = {"AC", "ACX"})
    void manualAcNeverBecomesObjectiveOrIndependent(String result) {
        var o = LegacyEvidenceClassifier.problem(1, 7, 1, result, "FRESH_ATTEMPT", TIME);
        assertEquals(Provenance.SELF_REPORTED, o.provenance());
        assertEquals(Outcome.PASS, o.outcome());
        assertEquals(Assistance.UNKNOWN, o.assistance());
        assertEquals("ACX".equals(result) ? Exposure.REPEAT : Exposure.UNKNOWN, o.exposure());
        assertEquals(Instant.parse("2026-10-10T12:00:00Z"), o.time().observedAt());
        assertEquals(TimeBasis.LEGACY_SQLITE_UTC, o.time().basis());
    }

    @ParameterizedTest
    @ValueSource(strings = {"WA", "TLE", "RTE", "CS", "MLE"})
    void reportedFailuresRemainReported(String code) {
        assertEquals(
                Outcome.FAIL,
                LegacyEvidenceClassifier.problem(1, 7, 2, code, "FRESH_ATTEMPT", TIME).outcome());
    }

    @ParameterizedTest
    @ValueSource(strings = {"IMPORTED", "UNKNOWN", "PREVIOUSLY_SOLVED"})
    void importedAndPriorSolvedTimestampIsNotAnEventClock(String origin) {
        var o = LegacyEvidenceClassifier.problem(1, 7, 2, "AC", origin, TIME);
        assertNull(o.time().observedAt());
        assertEquals(Exposure.REPEAT, o.exposure());
        assertEquals(origin, o.originCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXACT", "CLOSE_SPACING", "JAVA_CORRECT"})
    void explicitSupportedValidatorPassHasLimitedScope(String code) {
        var o = LegacyEvidenceClassifier.review(1, 2, code, "AGAIN", true, true, false, TIME);
        assertEquals(Provenance.OBJECTIVE_REVIEW, o.provenance());
        assertEquals(Outcome.PASS, o.outcome());
        assertEquals(Assistance.HINT, o.assistance());
        assertEquals(Exposure.FAMILIAR_RECALL, o.exposure());
        assertTrue(o.limitations().contains(Limitation.LEGACY_VALIDATOR_SCOPE));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "DIFFERENT",
                "JAVA_WRONG_OUTPUT",
                "JAVA_UNEXPECTED_EXCEPTION",
                "JAVA_MISSING_EXPECTED_EXCEPTION"
            })
    void explicitFailureDoesNotUsePositiveRating(String code) {
        var o = LegacyEvidenceClassifier.review(1, 2, code, "EASY", false, true, false, TIME);
        assertEquals(Provenance.OBJECTIVE_REVIEW, o.provenance());
        assertEquals(Outcome.FAIL, o.outcome());
        assertEquals(Assistance.NO_IN_APP_HINT_RECORDED, o.assistance());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "JAVA_COMPILE_ERROR",
                "JAVA_TIMEOUT",
                "JAVA_SANDBOX_UNAVAILABLE",
                "JAVA_PENDING",
                "TIMED_OUT",
                "TIMED_OUT_WITH_ATTEMPT",
                "EMPTY",
                "JAVA_REJECTED_UNSAFE_SNIPPET"
            })
    void executionAndConfigurationAmbiguityIsNotCapabilityFailure(String code) {
        var o = LegacyEvidenceClassifier.review(1, 2, code, "EASY", false, false, false, TIME);
        assertEquals(Outcome.UNAVAILABLE, o.outcome());
        assertEquals(Provenance.LEGACY_UNSPECIFIED, o.provenance());
    }

    @Test
    void ratingFallbackAndMissingHintFlagsRemainUnknown() {
        for (String validation : Arrays.asList(null, "FUTURE_CODE")) {
            var o =
                    LegacyEvidenceClassifier.review(
                            1, 2, validation, "GOOD", true, false, true, TIME);
            assertEquals(Provenance.LEGACY_UNSPECIFIED, o.provenance());
            assertEquals(Assistance.UNKNOWN, o.assistance());
            assertTrue(o.limitations().contains(Limitation.LEGACY_RATING_FALLBACK));
            assertTrue(o.limitations().contains(Limitation.BOSS_FAMILIAR_REVIEW));
        }
        assertEquals(
                Provenance.SELF_REPORTED,
                LegacyEvidenceClassifier.review(
                                1, 2, "SUBJECTIVE", "GOOD", false, false, false, TIME)
                        .provenance());
    }

    @Test
    void assessmentsNeverReconstructGradingHintsOrExposure() {
        for (boolean correct : new boolean[] {true, false}) {
            var o = LegacyEvidenceClassifier.assessment(1, 2, correct, TIME);
            assertEquals(Provenance.LEGACY_UNSPECIFIED, o.provenance());
            assertEquals(Assistance.UNKNOWN, o.assistance());
            assertEquals(Exposure.UNKNOWN, o.exposure());
            assertEquals(correct ? Outcome.PASS : Outcome.FAIL, o.outcome());
        }
    }

    @Test
    void hardRatingDoesNotProveFailure() {
        var subjective =
                LegacyEvidenceClassifier.review(
                        1, 2, "SUBJECTIVE", "HARD", false, false, false, TIME);
        var legacy = LegacyEvidenceClassifier.review(1, 2, null, "HARD", false, false, false, TIME);
        assertEquals(Outcome.UNAVAILABLE, subjective.outcome());
        assertEquals(Outcome.UNAVAILABLE, legacy.outcome());
        assertNull(
                LegacyEvidenceClassifier.assessment(1, 2, true, "2026-02-30 12:00:00")
                        .time()
                        .observedAt());
    }

    @Test
    void malformedTimestampIsUnknownNotZero() {
        var o = LegacyEvidenceClassifier.assessment(1, 2, true, "not-a-clock");
        assertNull(o.time().observedAt());
        assertEquals(TimeBasis.UNKNOWN, o.time().basis());
        assertTrue(o.limitations().contains(Limitation.MALFORMED_TIMESTAMP));
    }

    @Test
    void identityDependsOnlyOnCanonicalSourceEventAndRevision() {
        var o = LegacyEvidenceClassifier.assessment(1, 2, true, TIME);
        var revised =
                o.revise(
                        2, EvidenceIds.id(o.source()), ChangeReason.SOURCE_CORRECTED, Actor.SYSTEM);
        assertEquals(EvidenceIds.root(o.source()), EvidenceIds.root(revised.source()));
        assertNotEquals(EvidenceIds.id(o.source()), EvidenceIds.id(revised.source()));
        assertNotEquals(
                EvidenceIds.id(o.source()),
                EvidenceIds.id(new EvidenceSourceRef(SourceType.REVIEW, 1, 1)));
    }

    @Test
    void codecRoundTripsAndRejectsUnsupportedMalformedOrTrailingData() {
        var o = LegacyEvidenceClassifier.review(1, 2, "EXACT", "GOOD", true, true, false, TIME);
        assertEquals(o, EvidenceCodec.decode(EvidenceCodec.encode(o)));
        var revision = o.retract(ChangeReason.LEARNER_RETRACTED, Actor.LEARNER);
        assertEquals(revision, EvidenceCodec.decode(EvidenceCodec.encode(revision)));
        assertThrows(IllegalArgumentException.class, () -> EvidenceCodec.decode(new byte[65537]));
        assertThrows(
                IllegalArgumentException.class,
                () -> EvidenceCodec.decode(new byte[] {0, 0, 0, 2}));
        var data = EvidenceCodec.encode(o);
        assertThrows(
                IllegalArgumentException.class,
                () -> EvidenceCodec.decode(Arrays.copyOf(data, data.length + 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> EvidenceCodec.decode(Arrays.copyOf(data, 10)));
    }

    @Test
    void contractsRejectImpossibleLineageAndUnsupportedExternalProof() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvidenceSourceRef(SourceType.REVIEW, 0, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new EvidenceTime(TIME, null, TimeBasis.EXACT_UTC));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        LegacyEvidenceClassifier.assessment(1, 2, true, TIME)
                                .revise(3, "bad", ChangeReason.SOURCE_CORRECTED, Actor.SYSTEM));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvaluationReference(
                                "s", "f", "r", "e", "0".repeat(64), List.of("a", "a"), null));
    }
}

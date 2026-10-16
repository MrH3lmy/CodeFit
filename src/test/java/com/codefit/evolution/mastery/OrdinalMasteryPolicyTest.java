package com.codefit.evolution.mastery;

import static com.codefit.evolution.evidence.EvidenceTypes.*;
import static com.codefit.evolution.mastery.MasteryTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.DurableEvaluationRepository.CheckSummary;
import com.codefit.evolution.evaluation.EvaluationTypes.CheckStatus;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.mastery.EstimateInput.*;
import com.codefit.evolution.quest.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.*;
import java.util.*;

/** Adversarial pure inputs; integration tests separately prove authoritative source validation. */
class OrdinalMasteryPolicyTest {
    static final Instant T = Instant.parse("2026-01-10T12:00:00Z");
    static final SkillDefinition SKILL =
            new SkillDefinition(
                    new SkillRef("test.atomic", 1),
                    "test",
                    "Atomic",
                    "Repair",
                    List.of("Repair boundary"),
                    SkillLifecycle.ACTIVE);
    static final GraphRef GRAPH = new GraphRef("test-graph", 1);
    static final EvidenceMappingRef MAP = new EvidenceMappingRef("test-map", 1);

    static Observation pass(long id, String family, Cohort cohort, int day) {
        return observation(
                id,
                family,
                cohort,
                day,
                Outcome.PASS,
                Assistance.NONE_OBSERVED,
                Exposure.FIRST_PRESENTATION,
                true,
                CheckStatus.PASSED);
    }

    static Observation observation(
            long id,
            String family,
            Cohort cohort,
            int day,
            Outcome outcome,
            Assistance assistance,
            Exposure exposure,
            boolean novel,
            CheckStatus status) {
        Instant at = T.plus(Duration.ofDays(day));
        var source = new EvidenceSourceRef(SourceType.CHALLENGE_EVALUATION, id, 1);
        var check =
                new CheckSummary(
                        "functional",
                        CheckKind.DETERMINISTIC_IO,
                        true,
                        List.of(new BehaviorTarget(SKILL.ref(), 0)),
                        status,
                        1,
                        "Bounded functional scope");
        var eval =
                new EvaluationReference(
                        "submit-" + id,
                        family,
                        "rubric/v1",
                        "local-java-v1",
                        "a".repeat(64),
                        List.of("functional"),
                        id);
        var facts =
                new EvidenceObservation(
                        source,
                        new EvidenceTime(at.toString(), at, TimeBasis.EXACT_UTC),
                        Provenance.LOCAL_CHECK,
                        outcome,
                        assistance,
                        assistance == Assistance.HINT ? 1 : null,
                        exposure,
                        Set.of(),
                        Availability.AVAILABLE,
                        outcome.name(),
                        "LOCAL",
                        family,
                        eval,
                        Change.ORIGINAL,
                        null,
                        ChangeReason.ORIGINAL_RECORD,
                        Actor.SYSTEM);
        var context =
                new Context(
                        new CatalogRef("quest", 1),
                        new CatalogRef("challenge-" + id, 1),
                        family,
                        "variant-" + id,
                        cohort,
                        novel,
                        true,
                        "presentation-" + id,
                        at,
                        List.of(check));
        return new Observation(EvidenceIds.id(source), id, MAP, facts, List.of(), context);
    }

    static EstimateInput input(List<Observation> obs, Instant asOf, List<Activity> activities) {
        return new EstimateInput(
                GRAPH,
                SKILL,
                new Epoch("initial", Instant.MIN),
                asOf,
                List.of(MAP),
                obs,
                activities,
                List.of());
    }

    static MasteryEstimate estimate(Observation... obs) {
        return new OrdinalMasteryPolicy()
                .estimate(input(List.of(obs), T.plus(Duration.ofDays(25)), List.of()));
    }

    static Observation changed(
            Observation o, EvidenceObservation f, Context c, List<String> excluded) {
        return new Observation(o.id(), o.sequence(), o.mapping(), f, excluded, c);
    }

    static Context context(Observation o, boolean novel, List<CheckSummary> checks) {
        var c = o.context();
        return new Context(
                c.quest(),
                c.challenge(),
                c.family(),
                c.variant(),
                c.cohort(),
                novel,
                c.heldOutEquivalent(),
                c.presentationId(),
                c.presentedAt(),
                checks);
    }

    @Test
    void freshLearnerIsUnknown() {
        var e = estimate();
        assertEquals(Band.UNKNOWN, e.band());
        assertEquals(Support.INSUFFICIENT, e.support());
        assertFalse(e.currentCapabilitySupported());
        assertNull(e.lastMeaningfulObservation());
    }

    @Test
    void oneIndependentPassIsDeveloping() {
        var e = estimate(pass(1, "first", Cohort.PRACTICE, 0));
        assertEquals(Band.DEVELOPING, e.band());
        assertEquals(1, e.independentFamilies());
    }

    @Test
    void twoRelatedVariantsCannotInflateDiversityEvenIfCallerLabelsBothNovel() {
        var e = estimate(pass(1, "same", Cohort.PRACTICE, 0), pass(2, "same", Cohort.PRACTICE, 1));
        assertEquals(Band.DEVELOPING, e.band());
        assertEquals(1, e.independentFamilies());
    }

    @Test
    void twoIndependentFamiliesCoveringBehaviorAreIndependent() {
        var e =
                estimate(
                        pass(1, "baseline", Cohort.BASELINE, 0),
                        pass(2, "practice", Cohort.PRACTICE, 1));
        assertEquals(Band.INDEPENDENT, e.band());
        assertEquals(Support.LIMITED, e.support());
        assertTrue(e.currentCapabilitySupported());
    }

    @Test
    void baselineAloneCannotEstablishIndependent() {
        assertEquals(
                Band.DEVELOPING,
                estimate(
                                pass(1, "first", Cohort.BASELINE, 0),
                                pass(2, "second", Cohort.BASELINE, 1))
                        .band());
    }

    @ParameterizedTest
    @EnumSource(
            value = Assistance.class,
            names = {
                "UNKNOWN",
                "HINT",
                "NO_IN_APP_HINT_RECORDED",
                "EXPLANATION_OR_SOLUTION",
                "DECLARED_EXTERNAL"
            })
    void assistanceNeverCreatesIndependentProof(Assistance assistance) {
        var assisted =
                observation(
                        2,
                        "second",
                        Cohort.PRACTICE,
                        1,
                        Outcome.PASS,
                        assistance,
                        Exposure.FIRST_PRESENTATION,
                        true,
                        CheckStatus.PASSED);
        var e = estimate(pass(1, "first", Cohort.PRACTICE, 0), assisted);
        assertEquals(Band.DEVELOPING, e.band());
        assertEquals(1, e.independentFamilies());
    }

    @ParameterizedTest
    @EnumSource(
            value = Exposure.class,
            names = {"UNKNOWN", "FAMILIAR_RECALL"})
    void uncertainExposureNeverConfirmsIndependence(Exposure exposure) {
        assertEquals(
                0,
                estimate(
                                observation(
                                        1,
                                        "first",
                                        Cohort.PRACTICE,
                                        0,
                                        Outcome.PASS,
                                        Assistance.NONE_OBSERVED,
                                        exposure,
                                        true,
                                        CheckStatus.PASSED))
                        .independentFamilies());
    }

    @Test
    void knownFamiliarityExcludesRenamedTransfer() {
        var t = pass(3, "transfer", Cohort.TRANSFER, 2);
        t = changed(t, t.facts(), context(t, false, t.context().checks()), List.of());
        assertEquals(
                Band.INDEPENDENT,
                estimate(
                                pass(1, "first", Cohort.PRACTICE, 0),
                                pass(2, "second", Cohort.PRACTICE, 1),
                                t)
                        .band());
    }

    @Test
    void transferRequiresSeparateReservedFamily() {
        assertEquals(
                Band.TRANSFER_SUPPORTED,
                estimate(
                                pass(1, "first", Cohort.PRACTICE, 0),
                                pass(2, "second", Cohort.PRACTICE, 1),
                                pass(3, "transfer", Cohort.TRANSFER, 2))
                        .band());
    }

    @Test
    void postTestIsNotTransferOrFoundation() {
        assertEquals(
                Band.DEVELOPING,
                estimate(
                                pass(1, "post", Cohort.PILOT_POST, 0),
                                pass(2, "second", Cohort.PRACTICE, 1))
                        .band());
    }

    @Test
    void transferCannotDoubleAsSecondFoundationFamily() {
        assertEquals(
                Band.DEVELOPING,
                estimate(
                                pass(1, "first", Cohort.PRACTICE, 0),
                                pass(2, "transfer", Cohort.TRANSFER, 1))
                        .band());
    }

    @Test
    void earlyTransferIsNotRetroactivelyUnseenAfterPractice() {
        assertEquals(
                Band.INDEPENDENT,
                estimate(
                                pass(3, "transfer", Cohort.TRANSFER, 0),
                                pass(1, "first", Cohort.PRACTICE, 1),
                                pass(2, "second", Cohort.PRACTICE, 2))
                        .band());
    }

    @Test
    void sevenDayQuietPeriodSupportsRetention() {
        var obs =
                List.of(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        pass(3, "transfer", Cohort.TRANSFER, 2),
                        pass(4, "retention", Cohort.RETENTION, 9));
        var e =
                new OrdinalMasteryPolicy()
                        .estimate(
                                input(
                                        obs,
                                        T.plus(Duration.ofDays(10)),
                                        List.of(
                                                new Activity(
                                                        "instruction",
                                                        T.plus(Duration.ofDays(2)),
                                                        Set.of(0)))));
        assertEquals(Band.RETENTION_SUPPORTED, e.band());
    }

    @Test
    void rehearsalInterruptsRetentionEvenWhenUnsuccessful() {
        var obs =
                List.of(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        pass(3, "transfer", Cohort.TRANSFER, 2),
                        pass(4, "retention", Cohort.RETENTION, 9));
        var e =
                new OrdinalMasteryPolicy()
                        .estimate(
                                input(
                                        obs,
                                        T.plus(Duration.ofDays(10)),
                                        List.of(
                                                new Activity(
                                                        "instruction",
                                                        T.plus(Duration.ofDays(2)),
                                                        Set.of(0)),
                                                new Activity(
                                                        "rehearsal",
                                                        T.plus(Duration.ofDays(8)),
                                                        Set.of(0)))));
        assertEquals(Band.TRANSFER_SUPPORTED, e.band());
    }

    @Test
    void noKnownDelayAnchorCannotEstablishRetention() {
        assertEquals(
                Band.TRANSFER_SUPPORTED,
                estimate(
                                pass(1, "first", Cohort.PRACTICE, 0),
                                pass(2, "second", Cohort.PRACTICE, 1),
                                pass(3, "transfer", Cohort.TRANSFER, 2),
                                pass(4, "retention", Cohort.RETENTION, 20))
                        .band());
    }

    @ParameterizedTest
    @EnumSource(
            value = CheckStatus.class,
            names = {
                "UNSUPPORTED",
                "INCONCLUSIVE",
                "TIMED_OUT",
                "CANCELLED",
                "EVALUATOR_ERROR",
                "RUNTIME_ERROR",
                "NOT_RUN"
            })
    void unavailableChecksAreNeitherPositiveNorNegative(CheckStatus status) {
        var e =
                estimate(
                        observation(
                                1,
                                "first",
                                Cohort.PRACTICE,
                                0,
                                Outcome.UNAVAILABLE,
                                Assistance.NONE_OBSERVED,
                                Exposure.FIRST_PRESENTATION,
                                true,
                                status));
        assertEquals(Band.UNKNOWN, e.band());
        assertFalse(e.contradicted());
    }

    @Test
    void partialCannotPromoteIndependentEvenWithOnePassedCheck() {
        assertEquals(
                0,
                estimate(
                                observation(
                                        1,
                                        "first",
                                        Cohort.PRACTICE,
                                        0,
                                        Outcome.PARTIAL,
                                        Assistance.NONE_OBSERVED,
                                        Exposure.FIRST_PRESENTATION,
                                        true,
                                        CheckStatus.PASSED))
                        .independentFamilies());
    }

    @Test
    void requiredUnavailableCheckCannotBeIgnoredInPositiveBehaviorCoverage() {
        var o = pass(1, "first", Cohort.PRACTICE, 0);
        var check =
                new CheckSummary(
                        "boundary",
                        CheckKind.CONTROLLED_TRACE,
                        true,
                        List.of(new BehaviorTarget(SKILL.ref(), 0)),
                        CheckStatus.UNSUPPORTED,
                        0,
                        "Unsupported");
        var facts = withChecks(o.facts(), List.of("functional", "boundary"));
        var c = context(o, true, List.of(o.context().checks().getFirst(), check));
        assertEquals(Band.UNKNOWN, estimate(changed(o, facts, c, List.of())).band());
    }

    static EvidenceObservation withChecks(EvidenceObservation f, List<String> checks) {
        var e = f.evaluation();
        return new EvidenceObservation(
                f.source(),
                f.time(),
                f.provenance(),
                f.outcome(),
                f.assistance(),
                f.hintLevel(),
                f.exposure(),
                f.limitations(),
                f.availability(),
                f.resultCode(),
                f.originCode(),
                f.correlationKey(),
                new EvaluationReference(
                        e.submissionId(),
                        e.familyId(),
                        e.rubricVersion(),
                        e.evaluatorVersion(),
                        e.artifactSha256(),
                        checks,
                        e.problemAttemptId()),
                f.change(),
                f.previousEvidenceId(),
                f.reason(),
                f.actor());
    }

    @Test
    void failureAloneIsDevelopingAndNotZeroAbility() {
        var e =
                estimate(
                        observation(
                                1,
                                "first",
                                Cohort.PRACTICE,
                                0,
                                Outcome.FAIL,
                                Assistance.NONE_OBSERVED,
                                Exposure.FIRST_PRESENTATION,
                                true,
                                CheckStatus.FAILED));
        assertEquals(Band.DEVELOPING, e.band());
        assertFalse(e.contradicted());
    }

    @Test
    void oneNewFailurePreservesSupportedHistoryButBlocksAdvancement() {
        var e =
                estimate(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        observation(
                                3,
                                "third",
                                Cohort.PRACTICE,
                                2,
                                Outcome.FAIL,
                                Assistance.NONE_OBSERVED,
                                Exposure.FIRST_PRESENTATION,
                                true,
                                CheckStatus.FAILED));
        assertEquals(Band.INDEPENDENT, e.band());
        assertTrue(e.contradicted());
        assertEquals(Support.INSUFFICIENT, e.support());
        assertFalse(e.currentCapabilitySupported());
    }

    @Test
    void twoDeliberateFailuresCapCurrentStatusAndReplayIsNotAnotherFailure() {
        var f =
                observation(
                        3,
                        "third",
                        Cohort.PRACTICE,
                        2,
                        Outcome.FAIL,
                        Assistance.NONE_OBSERVED,
                        Exposure.FIRST_PRESENTATION,
                        true,
                        CheckStatus.FAILED);
        var first = pass(1, "first", Cohort.PRACTICE, 0);
        var second = pass(2, "second", Cohort.PRACTICE, 1);
        assertFalse(estimate(first, second, f, f).regressionCap());
        var e =
                estimate(
                        first,
                        second,
                        f,
                        observation(
                                4,
                                "third",
                                Cohort.PRACTICE,
                                3,
                                Outcome.FAIL,
                                Assistance.NONE_OBSERVED,
                                Exposure.REPEAT,
                                false,
                                CheckStatus.FAILED));
        assertEquals(Band.DEVELOPING, e.band());
        assertEquals(Band.INDEPENDENT, e.historicalBand());
        assertTrue(e.regressionCap());
    }

    @Test
    void oneFreshPassDoesNotClearTwoFailureCap() {
        var e =
                estimate(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        observation(
                                3,
                                "bad",
                                Cohort.PRACTICE,
                                2,
                                Outcome.FAIL,
                                Assistance.NONE_OBSERVED,
                                Exposure.FIRST_PRESENTATION,
                                true,
                                CheckStatus.FAILED),
                        observation(
                                4,
                                "bad",
                                Cohort.PRACTICE,
                                3,
                                Outcome.FAIL,
                                Assistance.NONE_OBSERVED,
                                Exposure.REPEAT,
                                false,
                                CheckStatus.FAILED),
                        pass(5, "new-one", Cohort.PRACTICE, 4));
        assertEquals(Band.DEVELOPING, e.band());
        assertTrue(e.contradicted());
    }

    @Test
    void twoNewFamiliesRestoreIndependentSupport() {
        var e =
                estimate(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        observation(
                                3,
                                "bad",
                                Cohort.PRACTICE,
                                2,
                                Outcome.FAIL,
                                Assistance.NONE_OBSERVED,
                                Exposure.FIRST_PRESENTATION,
                                true,
                                CheckStatus.FAILED),
                        pass(4, "new-one", Cohort.PRACTICE, 3),
                        pass(5, "new-two", Cohort.PRACTICE, 4));
        assertFalse(e.contradicted());
        assertTrue(e.currentCapabilitySupported());
    }

    @Test
    void unavailableDoesNotContradictPastSuccess() {
        var e =
                estimate(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        observation(
                                3,
                                "third",
                                Cohort.PRACTICE,
                                2,
                                Outcome.UNAVAILABLE,
                                Assistance.NONE_OBSERVED,
                                Exposure.FIRST_PRESENTATION,
                                true,
                                CheckStatus.UNSUPPORTED));
        assertEquals(Band.INDEPENDENT, e.band());
        assertFalse(e.contradicted());
    }

    @Test
    void withdrawnPositiveIsNotRegression() {
        var o = pass(1, "first", Cohort.PRACTICE, 0);
        var e = estimate(changed(o, o.facts(), o.context(), List.of("RETRACTED")));
        assertEquals(Band.UNKNOWN, e.band());
        assertFalse(e.contradicted());
    }

    @Test
    void mappingVersionsCannotMintAnotherSolve() {
        var o = pass(1, "first", Cohort.PRACTICE, 0);
        var duplicate =
                new Observation(
                        o.id(),
                        o.sequence(),
                        new EvidenceMappingRef("test-map", 2),
                        o.facts(),
                        List.of(),
                        o.context());
        assertEquals(1, estimate(o, duplicate).independentFamilies());
    }

    @Test
    void sameTimeHasStableIdTieBreakIndependentOfPageOrder() {
        var a = pass(1, "first", Cohort.PRACTICE, 0);
        var b = pass(2, "second", Cohort.PRACTICE, 0);
        assertEquals(estimate(a, b), estimate(b, a));
    }

    @Test
    void futureObservationCannotAffectHistoricalAsOf() {
        var in = input(List.of(pass(1, "first", Cohort.PRACTICE, 1)), T, List.of());
        assertEquals(Band.UNKNOWN, new OrdinalMasteryPolicy().estimate(in).band());
    }

    @Test
    void freshnessBoundaryIsUtcInclusiveThroughThirtyDays() {
        var o = pass(1, "first", Cohort.PRACTICE, 0);
        assertEquals(
                Freshness.CURRENT,
                new OrdinalMasteryPolicy()
                        .estimate(input(List.of(o), T.plus(Duration.ofDays(30)), List.of()))
                        .freshness());
        assertEquals(
                Freshness.STALE,
                new OrdinalMasteryPolicy()
                        .estimate(
                                input(
                                        List.of(o),
                                        T.plus(Duration.ofDays(30)).plusNanos(1),
                                        List.of()))
                        .freshness());
    }

    @Test
    void epochResetDoesNotUseOldProof() {
        var in =
                new EstimateInput(
                        GRAPH,
                        SKILL,
                        new Epoch("reset", T.plusSeconds(1)),
                        T.plusSeconds(2),
                        List.of(MAP),
                        List.of(pass(1, "first", Cohort.PRACTICE, 0)),
                        List.of(),
                        List.of());
        assertEquals(Band.UNKNOWN, new OrdinalMasteryPolicy().estimate(in).band());
    }

    @Test
    void missingAdditionalBehaviorPreventsPromotion() {
        var skill =
                new SkillDefinition(
                        SKILL.ref(),
                        "test",
                        "Atomic",
                        "Repair",
                        List.of("Repair boundary", "Explain transfer"),
                        SkillLifecycle.ACTIVE);
        var in =
                new EstimateInput(
                        GRAPH,
                        skill,
                        new Epoch("initial", Instant.MIN),
                        T.plus(Duration.ofDays(3)),
                        List.of(MAP),
                        List.of(
                                pass(1, "first", Cohort.PRACTICE, 0),
                                pass(2, "second", Cohort.PRACTICE, 1)),
                        List.of(),
                        List.of());
        assertEquals(Band.DEVELOPING, new OrdinalMasteryPolicy().estimate(in).band());
    }

    @Test
    void canonicalMetadataRoundTripIsReproducibleAndRejectsTampering() {
        var in = input(List.of(pass(1, "first", Cohort.PRACTICE, 0)), T, List.of());
        var bytes = EstimateCodec.encode(in);
        assertEquals(in, EstimateCodec.decode(bytes));
        assertArrayEquals(bytes, EstimateCodec.encode(EstimateCodec.decode(bytes)));
        byte[] trailing = Arrays.copyOf(bytes, bytes.length + 1);
        assertThrows(IllegalArgumentException.class, () -> EstimateCodec.decode(trailing));
    }

    static Observation legacy(long id, Provenance provenance) {
        var source = new EvidenceSourceRef(SourceType.ASSESSMENT_ATTEMPT, id, 1);
        var f =
                new EvidenceObservation(
                        source,
                        new EvidenceTime(T.toString(), T, TimeBasis.EXACT_UTC),
                        provenance,
                        Outcome.PASS,
                        Assistance.UNKNOWN,
                        null,
                        Exposure.UNKNOWN,
                        Set.of(),
                        Availability.AVAILABLE,
                        "CORRECT",
                        "ASSESSMENT",
                        "item:" + id,
                        null,
                        Change.ORIGINAL,
                        null,
                        ChangeReason.ORIGINAL_RECORD,
                        Actor.SYSTEM);
        return new Observation(EvidenceIds.id(source), id, MAP, f, List.of(), null);
    }

    @ParameterizedTest
    @EnumSource(
            value = Provenance.class,
            names = {"SELF_REPORTED", "LEGACY_UNSPECIFIED", "OBJECTIVE_REVIEW"})
    void uncertainOrReviewOnlySuccessIsUnknownNotEngineeringProof(Provenance provenance) {
        assertEquals(Band.UNKNOWN, estimate(legacy(1, provenance)).band());
        assertEquals(0, estimate(legacy(1, provenance)).independentFamilies());
    }

    @Test
    void manyChecksAreOneDeliberateOpportunity() {
        var o = pass(1, "first", Cohort.PRACTICE, 0);
        var second =
                new CheckSummary(
                        "boundary",
                        CheckKind.CONTROLLED_TRACE,
                        true,
                        List.of(new BehaviorTarget(SKILL.ref(), 0)),
                        CheckStatus.PASSED,
                        1,
                        "Structural scope");
        var facts = withChecks(o.facts(), List.of("functional", "boundary"));
        var e =
                estimate(
                        changed(
                                o,
                                facts,
                                context(o, true, List.of(o.context().checks().getFirst(), second)),
                                List.of()));
        assertEquals(1, e.independentFamilies());
        assertEquals(1, e.contributions().size());
        assertEquals(2, e.contributions().getFirst().checkIds().size());
    }

    @Test
    void unknownTimeNeverUsesIngestionTime() {
        var o = legacy(1, Provenance.LEGACY_UNSPECIFIED);
        var f = o.facts();
        var unknown =
                new EvidenceObservation(
                        f.source(),
                        new EvidenceTime("ambiguous", null, TimeBasis.UNKNOWN),
                        f.provenance(),
                        f.outcome(),
                        f.assistance(),
                        null,
                        f.exposure(),
                        f.limitations(),
                        f.availability(),
                        f.resultCode(),
                        f.originCode(),
                        f.correlationKey(),
                        null,
                        f.change(),
                        null,
                        f.reason(),
                        f.actor());
        var e = estimate(changed(o, unknown, null, List.of()));
        assertNull(e.lastMeaningfulObservation());
        assertTrue(e.exclusions().stream().anyMatch(x -> x.code() == ReasonCode.UNKNOWN_TIME));
    }

    @Test
    void repeatedExposureCannotBeIndependentEvenIfContextFlagIsWrong() {
        assertEquals(
                0,
                estimate(
                                observation(
                                        1,
                                        "first",
                                        Cohort.PRACTICE,
                                        0,
                                        Outcome.PASS,
                                        Assistance.NONE_OBSERVED,
                                        Exposure.REPEAT,
                                        true,
                                        CheckStatus.PASSED))
                        .independentFamilies());
    }

    @Test
    void transferRestorationRequiresNewHeldOutProof() {
        var fail =
                observation(
                        4,
                        "failed",
                        Cohort.PRACTICE,
                        3,
                        Outcome.FAIL,
                        Assistance.NONE_OBSERVED,
                        Exposure.FIRST_PRESENTATION,
                        true,
                        CheckStatus.FAILED);
        var e =
                estimate(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        pass(3, "old-transfer", Cohort.TRANSFER, 2),
                        fail,
                        pass(5, "new-one", Cohort.PRACTICE, 4),
                        pass(6, "new-two", Cohort.PRACTICE, 5));
        assertTrue(e.contradicted());
        var restored =
                estimate(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        pass(3, "old-transfer", Cohort.TRANSFER, 2),
                        fail,
                        pass(5, "new-one", Cohort.PRACTICE, 4),
                        pass(6, "new-transfer", Cohort.TRANSFER, 5));
        assertFalse(restored.contradicted());
    }

    @Test
    void unknownRehearsalTimeCannotSupportRetention() {
        var obs =
                List.of(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        pass(3, "transfer", Cohort.TRANSFER, 2),
                        pass(4, "retention", Cohort.RETENTION, 9));
        var e =
                new OrdinalMasteryPolicy()
                        .estimate(
                                input(
                                        obs,
                                        T.plus(Duration.ofDays(10)),
                                        List.of(new Activity("unknown-time", null, Set.of(0)))));
        assertEquals(Band.TRANSFER_SUPPORTED, e.band());
    }

    @Test
    void rehearsalAfterOpeningDelayedTaskInvalidatesItsClaim() {
        var obs =
                List.of(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        pass(3, "transfer", Cohort.TRANSFER, 2),
                        pass(4, "retention", Cohort.RETENTION, 9));
        var e =
                new OrdinalMasteryPolicy()
                        .estimate(
                                input(
                                        obs,
                                        T.plus(Duration.ofDays(10)),
                                        List.of(
                                                new Activity(
                                                        "instruction",
                                                        T.plus(Duration.ofDays(2)),
                                                        Set.of(0)),
                                                new Activity(
                                                        "extra-rehearsal",
                                                        T.plus(Duration.ofDays(9)),
                                                        Set.of(0)))));
        assertEquals(Band.TRANSFER_SUPPORTED, e.band());
    }

    @Test
    void recentAssistedProgressCannotRefreshOldIndependentCapability() {
        var in =
                input(
                        List.of(
                                pass(1, "first", Cohort.PRACTICE, 0),
                                pass(2, "second", Cohort.PRACTICE, 1),
                                observation(
                                        3,
                                        "third",
                                        Cohort.PRACTICE,
                                        100,
                                        Outcome.PASS,
                                        Assistance.HINT,
                                        Exposure.FIRST_PRESENTATION,
                                        true,
                                        CheckStatus.PASSED)),
                        T.plus(Duration.ofDays(100)),
                        List.of());
        var e = new OrdinalMasteryPolicy().estimate(in);
        assertEquals(Band.INDEPENDENT, e.band());
        assertEquals(Freshness.CURRENT, e.freshness());
        assertFalse(e.currentCapabilitySupported());
        assertTrue(
                e.reasons().stream()
                        .anyMatch(r -> r.code() == ReasonCode.INDEPENDENT_VALIDATION_DUE));
    }

    @Test
    void clockRegressedBeforePresentationDoesNotCreateSkillEvidence() {
        var o = pass(1, "first", Cohort.PRACTICE, 0);
        var c = o.context();
        var bad =
                new Context(
                        c.quest(),
                        c.challenge(),
                        c.family(),
                        c.variant(),
                        c.cohort(),
                        true,
                        true,
                        c.presentationId(),
                        T.plusSeconds(1),
                        c.checks());
        assertEquals(Band.UNKNOWN, estimate(changed(o, o.facts(), bad, List.of())).band());
    }

    @Test
    void optionalObservedCounterexampleContradictsButOptionalPassCannotProveBehavior() {
        var third =
                observation(
                        3,
                        "third",
                        Cohort.PRACTICE,
                        2,
                        Outcome.FAIL,
                        Assistance.NONE_OBSERVED,
                        Exposure.FIRST_PRESENTATION,
                        true,
                        CheckStatus.PASSED);
        var stress =
                new CheckSummary(
                        "observed",
                        CheckKind.OBSERVED_CONCURRENCY,
                        false,
                        List.of(new BehaviorTarget(SKILL.ref(), 0)),
                        CheckStatus.FAILED,
                        1,
                        "Finite counterexample");
        var facts = withChecks(third.facts(), List.of("functional", "observed"));
        var e =
                estimate(
                        pass(1, "first", Cohort.PRACTICE, 0),
                        pass(2, "second", Cohort.PRACTICE, 1),
                        changed(
                                third,
                                facts,
                                context(
                                        third,
                                        true,
                                        List.of(third.context().checks().getFirst(), stress)),
                                List.of()));
        assertTrue(e.contradicted());
        assertFalse(e.currentCapabilitySupported());
        assertTrue(
                e.contributions().stream()
                        .anyMatch(
                                c ->
                                        c.checkIds().contains("observed")
                                                && c.eligibleDeliberateFailure()));
        var optionalOnly = pass(4, "optional-only", Cohort.PRACTICE, 3);
        var optional =
                new CheckSummary(
                        "functional",
                        CheckKind.OBSERVED_CONCURRENCY,
                        false,
                        List.of(new BehaviorTarget(SKILL.ref(), 0)),
                        CheckStatus.PASSED,
                        1,
                        "Finite sample");
        assertEquals(
                Band.UNKNOWN,
                estimate(
                                changed(
                                        optionalOnly,
                                        optionalOnly.facts(),
                                        context(optionalOnly, true, List.of(optional)),
                                        List.of()))
                        .band());
    }
}

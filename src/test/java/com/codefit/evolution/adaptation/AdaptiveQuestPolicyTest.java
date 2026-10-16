package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationTypes.*;
import static com.codefit.evolution.mastery.MasteryTypes.*;
import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.AdaptationInput.*;
import com.codefit.evolution.evidence.EvidenceTypes;
import com.codefit.evolution.quest.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.*;
import java.util.*;

class AdaptiveQuestPolicyTest {
    static final Instant T = Instant.parse("2026-06-12T12:00:00Z");
    static final SkillRef SKILL = new SkillRef("domain.target", 1),
            PREREQ = new SkillRef("domain.prerequisite", 1);
    static final GraphRef GRAPH = new GraphRef("domain.track", 1);
    final AdaptiveQuestPolicy policy = new AdaptiveQuestPolicy();

    SkillState state(Band band, boolean current, Observation... obs) {
        return new SkillState(
                SKILL,
                11,
                "a".repeat(64),
                "ordinal-v1",
                "initial",
                band,
                band == Band.UNKNOWN ? Support.INSUFFICIENT : Support.LIMITED,
                band == Band.UNKNOWN ? Freshness.NO_OBSERVATION : Freshness.CURRENT,
                current,
                false,
                band.ordinal() >= Band.INDEPENDENT.ordinal() ? 2 : 1,
                Set.of(0),
                current ? Set.of(0) : Set.of(),
                List.of(),
                List.of(obs),
                List.of(new Activity("last", T.minus(Duration.ofDays(7)), Set.of(0))),
                List.of());
    }

    Observation pass(String id, boolean independent, EvidenceTypes.Assistance aid) {
        return new Observation(
                id,
                "command-" + id,
                "family-" + id,
                new CatalogRef("challenge-" + id, 1),
                T.minusSeconds(1),
                EvidenceTypes.Outcome.PASS,
                aid,
                EvidenceTypes.Exposure.FIRST_PRESENTATION,
                independent,
                false,
                Set.of(0),
                Set.of(),
                List.of("check"),
                2,
                Cohort.PRACTICE);
    }

    Observation fail(String id, int second) {
        return new Observation(
                id,
                "command-" + id,
                "family-fail",
                new CatalogRef("failed-task", 1),
                T.minusSeconds(second),
                EvidenceTypes.Outcome.PARTIAL,
                EvidenceTypes.Assistance.NONE_OBSERVED,
                EvidenceTypes.Exposure.REPEAT,
                false,
                true,
                Set.of(),
                Set.of(0),
                List.of("check"),
                2,
                Cohort.PRACTICE);
    }

    Candidate candidate(String id, Cohort cohort, int difficulty) {
        return new Candidate(
                new CatalogRef("quest-" + id, 1),
                new CatalogRef(id, 1),
                GRAPH,
                "family-" + id,
                "variant-" + id,
                cohort,
                List.of(new BehaviorTarget(SKILL, 0)),
                List.of(),
                difficulty,
                20,
                new CatalogRef("rubric-" + id, 1),
                "a".repeat(64),
                "b".repeat(64),
                "local-test-v1",
                "TEST",
                true,
                "",
                true,
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                true,
                0);
    }

    Preferences prefs(int budget, boolean paused, Set<String> decline, Map<String, Instant> defer) {
        return new Preferences(4, Track.EVOLUTION, SKILL, budget, paused, decline, defer);
    }

    AdaptationInput input(SkillState state, List<Candidate> candidates) {
        return input(state, candidates, prefs(60, false, Set.of(), Map.of()));
    }

    AdaptationInput input(SkillState state, List<Candidate> candidates, Preferences prefs) {
        return new AdaptationInput(
                GRAPH,
                true,
                "",
                prefs,
                T,
                AdaptiveQuestPolicy.VERSION,
                AdaptiveQuestPolicy.CONFIGURATION,
                List.of(state),
                candidates,
                null);
    }

    Candidate exposed(Candidate c, boolean reveal) {
        return new Candidate(
                c.quest(),
                c.challenge(),
                c.graph(),
                c.family(),
                c.variant(),
                c.cohort(),
                c.targets(),
                c.prerequisites(),
                c.difficulty(),
                c.effortMinutes(),
                c.rubric(),
                c.manifestSha(),
                c.rubricSha(),
                c.evaluator(),
                c.runner(),
                c.available(),
                c.limitation(),
                c.equivalent(),
                1,
                1,
                1,
                0,
                reveal,
                true,
                c.baselineSkipped(),
                true,
                0);
    }

    SkillState changed(
            SkillState s,
            Freshness freshness,
            boolean current,
            boolean contradiction,
            List<SkillRef> prereqs,
            List<Activity> activities) {
        return new SkillState(
                s.skill(),
                s.revision(),
                s.inputSha(),
                s.estimatePolicy(),
                s.epoch(),
                s.band(),
                s.support(),
                freshness,
                current,
                contradiction,
                s.independentFamilies(),
                s.requiredBehaviors(),
                s.supportedBehaviors(),
                prereqs,
                s.observations(),
                activities,
                s.exclusions());
    }

    Candidate unavailable(Candidate c) {
        return new Candidate(
                c.quest(),
                c.challenge(),
                c.graph(),
                c.family(),
                c.variant(),
                c.cohort(),
                c.targets(),
                c.prerequisites(),
                c.difficulty(),
                c.effortMinutes(),
                c.rubric(),
                c.manifestSha(),
                c.rubricSha(),
                "unsupported",
                c.runner(),
                false,
                "Runner or exact evaluator unavailable",
                c.equivalent(),
                0,
                0,
                0,
                0,
                false,
                false,
                false,
                true,
                0);
    }

    boolean rejected(NextQuestDecision d, Reason code) {
        return d.candidates().stream().anyMatch(r -> r.exclusions().contains(code));
    }

    @Test
    void freshLearnerPrefersReservedBaselineWithoutPresentation() {
        var d =
                policy.decide(
                        input(
                                state(Band.UNKNOWN, false),
                                List.of(
                                        candidate("practice", Cohort.PRACTICE, 1),
                                        candidate("baseline", Cohort.BASELINE, 1))));
        assertEquals(Branch.BASELINE, d.branch());
        assertEquals(Action.BASELINE, d.primary().action());
        assertEquals("baseline", d.primary().challenge().id());
    }

    @Test
    void unknownCanUseOnlyExplicitIntroductoryPractice() {
        var d =
                policy.decide(
                        input(
                                state(Band.UNKNOWN, false),
                                List.of(candidate("intro", Cohort.PRACTICE, 1))));
        assertEquals(Action.INTRODUCTION, d.primary().action());
    }

    @Test
    void skippedBaselineIsNotARepeatedFailure() {
        var c = candidate("baseline", Cohort.BASELINE, 1);
        c =
                new Candidate(
                        c.quest(),
                        c.challenge(),
                        c.graph(),
                        c.family(),
                        c.variant(),
                        c.cohort(),
                        c.targets(),
                        c.prerequisites(),
                        c.difficulty(),
                        c.effortMinutes(),
                        c.rubric(),
                        c.manifestSha(),
                        c.rubricSha(),
                        c.evaluator(),
                        c.runner(),
                        true,
                        "",
                        true,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false,
                        true,
                        true,
                        0);
        var d =
                policy.decide(
                        input(
                                state(Band.UNKNOWN, false),
                                List.of(c, candidate("intro", Cohort.PRACTICE, 1))));
        assertEquals(Action.INTRODUCTION, d.primary().action());
        assertTrue(rejected(d, Reason.BASELINE_SKIPPED));
    }

    @Test
    void pauseHasPriorityOverUnavailableContextAndAllPerformance() {
        var in =
                input(
                        state(Band.INDEPENDENT, false, fail("a", 2), fail("b", 1)),
                        List.of(),
                        prefs(10, true, Set.of(), Map.of()));
        var d =
                policy.decide(
                        new AdaptationInput(
                                in.graph(),
                                false,
                                "missing",
                                in.preferences(),
                                T,
                                in.policy(),
                                in.configuration(),
                                in.skills(),
                                in.candidates(),
                                null));
        assertEquals(Branch.PAUSED, d.branch());
        assertNull(d.primary());
        assertTrue(d.alternatives().isEmpty());
    }

    @Test
    void unavailableGraphFailsClosed() {
        var in =
                input(
                        state(Band.INDEPENDENT, true),
                        List.of(candidate("transfer", Cohort.TRANSFER, 2)));
        assertEquals(
                Branch.UNAVAILABLE,
                policy.decide(
                                new AdaptationInput(
                                        GRAPH,
                                        false,
                                        "missing",
                                        in.preferences(),
                                        T,
                                        in.policy(),
                                        in.configuration(),
                                        in.skills(),
                                        in.candidates(),
                                        null))
                        .branch());
    }

    @Test
    void allUnsupportedCandidatesDoNotSubstituteReservedOrHarderTasks() {
        var d =
                policy.decide(
                        input(
                                state(
                                        Band.DEVELOPING,
                                        false,
                                        pass("a", true, EvidenceTypes.Assistance.NONE_OBSERVED)),
                                List.of(
                                        unavailable(candidate("practice", Cohort.PRACTICE, 2)),
                                        candidate("post", Cohort.PILOT_POST, 1))));
        assertNull(d.primary());
        assertTrue(rejected(d, Reason.UNAVAILABLE));
        assertTrue(rejected(d, Reason.RESERVED_COHORT));
    }

    @Test
    void oneIndependentFamilyNeedsAnotherFreshFamily() {
        var d =
                policy.decide(
                        input(
                                state(
                                        Band.DEVELOPING,
                                        false,
                                        pass("a", true, EvidenceTypes.Assistance.NONE_OBSERVED)),
                                List.of(
                                        exposed(candidate("related", Cohort.PRACTICE, 2), false),
                                        candidate("fresh", Cohort.PRACTICE, 2))));
        assertEquals(Branch.DIVERSITY, d.branch());
        assertEquals("fresh", d.primary().challenge().id());
        assertTrue(rejected(d, Reason.FAMILIAR));
        assertEquals(List.of("a"), d.reasons().getFirst().evidenceIds());
        assertEquals(List.of(11L), d.reasons().getFirst().estimateRevisions());
    }

    @Test
    void twoFamiliesAndCurrentEstimateSelectTransferWithoutUsingXp() {
        var d =
                policy.decide(
                        input(
                                state(Band.INDEPENDENT, true),
                                List.of(
                                        candidate("practice", Cohort.PRACTICE, 2),
                                        candidate("transfer", Cohort.TRANSFER, 2))));
        assertEquals(Branch.TRANSFER, d.branch());
        assertEquals(Action.TRANSFER, d.primary().action());
    }

    @ParameterizedTest
    @EnumSource(
            value = EvidenceTypes.Assistance.class,
            names = {
                "HINT",
                "EXPLANATION_OR_SOLUTION",
                "DECLARED_EXTERNAL",
                "UNKNOWN",
                "NO_IN_APP_HINT_RECORDED"
            })
    void assistanceOrUnknownIndependenceRequiresConsolidation(EvidenceTypes.Assistance aid) {
        var d =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false, pass("a", false, aid)),
                                List.of(
                                        candidate("fresh", Cohort.PRACTICE, 2),
                                        candidate("harder", Cohort.PRACTICE, 3))));
        assertEquals(Branch.ASSISTED, d.branch());
        assertEquals(Action.CONSOLIDATE, d.primary().action());
        assertTrue(rejected(d, Reason.DIFFICULTY));
    }

    @Test
    void singleGenuineFailureTargetsSameBehaviorWithoutDifficultyIncrease() {
        var d =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false, fail("a", 1)),
                                List.of(
                                        candidate("easier", Cohort.PRACTICE, 1),
                                        candidate("harder", Cohort.PRACTICE, 3))));
        assertEquals(Branch.SINGLE_FAILURE, d.branch());
        assertEquals(Action.RETRY, d.primary().action());
        assertEquals(1, d.primary().difficulty());
    }

    @Test
    void repeatedGenuineFailuresTakePriorityOverContradictionAndStaleness() {
        var s = state(Band.INDEPENDENT, false, fail("a", 2), fail("b", 1));
        s = changed(s, Freshness.STALE, false, true, List.of(), s.activities());
        var d =
                policy.decide(
                        input(
                                s,
                                List.of(
                                        candidate("decompose", Cohort.PRACTICE, 1),
                                        candidate("same", Cohort.PRACTICE, 2))));
        assertEquals(Branch.REPEATED_FAILURE, d.branch());
        assertEquals(Action.DECOMPOSE, d.primary().action());
        assertEquals(1, d.primary().difficulty());
    }

    @Test
    void partialWithoutRelevantEligibleFailureDoesNotBecomeTwoFailures() {
        var p = pass("partial", false, EvidenceTypes.Assistance.NONE_OBSERVED);
        p =
                new Observation(
                        p.evidenceId(),
                        p.submissionId(),
                        p.family(),
                        p.challenge(),
                        p.at(),
                        EvidenceTypes.Outcome.PARTIAL,
                        p.assistance(),
                        p.exposure(),
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        List.of("unsupported"),
                        2,
                        Cohort.PRACTICE);
        assertEquals(
                Branch.DIVERSITY,
                policy.decide(
                                input(
                                        state(Band.DEVELOPING, false, p),
                                        List.of(candidate("fresh", Cohort.PRACTICE, 2))))
                        .branch());
    }

    @Test
    void staleOrContradictorySupportCannotUnlockTransfer() {
        for (boolean contradiction : List.of(false, true)) {
            var s = state(Band.INDEPENDENT, false);
            s =
                    changed(
                            s,
                            contradiction ? Freshness.CURRENT : Freshness.STALE,
                            false,
                            contradiction,
                            List.of(),
                            s.activities());
            var d =
                    policy.decide(
                            input(
                                    s,
                                    List.of(
                                            candidate("fresh", Cohort.PRACTICE, 1),
                                            candidate("transfer", Cohort.TRANSFER, 1))));
            assertEquals(Branch.UNCERTAIN, d.branch());
            assertEquals(Action.FRESH_VALIDATION, d.primary().action());
            assertTrue(rejected(d, Reason.NO_INDEPENDENT_SUPPORT));
        }
    }

    @Test
    void retentionAtExactSevenDayBoundaryIsDue() {
        var d =
                policy.decide(
                        input(
                                state(Band.TRANSFER_SUPPORTED, true),
                                List.of(candidate("retention", Cohort.RETENTION, 2))));
        assertEquals(Branch.RETENTION, d.branch());
        assertEquals(T, d.primary().dueAt());
    }

    @Test
    void interveningRehearsalHintRevealOrPreparedAttemptDefersRetention() {
        for (String activity :
                List.of(
                        "presentation",
                        "instruction:hint",
                        "instruction:solution",
                        "submission:prepared",
                        "source:recall")) {
            var s = state(Band.TRANSFER_SUPPORTED, true);
            s =
                    changed(
                            s,
                            Freshness.CURRENT,
                            true,
                            false,
                            List.of(),
                            List.of(new Activity(activity, T.minusSeconds(1), Set.of(0))));
            var d = policy.decide(input(s, List.of(candidate("retention", Cohort.RETENTION, 2))));
            assertNull(d.primary());
            assertTrue(rejected(d, Reason.RETENTION_NOT_DUE));
            assertEquals(
                    T.plus(Duration.ofDays(7)).minusSeconds(1), d.candidates().getFirst().dueAt());
        }
    }

    @Test
    void unknownOrFutureActivityCannotQualifyQuietTime() {
        for (var activities :
                List.of(
                        List.of(new Activity("unknown", null, Set.of(0))),
                        List.of(new Activity("future", T.plusSeconds(1), Set.of(0))))) {
            var s = state(Band.TRANSFER_SUPPORTED, true);
            s = changed(s, Freshness.CURRENT, true, false, List.of(), activities);
            assertNull(
                    policy.decide(input(s, List.of(candidate("retention", Cohort.RETENTION, 2))))
                            .primary());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Cohort.class,
            names = {"TRANSFER", "RETENTION"})
    void exposedOrRevealedHoldOutCannotBeRecommendedAsUnseen(Cohort cohort) {
        var band = cohort == Cohort.TRANSFER ? Band.INDEPENDENT : Band.TRANSFER_SUPPORTED;
        var d =
                policy.decide(
                        input(
                                state(band, true),
                                List.of(exposed(candidate("heldout", cohort, 1), true))));
        assertNull(d.primary());
        assertTrue(d.alternatives().isEmpty());
        assertTrue(rejected(d, Reason.FAMILIAR));
    }

    @Test
    void missingPrerequisiteOnlyPermitsExplicitExplorationOfPractice() {
        var c = candidate("practice", Cohort.PRACTICE, 1);
        c =
                new Candidate(
                        c.quest(),
                        c.challenge(),
                        c.graph(),
                        c.family(),
                        c.variant(),
                        c.cohort(),
                        c.targets(),
                        List.of(PREREQ),
                        1,
                        20,
                        c.rubric(),
                        c.manifestSha(),
                        c.rubricSha(),
                        c.evaluator(),
                        c.runner(),
                        true,
                        "",
                        true,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false,
                        false,
                        true,
                        0);
        var d = policy.decide(input(state(Band.DEVELOPING, false), List.of(c)));
        assertNull(d.primary());
        assertTrue(rejected(d, Reason.PREREQUISITE));
        assertTrue(d.alternatives().getFirst().exploratory());
    }

    @Test
    void budgetAndDeclineAreHardFiltersAndDeferExpiresExactlyAtUtcDueTime() {
        var a = candidate("a", Cohort.PRACTICE, 1);
        var b = candidate("b", Cohort.PRACTICE, 1);
        assertNull(
                policy.decide(
                                input(
                                        state(Band.DEVELOPING, false),
                                        List.of(a),
                                        prefs(10, false, Set.of(), Map.of())))
                        .primary());
        assertNull(
                policy.decide(
                                input(
                                        state(Band.DEVELOPING, false),
                                        List.of(a),
                                        prefs(60, false, Set.of("a"), Map.of())))
                        .primary());
        var d =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false),
                                List.of(a, b),
                                prefs(60, false, Set.of(), Map.of("a", T.plusSeconds(1)))));
        assertEquals("b", d.primary().challenge().id());
        assertTrue(rejected(d, Reason.DEFERRED));
        assertEquals(
                "a",
                policy.decide(
                                input(
                                        state(Band.DEVELOPING, false),
                                        List.of(a),
                                        prefs(60, false, Set.of(), Map.of("a", T))))
                        .primary()
                        .challenge()
                        .id());
    }

    @Test
    void rowOrderDoesNotChangeDecisionOrCanonicalInput() {
        var a = candidate("a", Cohort.PRACTICE, 1);
        var b = candidate("b", Cohort.PRACTICE, 1);
        var first = input(state(Band.DEVELOPING, false), List.of(b, a));
        var second = input(state(Band.DEVELOPING, false), List.of(a, b));
        assertEquals(policy.decide(first), policy.decide(second));
        assertArrayEquals(AdaptationCodec.encode(first), AdaptationCodec.encode(second));
        assertEquals("a", policy.decide(first).primary().challenge().id());
    }

    @Test
    void depletedPoolNeverFabricatesContentOrRelabelsPilotPost() {
        var d =
                policy.decide(
                        input(
                                state(Band.INDEPENDENT, true),
                                List.of(candidate("post", Cohort.PILOT_POST, 2))));
        assertNull(d.primary());
        assertEquals(Branch.DEPLETED, d.branch());
        assertTrue(rejected(d, Reason.RESERVED_COHORT));
    }

    @Test
    void currentRetentionSupportCanIncreaseOnlyOneDifficultyStep() {
        var d =
                policy.decide(
                        input(
                                state(
                                        Band.RETENTION_SUPPORTED,
                                        true,
                                        pass("a", true, EvidenceTypes.Assistance.NONE_OBSERVED)),
                                List.of(
                                        candidate("next", Cohort.PRACTICE, 3),
                                        candidate("too-hard", Cohort.PRACTICE, 4))));
        assertEquals(Branch.SUPPORTED, d.branch());
        assertEquals(3, d.primary().difficulty());
        assertTrue(rejected(d, Reason.DIFFICULTY));
    }

    @Test
    void pinnedPolicyAndConfigRejectUnknownHistoricalInterpreters() {
        var in = input(state(Band.UNKNOWN, false), List.of());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.decide(
                                new AdaptationInput(
                                        GRAPH,
                                        true,
                                        "",
                                        in.preferences(),
                                        T,
                                        "adaptive-v2",
                                        in.configuration(),
                                        in.skills(),
                                        List.of(),
                                        null)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.decide(
                                new AdaptationInput(
                                        GRAPH,
                                        true,
                                        "",
                                        in.preferences(),
                                        T,
                                        in.policy(),
                                        "future-config",
                                        in.skills(),
                                        List.of(),
                                        null)));
    }

    @Test
    void canonicalCodecRejectsTruncationTrailingDataAndUnknownVersion() {
        var in =
                input(
                        state(Band.UNKNOWN, false),
                        List.of(candidate("baseline", Cohort.BASELINE, 1)));
        var bytes = AdaptationCodec.encode(in);
        assertEquals(in, AdaptationCodec.decodeAdaptationInput(bytes));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AdaptationCodec.decodeAdaptationInput(
                                Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        AdaptationCodec.decodeAdaptationInput(
                                Arrays.copyOf(bytes, bytes.length + 1)));
        bytes[3] = 2;
        assertThrows(
                IllegalArgumentException.class, () -> AdaptationCodec.decodeAdaptationInput(bytes));
    }

    @Test
    void duplicateIdenticalCommandCannotBecomeRepeatedFailure() {
        var failure = fail("a", 1);
        var once =
                input(
                        state(Band.DEVELOPING, false, failure),
                        List.of(candidate("retry", Cohort.PRACTICE, 1)));
        var twice =
                input(
                        state(Band.DEVELOPING, false, failure, failure),
                        List.of(candidate("retry", Cohort.PRACTICE, 1)));
        assertEquals(policy.decide(once), policy.decide(twice));
        assertEquals(Branch.SINGLE_FAILURE, policy.decide(twice).branch());
    }

    @Test
    void cycleAndOrphanContextAreExplicitlyUnavailable() {
        var a = state(Band.DEVELOPING, false);
        a = changed(a, Freshness.CURRENT, false, false, List.of(PREREQ), a.activities());
        assertEquals(Branch.UNAVAILABLE, policy.decide(input(a, List.of())).branch());
        var b =
                new SkillState(
                        PREREQ,
                        12,
                        "b".repeat(64),
                        "ordinal-v1",
                        "initial",
                        Band.DEVELOPING,
                        Support.LIMITED,
                        Freshness.CURRENT,
                        false,
                        false,
                        0,
                        Set.of(0),
                        Set.of(),
                        List.of(SKILL),
                        List.of(),
                        List.of(),
                        List.of());
        var in = input(a, List.of());
        assertEquals(
                Branch.UNAVAILABLE,
                policy.decide(
                                new AdaptationInput(
                                        GRAPH,
                                        true,
                                        "",
                                        in.preferences(),
                                        T,
                                        in.policy(),
                                        in.configuration(),
                                        List.of(a, b),
                                        List.of(),
                                        null))
                        .branch());
    }

    @Test
    void retentionNotYetDueDoesNotBlockSupportedNextSkillBranch() {
        var s = state(Band.TRANSFER_SUPPORTED, true);
        s =
                changed(
                        s,
                        Freshness.CURRENT,
                        true,
                        false,
                        List.of(),
                        List.of(new Activity("recent", T.minusSeconds(1), Set.of(0))));
        var d =
                policy.decide(
                        input(
                                s,
                                List.of(
                                        candidate("retention", Cohort.RETENTION, 2),
                                        candidate("practice", Cohort.PRACTICE, 2))));
        assertEquals(Branch.SUPPORTED, d.branch());
        assertEquals(Action.PROGRESS, d.primary().action());
        assertTrue(d.reasons().stream().anyMatch(r -> r.code() == Reason.RETENTION_NOT_DUE));
    }

    @Test
    void declinedDueRetentionCannotBlockOtherSupportedWork() {
        var d =
                policy.decide(
                        input(
                                state(Band.TRANSFER_SUPPORTED, true),
                                List.of(
                                        candidate("retention", Cohort.RETENTION, 2),
                                        candidate("practice", Cohort.PRACTICE, 2)),
                                prefs(60, false, Set.of("retention"), Map.of())));
        assertEquals(Branch.SUPPORTED, d.branch());
        assertTrue(rejected(d, Reason.DECLINED));
    }

    @Test
    void repeatedFailureDoesNotEndlesslyRecommendSameTwiceSubmittedFamily() {
        var c = exposed(candidate("stuck", Cohort.PRACTICE, 2), false);
        c =
                new Candidate(
                        c.quest(),
                        c.challenge(),
                        c.graph(),
                        c.family(),
                        c.variant(),
                        c.cohort(),
                        c.targets(),
                        c.prerequisites(),
                        c.difficulty(),
                        c.effortMinutes(),
                        c.rubric(),
                        c.manifestSha(),
                        c.rubricSha(),
                        c.evaluator(),
                        c.runner(),
                        true,
                        "",
                        false,
                        2,
                        2,
                        2,
                        0,
                        false,
                        true,
                        false,
                        true,
                        0);
        var d =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false, fail("a", 2), fail("b", 1)),
                                List.of(c)));
        assertNull(d.primary());
        assertTrue(rejected(d, Reason.RETRY_EXHAUSTED));
        assertTrue(d.alternatives().getFirst().exploratory());
    }

    @Test
    void incompleteBehaviorCoverageNeverBecomesEligibleWhenOtherContentIsUnavailable() {
        var base = state(Band.INDEPENDENT, true);
        var s =
                new SkillState(
                        base.skill(),
                        base.revision(),
                        base.inputSha(),
                        base.estimatePolicy(),
                        base.epoch(),
                        base.band(),
                        base.support(),
                        base.freshness(),
                        base.current(),
                        false,
                        2,
                        Set.of(0, 1),
                        Set.of(0, 1),
                        List.of(),
                        List.of(),
                        base.activities(),
                        List.of());
        var d =
                policy.decide(
                        input(
                                s,
                                List.of(
                                        candidate("incomplete-transfer", Cohort.TRANSFER, 1),
                                        unavailable(candidate("other", Cohort.PRACTICE, 1)))));
        assertNull(d.primary());
        assertTrue(rejected(d, Reason.WRONG_BEHAVIOR));
    }

    @Test
    void relatedVariantCannotEvadeFamilyDeclineOrDefer() {
        var a = candidate("a", Cohort.PRACTICE, 1);
        var b = candidate("b", Cohort.PRACTICE, 1);
        b =
                new Candidate(
                        b.quest(),
                        b.challenge(),
                        b.graph(),
                        a.family(),
                        b.variant(),
                        b.cohort(),
                        b.targets(),
                        b.prerequisites(),
                        b.difficulty(),
                        b.effortMinutes(),
                        b.rubric(),
                        b.manifestSha(),
                        b.rubricSha(),
                        b.evaluator(),
                        b.runner(),
                        true,
                        "",
                        false,
                        0,
                        0,
                        0,
                        0,
                        false,
                        false,
                        false,
                        true,
                        0);
        var hidden =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false),
                                List.of(a, b),
                                prefs(60, false, Set.of("a"), Map.of())));
        assertNull(hidden.primary());
        assertTrue(hidden.alternatives().isEmpty());
        assertTrue(
                hidden.candidates().stream()
                        .allMatch(r -> r.exclusions().contains(Reason.DECLINED)));
        var deferred =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false),
                                List.of(a, b),
                                prefs(60, false, Set.of(), Map.of("a", T.plusSeconds(60)))));
        assertNull(deferred.primary());
        assertTrue(
                deferred.candidates().stream()
                        .allMatch(r -> r.exclusions().contains(Reason.DEFERRED)));
        assertTrue(
                deferred.candidates().stream().allMatch(r -> T.plusSeconds(60).equals(r.dueAt())));
    }

    @Test
    void expiredDeferRanksByDueTimeBeforeIdWithoutRelaxingEligibility() {
        var a = candidate("a", Cohort.PRACTICE, 1);
        var b = candidate("b", Cohort.PRACTICE, 1);
        var d =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false),
                                List.of(a, b),
                                prefs(60, false, Set.of(), Map.of("b", T.minusSeconds(1)))));
        assertEquals("b", d.primary().challenge().id());
        assertEquals(T.minusSeconds(1), d.primary().dueAt());
    }

    @Test
    void completelyEmptyCatalogIsDepletedRatherThanAnInventedRunnerFault() {
        var d = policy.decide(input(state(Band.UNKNOWN, false), List.of()));
        assertEquals(Branch.DEPLETED, d.branch());
        assertNull(d.primary());
        assertTrue(d.reasons().stream().anyMatch(r -> r.code() == Reason.CATALOG_DEPLETED));
    }

    @Test
    void familiarHarderSuccessCannotRaiseAdvancementCeiling() {
        var proof = pass("proof", true, EvidenceTypes.Assistance.NONE_OBSERVED);
        var familiar =
                new Observation(
                        "familiar",
                        "repeated-command",
                        "familiar-family",
                        new CatalogRef("familiar", 1),
                        T,
                        EvidenceTypes.Outcome.PASS,
                        EvidenceTypes.Assistance.NONE_OBSERVED,
                        EvidenceTypes.Exposure.REPEAT,
                        false,
                        false,
                        Set.of(0),
                        Set.of(),
                        List.of("check"),
                        5,
                        Cohort.PRACTICE);
        for (var band : List.of(Band.INDEPENDENT, Band.RETENTION_SUPPORTED)) {
            var cohort = band == Band.INDEPENDENT ? Cohort.TRANSFER : Cohort.PRACTICE;
            var d =
                    policy.decide(
                            input(
                                    state(band, true, proof, familiar),
                                    List.of(
                                            candidate("comparable", cohort, 3),
                                            candidate("inflated", cohort, 4))));
            assertEquals("comparable", d.primary().challenge().id());
            assertTrue(rejected(d, Reason.DIFFICULTY));
        }
        var developing =
                policy.decide(
                        input(
                                state(Band.DEVELOPING, false, proof, familiar),
                                List.of(
                                        candidate("same", Cohort.PRACTICE, 2),
                                        candidate("inflated", Cohort.PRACTICE, 3))));
        assertEquals(2, developing.primary().difficulty());
        assertTrue(rejected(developing, Reason.DIFFICULTY));
    }

    @Test
    void assistedHarderSuccessCannotRaiseConsolidationAboveIndependentSupport() {
        var proof = pass("proof", true, EvidenceTypes.Assistance.NONE_OBSERVED);
        var assisted =
                new Observation(
                        "assisted",
                        "assisted-command",
                        "assisted-family",
                        new CatalogRef("assisted", 1),
                        T,
                        EvidenceTypes.Outcome.PASS,
                        EvidenceTypes.Assistance.HINT,
                        EvidenceTypes.Exposure.FIRST_PRESENTATION,
                        false,
                        false,
                        Set.of(0),
                        Set.of(),
                        List.of("check"),
                        5,
                        Cohort.PRACTICE);
        var d =
                policy.decide(
                        input(
                                state(Band.INDEPENDENT, false, proof, assisted),
                                List.of(
                                        candidate("consolidate", Cohort.PRACTICE, 2),
                                        candidate("too-hard", Cohort.PRACTICE, 3))));
        assertEquals(Branch.ASSISTED, d.branch());
        assertEquals(2, d.primary().difficulty());
        assertTrue(rejected(d, Reason.DIFFICULTY));
    }
}

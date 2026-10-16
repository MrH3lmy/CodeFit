package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;

import org.junit.jupiter.api.*;

import java.util.*;

class QuestCatalogContractTest {
    private final BundledAssets assets = new BundledAssets();

    @Test
    void everyPublishedContractRoundTripsAndAssetsMatch() {
        var challenges = JavaTrainingPack.challenges();
        assertEquals(14, challenges.size());
        assertEquals(12, challenges.stream().map(ChallengeSpec::familyId).distinct().count());
        for (var c : challenges) {
            assertEquals(c, CatalogCodec.challenge(CatalogCodec.encode(c)));
            assets.validate(c);
            CatalogValidation.targets(
                    JavaDebuggingSkillSeed.graph(), c.domainId(), c.targets(), c.prerequisites());
            assertTrue(c.rubric().checks().stream().anyMatch(ChallengeRubric.Check::required));
        }
        assertEquals(7, JavaTrainingPack.quests().size());
        for (var q : JavaTrainingPack.quests()) {
            assertEquals(q, CatalogCodec.quest(CatalogCodec.encode(q)));
            CatalogValidation.quest(q, challenges);
            CatalogValidation.targets(
                    JavaDebuggingSkillSeed.graph(), q.domainId(), q.targets(), q.prerequisites());
        }
    }

    @Test
    void holdoutsAndIndependentPracticeFamiliesAreExplicit() {
        var cs = JavaTrainingPack.challenges();
        Map<Cohort, Long> count = new EnumMap<>(Cohort.class);
        for (var c : cs) count.merge(c.cohort(), 1L, Long::sum);
        assertEquals(
                Map.of(
                        Cohort.BASELINE,
                        3L,
                        Cohort.PRACTICE,
                        7L,
                        Cohort.TRANSFER,
                        1L,
                        Cohort.RETENTION,
                        1L,
                        Cohort.PILOT_POST,
                        2L),
                count);
        var counter = get("counter-repair");
        assertEquals(counter.familyId(), get("counter-repair-b").familyId());
        assertNotEquals(
                get("remaining-capacity").familyId(), get("distinct-id-accumulator").familyId());
        assertTrue(
                cs.stream()
                                .filter(c -> c.cohort() == Cohort.PRACTICE)
                                .map(ChallengeSpec::familyId)
                                .distinct()
                                .count()
                        >= 2);
        for (var c : cs)
            assertEquals(
                    1,
                    cs.stream()
                            .filter(x -> x.familyId().equals(c.familyId()))
                            .map(ChallengeSpec::cohort)
                            .distinct()
                            .count());
    }

    @Test
    void parserRejectsDuplicateUnknownAndNoncanonicalKeys() {
        String s = CatalogCodec.encode(get("counter-repair"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CatalogCodec.challenge(s + "domain=software-engineering\n"));
        assertThrows(
                IllegalArgumentException.class, () -> CatalogCodec.challenge("unknown=x\n" + s));
        assertThrows(
                IllegalArgumentException.class, () -> CatalogCodec.challenge("# comment\n" + s));
        assertThrows(
                IllegalArgumentException.class,
                () -> CatalogCodec.challenge(s.replace("retry.allowed=true", "retry.allowed=yes")));
    }

    @Test
    void exactSkillVersionAndBehaviorAndPrerequisitesCannotDrift() {
        var graph = JavaDebuggingSkillSeed.graph();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CatalogValidation.targets(
                                graph,
                                "software-engineering",
                                List.of(
                                        new BehaviorTarget(
                                                new SkillRef("java.concurrency.atomic-update", 2),
                                                0)),
                                List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CatalogValidation.targets(
                                graph,
                                "software-engineering",
                                List.of(
                                        new BehaviorTarget(
                                                new SkillRef("java.debugging.reproduce", 1), 1)),
                                List.of()));
        var c = get("counter-repair");
        assertThrows(
                IllegalArgumentException.class,
                () -> CatalogValidation.targets(graph, c.domainId(), c.targets(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CatalogValidation.targets(
                                graph, "other-domain", c.targets(), c.prerequisites()));
    }

    @Test
    void duplicateChecksMissingAssetsAndInvalidMappingsReject() {
        var c = get("counter-repair");
        String text = CatalogCodec.encode(c);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CatalogCodec.challenge(
                                text.replace("checks.2.id=functional", "checks.2.id=explanation")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CatalogCodec.challenge(
                                text.replace(
                                        "checks.2.assets.0=harness", "checks.2.assets.0=missing")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CatalogCodec.challenge(
                                text.replace(
                                        "checks.2.targets.0.behavior=0",
                                        "checks.2.targets.0.behavior=1")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        CatalogCodec.challenge(
                                text.replace("assets.1.kind=HARNESS", "assets.1.kind=DATA")));
    }

    @Test
    void immutableCollectionsAndSafeAssetPaths() {
        var c = get("counter-repair");
        assertThrows(UnsupportedOperationException.class, () -> c.assets().clear());
        assertThrows(UnsupportedOperationException.class, () -> c.rubric().checks().clear());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AssetRef(
                                "bad",
                                AssetKind.DATA,
                                "evolution/packs/../private",
                                "0".repeat(64)));
        var a = c.assets().getFirst();
        assertThrows(
                IllegalStateException.class,
                () -> assets.read(new AssetRef(a.id(), a.kind(), a.path(), "0".repeat(64))));
    }

    @Test
    void finiteStressIsNotARequiredSafetyProof() {
        for (var c : JavaTrainingPack.challenges())
            for (var check : c.rubric().checks())
                if (check.kind() == CheckKind.OBSERVED_CONCURRENCY
                        || check.kind() == CheckKind.TERMINATION) assertFalse(check.required());
        for (var c : JavaTrainingPack.challenges())
            if (c.runner() == Runner.JAVA21_MAIN
                    && c.targets().stream()
                            .anyMatch(t -> !t.skill().skillId().equals("java.debugging.reproduce")))
                assertTrue(
                        c.rubric().checks().stream()
                                .anyMatch(
                                        check ->
                                                check.required()
                                                        && check.kind()
                                                                == CheckKind.CONTROLLED_TRACE));
    }

    @Test
    void rubricTagsAndCheckIdsFitExistingEvidenceReferencesWithoutCreatingEvidence() {
        for (var c : JavaTrainingPack.challenges()) {
            var ref =
                    new com.codefit.evolution.evidence.EvaluationReference(
                            "future-submission",
                            c.familyId(),
                            c.rubric().ref().tag(),
                            "future-evaluator/v1",
                            "0".repeat(64),
                            c.rubric().checks().stream().map(ChallengeRubric.Check::id).toList(),
                            null);
            assertEquals(c.familyId(), ref.familyId());
        }
    }

    private ChallengeSpec get(String id) {
        return JavaTrainingPack.challenges().stream()
                .filter(c -> c.ref().id().equals(id))
                .findFirst()
                .orElseThrow();
    }
}

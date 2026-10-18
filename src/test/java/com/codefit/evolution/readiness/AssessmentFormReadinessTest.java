package com.codefit.evolution.readiness;

import static com.codefit.evolution.readiness.AssessmentFormReadiness.declared;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.*;
import com.codefit.evolution.readiness.AssessmentFormReadiness.*;

import org.junit.jupiter.api.*;

import java.util.*;

class AssessmentFormReadinessTest {
    private final AssessmentFormReadiness readiness = new AssessmentFormReadiness();

    private ChallengeSpec installed(String id) {
        return JavaTrainingPack.challenges().stream()
                .filter(s -> s.ref().equals(new CatalogRef(id, 1)))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void installedBaselineActuallyLacksInvariantParityWithBothPostForms() {
        for (String post : List.of("post-ledger", "post-expiry")) {
            var comparison =
                    readiness.compare(
                            JavaDebuggingSkillSeed.graph(),
                            declared(installed("baseline-counter")),
                            declared(installed(post)));
            assertFalse(comparison.mappedCheckParity());
            assertFalse(comparison.declaredInvariantParity());
            assertFalse(comparison.structuralParity());
            assertTrue(comparison.gaps().contains("REQUIRED_BEHAVIOR_CHECK_PROFILE_DIFFERS"));
            assertTrue(comparison.gaps().contains("DECLARED_REQUIRED_INVARIANTS_DIFFER"));
        }
    }

    @Test
    void candidateHasRequiredBehaviorCheckAndDeclaredInvariantParityButIsNotHumanApproval() {
        var candidate = PilotAssessmentForms.baselineCapacity();
        for (String post : List.of("post-ledger", "post-expiry")) {
            var comparison =
                    readiness.compare(
                            JavaDebuggingSkillSeed.graph(),
                            declared(candidate),
                            declared(installed(post)));
            assertTrue(comparison.mappedCheckParity());
            assertTrue(comparison.declaredInvariantParity());
            assertTrue(comparison.structuralParity(), comparison.gaps().toString());
            assertFalse(comparison.baseline().installed());
            assertTrue(comparison.post().installed());
            assertTrue(comparison.interpretation().contains("independent human"));
        }
        var report = readiness.installedReport(JavaDebuggingSkillSeed.graph());
        assertEquals(Status.NOT_READY, report.technicalImplementation());
        assertEquals(Status.NOT_READY, report.protocolFreeze());
        assertEquals(Status.NOT_READY, report.pilotExecution());
    }

    @Test
    void inventoryMakesRelatedTraceFormsAndSingletonHeldOutPoolsExplicit() {
        var report = readiness.installedReport(JavaDebuggingSkillSeed.graph());
        assertEquals(14, report.inventory().size());
        assertEquals(1, report.candidates().size());
        assertEquals(2, report.distinctFamilies().get(Cohort.BASELINE));
        assertEquals(1, report.distinctFamilies().get(Cohort.TRANSFER));
        assertEquals(1, report.distinctFamilies().get(Cohort.RETENTION));
        assertEquals(
                installed("baseline-trace").familyId(), installed("baseline-localize").familyId());
        for (var form : report.inventory()) {
            assertTrue(form.installed());
            assertEquals(JavaDebuggingSkillSeed.GRAPH, form.graph());
            assertFalse(form.assetDigests().isEmpty());
            assertTrue(form.manifestSha256().matches("[0-9a-f]{64}"));
        }
        var familyRoles = new HashMap<String, Set<Cohort>>();
        report.inventory()
                .forEach(
                        f ->
                                familyRoles
                                        .computeIfAbsent(
                                                f.family(), k -> EnumSet.noneOf(Cohort.class))
                                        .add(f.cohort()));
        assertTrue(familyRoles.values().stream().allMatch(c -> c.size() == 1));
        assertFalse(familyRoles.containsKey(report.candidates().getFirst().family()));
    }

    @Test
    void immutableManifestAndExactGraphFailClosedRatherThanSelectingLatest() {
        var candidate = PilotAssessmentForms.baselineCapacity();
        var assets = new BundledAssets();
        assertEquals(
                PilotAssessmentForms.MANIFEST_SHA256,
                CatalogCodec.digest(assets.manifest(PilotAssessmentForms.MANIFEST)));
        assertEquals(candidate, CatalogCodec.challenge(CatalogCodec.encode(candidate)));
        var newerGraph =
                new SkillGraph(
                        new GraphRef(JavaDebuggingSkillSeed.GRAPH.graphId(), 2),
                        JavaDebuggingSkillSeed.graph().definitions(),
                        JavaDebuggingSkillSeed.graph().prerequisites());
        var comparison =
                readiness.compare(
                        newerGraph, declared(candidate), declared(installed("post-ledger")));
        assertTrue(comparison.gaps().contains("EXACT_GRAPH_MISMATCH"));
        assertFalse(comparison.structuralParity());
    }

    @Test
    void invalidOptionalOrUnmappedInvariantBindingsAreRejected() {
        var s = installed("post-ledger");
        var behavior = s.targets().getFirst();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Form(
                                s,
                                List.of(
                                        new InvariantBinding(
                                                Invariant.ATOMIC_OPERATION,
                                                "explanation",
                                                behavior))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Form(
                                s,
                                List.of(
                                        new InvariantBinding(
                                                Invariant.ATOMIC_OPERATION,
                                                "absent-check",
                                                behavior))));
        var binding =
                new InvariantBinding(Invariant.ATOMIC_OPERATION, "operation-boundary", behavior);
        assertThrows(IllegalArgumentException.class, () -> new Form(s, List.of(binding, binding)));
    }

    @Test
    void wrongRoleOrRelatedFamilyCannotBecomeAPostAssessment() {
        var s = installed("post-ledger");
        assertFalse(
                readiness
                        .compare(JavaDebuggingSkillSeed.graph(), declared(s), declared(s))
                        .structuralParity());
        assertTrue(
                readiness
                        .compare(JavaDebuggingSkillSeed.graph(), declared(s), declared(s))
                        .gaps()
                        .contains("RELATED_OR_REUSED_FORM"));
        assertTrue(
                readiness
                        .compare(JavaDebuggingSkillSeed.graph(), declared(s), declared(s))
                        .gaps()
                        .contains("ROLE_MISMATCH"));
    }

    @Test
    void reportDoesNotCarryPromptsAnswersSourceHiddenInputsOrLearnerData() {
        String report = readiness.installedReport(JavaDebuggingSkillSeed.graph()).toString();
        var assets = new BundledAssets();
        var spec = PilotAssessmentForms.baselineCapacity();
        for (var role : List.of(AssetKind.PROMPT, AssetKind.REFERENCE, AssetKind.HARNESS)) {
            String secret =
                    assets.read(
                            spec.assets().stream()
                                    .filter(a -> a.kind() == role)
                                    .findFirst()
                                    .orElseThrow());
            assertFalse(report.contains(secret));
        }
        assertFalse(report.contains("System.in"));
        assertFalse(report.contains("expectedStdout"));
        assertFalse(report.contains("readiness-v1/baseline-capacity/"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> readiness.summarize(spec).assetDigests().clear());
    }
}

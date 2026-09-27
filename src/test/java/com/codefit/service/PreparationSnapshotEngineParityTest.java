package com.codefit.service;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewMaterialType;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import com.codefit.peer.protocol.DomainSnapshot;
import com.codefit.peer.protocol.PreparationSnapshot;
import com.codefit.peer.protocol.PreparationSnapshots;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property test: for thousands of synthetic profiles scored by the <em>real</em> readiness engine
 * ({@link InterviewReadinessService#buildDomainReadiness} / {@code buildResult}), the peer snapshot
 * accepts the engine's output and reproduces its overall score, coverage, status, and blocking gates
 * exactly. This includes floating-point rounding ties such as thirds that sum to x.5. Pure computation
 * with a fixed seed; no database.
 */
class PreparationSnapshotEngineParityTest {
    private static final Instant CAPTURED = Instant.parse("2026-09-21T18:00:00Z");

    @Test
    void snapshotsAcceptAndReproduceRealEngineResults() {
        Random random = new Random(180);
        for (int iteration = 0; iteration < 5_000; iteration++) {
            List<InterviewDomain> domains = new ArrayList<>();
            List<InterviewDomainReadiness> readiness = new ArrayList<>();
            int domainCount = 1 + random.nextInt(6);
            int[] weights = weights(random, domainCount);
            List<String> ids = new ArrayList<>(List.of("zeta", "alpha", "mu", "beta", "omega", "kappa"));
            java.util.Collections.shuffle(ids, random); // profile order deliberately differs from id order
            for (int d = 0; d < domainCount; d++) {
                boolean critical = random.nextInt(3) == 0;
                Integer threshold = critical || random.nextBoolean() ? 50 + random.nextInt(40) : null;
                int requirementCount = random.nextInt(8) == 0 ? 0 : 1 + random.nextInt(7);
                List<InterviewRequirement> requirements = new ArrayList<>();
                List<InterviewRequirementReadiness> requirementReadiness = new ArrayList<>();
                for (int r = 0; r < requirementCount; r++) {
                    InterviewRequirement requirement = InterviewRequirement.available("d" + d + "-r" + r, "R", null,
                            InterviewMaterialType.DECK, "deck-" + d + "-" + r);
                    requirements.add(requirement);
                    InterviewMaterialType source = random.nextInt(4) == 0 ? InterviewMaterialType.MOCK_INTERVIEW : InterviewMaterialType.DECK;
                    requirementReadiness.add(random.nextInt(3) == 0
                            ? InterviewRequirementReadiness.unmeasurable(requirement, source, "no data")
                            : InterviewRequirementReadiness.measured(requirement, source, random.nextInt(101), "synthetic"));
                }
                InterviewDomain domain = new InterviewDomain(ids.get(d), "Domain " + d, null, weights[d], critical,
                        threshold, requirements);
                domains.add(domain);
                readiness.add(InterviewReadinessService.buildDomainReadiness(domain, requirementReadiness));
            }
            InterviewPreparationProfile profile = new InterviewPreparationProfile("parity", "Parity", null, domains);
            InterviewReadinessService.InterviewReadinessPolicy policy =
                    new InterviewReadinessService.InterviewReadinessPolicy(50 + random.nextInt(50));
            InterviewReadinessResult result = InterviewReadinessService.buildResult(profile, readiness, policy);

            PreparationSnapshot snapshot = PreparationSnapshots.fromReadiness(profile, result,
                    policy.overallReadinessThresholdPercent(), CAPTURED);

            String context = "iteration " + iteration;
            assertEquals(result.overallReadinessPercent(), snapshot.overallPercent(), context);
            assertEquals(result.coveragePercent(), snapshot.coveragePercent(), context);
            assertEquals(result.status().name(), snapshot.status().name(), context);
            assertEquals(result.blockingCriticalDomainIds(), snapshot.blockingCriticalDomainIds(), context);
            assertEquals(result.domains().stream().map(InterviewDomainReadiness::domainId).toList(),
                    snapshot.domains().stream().map(DomainSnapshot::domainId).toList(), context);
        }
    }

    @Test
    void floatingPointTieIsReproducedNotRecomputedExactly() {
        // Two 50% domains, each 1 of 3 requirements measured, scores 70 and 71: the exact rational
        // overall is 70.5, but the engine's binary64 arithmetic decides the rounding. The snapshot must agree.
        List<InterviewDomain> domains = new ArrayList<>();
        List<InterviewDomainReadiness> readiness = new ArrayList<>();
        int[] scores = {70, 71};
        for (int d = 0; d < 2; d++) {
            List<InterviewRequirement> requirements = new ArrayList<>();
            List<InterviewRequirementReadiness> requirementReadiness = new ArrayList<>();
            for (int r = 0; r < 3; r++) {
                InterviewRequirement requirement = InterviewRequirement.available("t" + d + r, "R", null, InterviewMaterialType.DECK, "k" + d + r);
                requirements.add(requirement);
                requirementReadiness.add(r == 0
                        ? InterviewRequirementReadiness.measured(requirement, InterviewMaterialType.DECK, scores[d], "s")
                        : InterviewRequirementReadiness.unmeasurable(requirement, InterviewMaterialType.DECK, "none"));
            }
            InterviewDomain domain = new InterviewDomain("tie-" + d, "Tie", null, 50, false, null, requirements);
            domains.add(domain);
            readiness.add(InterviewReadinessService.buildDomainReadiness(domain, requirementReadiness));
        }
        InterviewPreparationProfile profile = new InterviewPreparationProfile("tie", "Tie", null, domains);
        InterviewReadinessResult result = InterviewReadinessService.buildResult(profile, readiness, InterviewReadinessService.DEFAULT_POLICY);
        PreparationSnapshot snapshot = PreparationSnapshots.fromReadiness(profile, result, 75, CAPTURED);
        assertEquals(result.overallReadinessPercent(), snapshot.overallPercent());
    }

    private static int[] weights(Random random, int count) {
        int[] weights = new int[count];
        int remaining = 100;
        for (int i = 0; i < count - 1; i++) {
            weights[i] = random.nextInt(remaining + 1);
            remaining -= weights[i];
        }
        weights[count - 1] = remaining;
        return weights;
    }
}

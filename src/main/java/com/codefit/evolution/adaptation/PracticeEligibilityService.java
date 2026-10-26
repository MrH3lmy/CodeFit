package com.codefit.evolution.adaptation;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.quest.BundledAssets;
import com.codefit.repository.SkillCatalogRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Explicit exploratory practice only. No estimate/decision publication or reward reconciliation.
 */
public final class PracticeEligibilityService {
    public List<AdaptationInput.Candidate> eligible(
            Connection c, Instant at, boolean runnerAvailable) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("Coherent transaction required");
        var graph = JavaDebuggingSkillSeed.GRAPH;
        var actual = new SkillCatalogRepository().findGraph(c, graph);
        if (actual.isEmpty() || !actual.get().equals(JavaDebuggingSkillSeed.graph()))
            throw new IllegalStateException("Exact graph unavailable");
        var candidates =
                new AdaptationInputReader(new BundledAssets())
                        .candidates(c, graph, at, runnerAvailable);
        var input =
                new AdaptationInput(
                        graph,
                        true,
                        "",
                        new AdaptationDecisionRepository().preferences(c, at),
                        at,
                        AdaptiveQuestPolicy.VERSION,
                        AdaptiveQuestPolicy.CONFIGURATION,
                        List.of(),
                        candidates,
                        null);
        return candidates.stream()
                .filter(candidate -> AdaptiveQuestPolicy.exploratoryEligible(input, candidate))
                .toList();
    }
}

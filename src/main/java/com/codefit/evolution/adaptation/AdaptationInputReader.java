package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationInput.*;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.*;
import com.codefit.repository.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Exact installed pilot adapter. Core policy knows no Java IDs or SQL. */
final class AdaptationInputReader {
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private final ChallengeExposureRepository exposure = new ChallengeExposureRepository();
    private final BundledAssets assets;

    AdaptationInputReader(BundledAssets assets) {
        this.assets = assets;
    }

    List<Candidate> candidates(Connection c, GraphRef graph, Instant asOf, boolean runnerAvailable)
            throws SQLException {
        var result = new ArrayList<Candidate>();
        var submissions = new HashMap<String, Integer>();
        var evaluations = new DurableEvaluationRepository();
        long cursor = 0;
        int scanned = 0;
        while (true) {
            var page = evaluations.submissionExposurePage(c, cursor, 128);
            for (var command : page) {
                // Future submissions conservatively remain familiarity; never supply proof.
                var spec = catalog.findChallenge(c, command.challenge()).orElseThrow();
                submissions.merge(spec.familyId(), 1, Integer::sum);
            }
            scanned += page.size();
            if (scanned > 10000)
                throw new IllegalStateException(
                        "Submission history exceeds bounded adaptation input");
            if (page.size() < 128) break;
            cursor = page.getLast().id();
        }
        for (var installedQuest : JavaTrainingPack.quests()) {
            var actualQuest = catalog.findQuest(c, installedQuest.ref());
            boolean questAvailable = actualQuest.filter(installedQuest::equals).isPresent();
            for (var ref : installedQuest.challenges()) {
                var installed =
                        JavaTrainingPack.challenges().stream()
                                .filter(s -> s.ref().equals(ref))
                                .findFirst()
                                .orElseThrow();
                if (!installed.graph().equals(graph)) continue;
                var actual = catalog.findChallenge(c, ref);
                boolean available = questAvailable && actual.filter(installed::equals).isPresent();
                String limitation = available ? "" : "Exact published catalog context unavailable";
                if (available) {
                    try {
                        assets.validate(installed);
                        catalog.verifyBinding(c, installed);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        available = false;
                        limitation = "Exact installed asset or backing identity unavailable";
                    }
                }
                if (installed.runner() == Runner.JAVA21_MAIN && !runnerAvailable) {
                    available = false;
                    limitation = "Local Java 21 runner unavailable";
                }
                var context = exposure.context(c, installed);
                boolean equivalent =
                        Set.of("inventory-transfer", "queue-retention").contains(ref.id())
                                && ref.version() == 1
                                && graph.equals(JavaDebuggingSkillSeed.GRAPH);
                result.add(
                        new Candidate(
                                installedQuest.ref(),
                                ref,
                                graph,
                                installed.familyId(),
                                installed.variantId(),
                                installed.cohort(),
                                installed.targets(),
                                installed.prerequisites(),
                                installed.difficulty(),
                                installed.effortMinutes(),
                                installed.rubric().ref(),
                                CatalogCodec.digest(CatalogCodec.encode(installed)),
                                CatalogCodec.digest(CatalogCodec.encode(installed.rubric())),
                                JavaChallengeEvaluator.VERSION,
                                installed.runner().name(),
                                available,
                                limitation,
                                equivalent,
                                context.familyPresentations(),
                                context.variantPresentations(),
                                submissions.getOrDefault(installed.familyId(), 0),
                                context.highestHint(),
                                context.solutionRevealed(),
                                context.knownFamiliar(),
                                skipped(c, ref, asOf),
                                installed.retry().allowed(),
                                installed.retry().maximumPresentations()));
            }
        }
        return List.copyOf(result);
    }

    static SkillState skill(
            EstimateInput input, EstimateRevisionRepository.Revision revision, SkillGraph graph) {
        var e = revision.estimate();
        var observations = new ArrayList<Observation>();
        for (var contribution : e.contributions()) {
            var source =
                    input.observations().stream()
                            .filter(
                                    o ->
                                            o.id().equals(contribution.evidenceId())
                                                    && o.mapping().equals(contribution.mapping()))
                            .findFirst()
                            .orElseThrow();
            var facts = source.facts();
            var context = source.context();
            int difficulty =
                    JavaTrainingPack.challenges().stream()
                            .filter(s -> s.ref().equals(context.challenge()))
                            .mapToInt(ChallengeSpec::difficulty)
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "Exact historical challenge difficulty"
                                                        + " unavailable"));
            observations.add(
                    new Observation(
                            source.id(),
                            contribution.submissionId(),
                            contribution.family(),
                            context.challenge(),
                            facts.time().observedAt(),
                            facts.outcome(),
                            facts.assistance(),
                            facts.exposure(),
                            contribution.independent(),
                            contribution.eligibleDeliberateFailure(),
                            contribution.behaviors(),
                            contribution.failedBehaviors(),
                            contribution.checkIds(),
                            difficulty,
                            context.cohort()));
        }
        var required = new TreeSet<Integer>();
        for (int i = 0; i < input.skill().observableBehaviors().size(); i++) required.add(i);
        return new SkillState(
                e.skill(),
                revision.id(),
                revision.inputSha(),
                e.policyVersion(),
                e.epoch().id(),
                e.band(),
                e.support(),
                e.freshness(),
                e.currentCapabilitySupported(),
                e.contradicted(),
                e.independentFamilies(),
                required,
                e.supportedBehaviors(),
                graph.prerequisiteTraversal(e.skill()).stream().map(SkillDefinition::ref).toList(),
                observations,
                input.activities().stream()
                        .map(a -> new Activity(a.id(), a.at(), a.behaviors()))
                        .toList(),
                e.exclusions().stream()
                        .map(x -> x.evidenceId() + ":" + x.mapping() + ":" + x.code())
                        .toList());
    }

    private boolean skipped(Connection c, CatalogRef ref, Instant asOf) throws SQLException {
        boolean all = false, task = false;
        try (var s =
                c.prepareStatement(
                        "SELECT task,action,chosen_at FROM evolution_baseline_choices WHERE"
                                + " graph_id=? AND graph_version=? AND task IN ('*',?) ORDER BY"
                                + " sequence")) {
            s.setString(1, JavaDebuggingSkillSeed.GRAPH.graphId());
            s.setInt(2, JavaDebuggingSkillSeed.GRAPH.graphVersion());
            s.setString(3, ref.tag());
            try (var r = s.executeQuery()) {
                while (r.next())
                    if (!Instant.parse(r.getString(3)).isAfter(asOf)) {
                        boolean skip = r.getString(2).equals("SKIP");
                        if (r.getString(1).equals("*")) all = skip;
                        else task = skip;
                    }
            }
        }
        return all || task;
    }
}

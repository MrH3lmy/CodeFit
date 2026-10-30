package com.codefit.desktop;

import com.codefit.config.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.ChallengePresentationService;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Host-only test fixture; genuine engine records in a synthetic disposable profile. Never packaged.
 */
public final class AnalyticsFixture {
    public static final Instant TIME = Instant.parse("2026-06-01T12:00:00Z");

    static Clock clock(int days) {
        return Clock.fixed(TIME.plusSeconds(days * 86400L), ZoneOffset.UTC);
    }

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        String previous = DatabaseConfig.currentDatabaseUrl();
        try (var p = ExperimentalProfile.openLearning(path)) {
            DatabaseConfig.useDatabaseUrl("jdbc:sqlite:" + path.resolve("codefit.db"));
            if (args.length > 1 && args[1].equals("empty")) return;
            seed(p);
        } finally {
            DatabaseConfig.useDatabaseUrl(previous);
        }
    }

    static void seed(ExperimentalProfile p) throws Exception {
        reconcile(p, 0);
        String first = evaluate("counter-repair", 1);
        reconcile(p, 2);
        evaluate("remaining-capacity", 3);
        reconcile(p, 4);
        new ChallengeEvaluationCoordinator(clock(5))
                .invalidate(first, UUID.randomUUID().toString(), false);
        new ChallengeEvaluationCoordinator(clock(6))
                .invalidate(first, UUID.randomUUID().toString(), true);
        reconcile(p, 7);
    }

    static void reconcile(ExperimentalProfile p, int days) throws Exception {
        var r =
                new PracticeSkillsService(p, clock(days))
                        .reconcile(UUID.randomUUID().toString(), UUID.randomUUID().toString());
        if (!r.state().equals("PUBLISHED"))
            throw new IllegalStateException("fixture publication failed");
    }

    static String evaluate(String challenge, int days) throws Exception {
        return evaluate(challenge, days, false);
    }

    static String evaluate(String challenge, int days, boolean mutant) throws Exception {
        var spec =
                JavaTrainingPack.challenges().stream()
                        .filter(x -> x.ref().id().equals(challenge))
                        .findFirst()
                        .orElseThrow();
        var quest =
                JavaTrainingPack.quests().stream()
                        .filter(x -> x.challenges().contains(spec.ref()))
                        .findFirst()
                        .orElseThrow();
        var assets = new BundledAssets();
        String presentation = "analytics-test-" + UUID.randomUUID(),
                id = UUID.randomUUID().toString();
        new ChallengePresentationService(new QuestCatalogRepository(), assets, clock(days))
                .present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                spec.ref(),
                                spec.cohort(),
                                QuestTypes.Familiarity.DECLARED_UNFAMILIAR));
        String source =
                assets.read(
                        spec.assets().stream()
                                .filter(
                                        a ->
                                                a.kind()
                                                        == (mutant
                                                                ? QuestTypes.AssetKind.MUTANT
                                                                : QuestTypes.AssetKind.REFERENCE))
                                .findFirst()
                                .orElseThrow());
        var result =
                new ChallengeEvaluationCoordinator(clock(days))
                        .submit(
                                new SubmissionRequest(
                                        id,
                                        presentation,
                                        source,
                                        EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                                        ""));
        if (mutant
                ? result.outcome() != EvaluationTypes.Outcome.FAILED
                        && result.outcome() != EvaluationTypes.Outcome.PARTIAL
                : result.outcome() != EvaluationTypes.Outcome.PASSED)
            throw new IllegalStateException("fixture evaluation failed");
        return id;
    }
}

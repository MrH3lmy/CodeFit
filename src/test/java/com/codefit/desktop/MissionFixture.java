package com.codefit.desktop;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.QuestCatalogRepository;
import com.codefit.service.ChallengePresentationService;

import java.nio.file.*;
import java.time.*;

/** Synthetic test-only history, built through actual presentation/evaluation owners. */
public final class MissionFixture {
    public static final Instant AT = Instant.parse("2026-06-12T12:00:00Z");

    public static void populate(Path file, String mode) throws Exception {
        String previous = DatabaseConfig.currentDatabaseUrl();
        DatabaseConfig.useDatabaseFile(file);
        try {
            if (mode.equals("empty")) return;
            var clock = Clock.fixed(AT, ZoneOffset.UTC);
            var assets = new BundledAssets();
            var spec =
                    JavaTrainingPack.challenges().stream()
                            .filter(s -> s.ref().id().equals("counter-repair"))
                            .findFirst()
                            .orElseThrow();
            var quest =
                    JavaTrainingPack.quests().stream()
                            .filter(q -> q.challenges().contains(spec.ref()))
                            .findFirst()
                            .orElseThrow();
            var evaluator = new ChallengeEvaluationCoordinator(clock);
            var presentations =
                    new ChallengePresentationService(new QuestCatalogRepository(), assets, clock);
            for (int i = 1; i <= (mode.equals("large") ? 25 : 3); i++) {
                String presentation = "mission-presentation-" + i;
                presentations.present(
                        new ChallengePresentation.Request(
                                presentation,
                                quest.ref(),
                                spec.ref(),
                                spec.cohort(),
                                QuestTypes.Familiarity.UNKNOWN));
                String source =
                        assets.read(
                                spec.assets().stream()
                                        .filter(a -> a.kind() == QuestTypes.AssetKind.REFERENCE)
                                        .findFirst()
                                        .orElseThrow());
                var request =
                        new SubmissionRequest(
                                "mission-submission-" + i,
                                presentation,
                                source,
                                EvaluationTypes.OutsideAssistance.NONE_DECLARED,
                                "PRIVATE-REFLECTION-SECRET");
                evaluator.prepare(request);
                if (i == 1 && !mode.equals("large")) evaluator.submit(request);
                if (i == 2 && !mode.equals("large")) {
                    evaluator.cancel(request.id());
                    evaluator.resume(request.id());
                }
            }
            if (mode.equals("withdrawn"))
                evaluator.invalidate("mission-submission-1", "mission-withdrawal", true);
            try (var c = DatabaseConfig.getConnection();
                    var s = c.createStatement()) {
                // Header-only synthetic decision: projection must not treat outputs as assignments.
                s.executeUpdate(
                        "INSERT INTO"
                            + " evolution_adaptation_decisions(id,input_sha,policy,configuration,graph_id,graph_version,preferences_version,as_of,inputs,output_sha,outputs)"
                            + " VALUES('decision-"
                                + "a".repeat(64)
                                + "','unused','PRIVATE-POLICY','private','private',1,1,'2026-06-12T12:00:00Z',X'00','unused',X'00')");
                switch (mode) {
                    case "recorded", "large", "withdrawn" -> {}
                    case "corrupt" -> {
                        s.execute("DROP TRIGGER evolution_submission_snapshot_immutable");
                        s.executeUpdate("UPDATE evolution_submissions SET snapshot=X'00'");
                    }
                    case "missing" ->
                            s.execute(
                                    "ALTER TABLE evolution_quest_definitions RENAME TO"
                                            + " missing_quests");
                    case "conflicting" -> {
                        s.execute("DROP TRIGGER evolution_quest_definitions_no_update");
                        s.executeUpdate(
                                "UPDATE evolution_quest_definitions SET"
                                        + " payload='PRIVATE-CHANGED-MANIFEST'");
                    }
                    default -> throw new IllegalArgumentException(mode);
                }
            }
        } finally {
            DatabaseConfig.useDatabaseUrl(previous);
        }
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        LearnerFixture.create(file);
        populate(file, args[1]);
    }
}

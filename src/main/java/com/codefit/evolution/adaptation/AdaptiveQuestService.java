package com.codefit.evolution.adaptation;

import static com.codefit.evolution.adaptation.AdaptationInput.*;
import static com.codefit.evolution.adaptation.AdaptationTypes.*;

import com.codefit.config.*;
import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.AdaptationDecisionRepository.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.progression.ProgressionService;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * On-demand UI-ready boundary. Evaluation commits independently before this service is called. One
 * read snapshot freezes estimates, exposure, catalog and preferences. CPU policy work is outside
 * SQL transactions. Same-connection data_version is rechecked under the writer before publication.
 */
public final class AdaptiveQuestService {
    private static final Object[] START_LOCKS = new Object[64];

    static {
        Arrays.setAll(START_LOCKS, ignored -> new Object());
    }

    private final Clock clock;
    private final BundledAssets assets;
    private final BooleanSupplier runnerAvailable;
    private final ProblemLibraryService curriculum;
    private final Runnable afterRead;
    private final AdaptationDecisionRepository decisions = new AdaptationDecisionRepository();
    private final MasteryEstimateService estimates = new MasteryEstimateService();
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private final ChallengePresentationService presentations;

    public AdaptiveQuestService() {
        this(
                Clock.systemUTC(),
                new BundledAssets(),
                new JavaCodeRunner()::isAvailable,
                new ProblemLibraryService());
    }

    public AdaptiveQuestService(
            Clock clock,
            BundledAssets assets,
            BooleanSupplier runnerAvailable,
            ProblemLibraryService curriculum) {
        this(clock, assets, runnerAvailable, curriculum, () -> {});
    }

    AdaptiveQuestService(
            Clock clock,
            BundledAssets assets,
            BooleanSupplier runnerAvailable,
            ProblemLibraryService curriculum,
            Runnable afterRead) {
        this.clock = Objects.requireNonNull(clock);
        this.assets = Objects.requireNonNull(assets);
        this.runnerAvailable = Objects.requireNonNull(runnerAvailable);
        this.curriculum = Objects.requireNonNull(curriculum);
        this.afterRead = Objects.requireNonNull(afterRead);
        this.presentations = new ChallengePresentationService(catalog, assets, clock);
    }

    public Decision recommend(Instant asOf) {
        return recommend(JavaDebuggingSkillSeed.GRAPH, asOf);
    }

    /** Current Player System entry point; historical/replayed decisions never grant milestones. */
    public Decision recommendCurrent() {
        var decision = recommend(JavaDebuggingSkillSeed.GRAPH, clock.instant());
        new ProgressionService()
                .reconcileCurrentMilestones(decision.input().graph(), decision.input().asOf());
        return decision;
    }

    public Decision recommend(GraphRef graph, Instant asOf) {
        return connection(c -> derive(c, graph, Objects.requireNonNull(asOf)).decision());
    }

    private record Derived(Decision decision, long version) {}

    private Derived derive(Connection c, GraphRef graph, Instant asOf) throws SQLException {
        for (int retry = 0; retry < 3; retry++) {
            long before = version(c);
            c.setAutoCommit(false);
            Preferences preferences;
            List<Candidate> candidates;
            MasteryEstimateService.CapturedPilot captured;
            SkillGraph skillGraph;
            String limitation = "";
            try {
                preferences = decisions.preferences(c, asOf);
                // V1 adapter pins the exact approved graph. Future versions never inherit
                // eligibility.
                skillGraph =
                        graph.equals(JavaDebuggingSkillSeed.GRAPH)
                                ? new SkillCatalogRepository().findGraph(c, graph).orElse(null)
                                : null;
                boolean evolution =
                        preferences.track() == Track.EVOLUTION
                                && !preferences.paused()
                                && skillGraph != null;
                captured = evolution ? estimates.capturePilot(c, asOf) : null;
                candidates =
                        evolution
                                ? new AdaptationInputReader(assets)
                                        .candidates(c, graph, asOf, runnerAvailable.getAsBoolean())
                                : List.of();
                if (skillGraph == null) limitation = "No supported adapter for this exact graph";
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
            c.setAutoCommit(true);
            // Ordinary curriculum selection is delegated exactly, never re-ranked by Evolution.
            Long problem = null;
            if (preferences.track() == Track.CURRICULUM && !preferences.paused())
                problem =
                        curriculum
                                .getNextRecommendedProblem()
                                .map(e -> e.problem().getId())
                                .orElse(null);
            afterRead.run();
            var inputs = captured == null ? List.<EstimateInput>of() : captured.inputs();
            var prepared =
                    captured == null
                            ? List.<MasteryEstimateService.PreparedEstimate>of()
                            : estimates.prepare(captured);
            var revisions = new ArrayList<EstimateRevisionRepository.Revision>();
            c.setAutoCommit(false);
            try {
                catalog.lock(c);
                if (version(c) != before) {
                    c.rollback();
                    c.setAutoCommit(true);
                    continue;
                }
                for (var item : prepared) revisions.add(estimates.publish(c, item));
                c.commit();
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
            c.setAutoCommit(true);
            var skills = new ArrayList<SkillState>();
            for (int i = 0; i < inputs.size(); i++)
                skills.add(
                        AdaptationInputReader.skill(inputs.get(i), revisions.get(i), skillGraph));
            var input =
                    new AdaptationInput(
                            graph,
                            skillGraph != null,
                            limitation,
                            preferences,
                            asOf,
                            AdaptiveQuestPolicy.VERSION,
                            AdaptiveQuestPolicy.CONFIGURATION,
                            skills,
                            candidates,
                            problem);
            var result = new AdaptiveQuestPolicy().decide(input);
            // No write transaction spans policy computation/encoding.
            byte[] decisionBytes = AdaptationCodec.encode(input);
            String decisionSha = CatalogCodec.digest(decisionBytes);
            byte[] outputBytes = AdaptationCodec.encode(result);
            String outputSha = CatalogCodec.digest(outputBytes);
            c.setAutoCommit(false);
            try {
                catalog.lock(c);
                if (version(c) != before) {
                    c.rollback();
                    c.setAutoCommit(true);
                    continue;
                }
                var decision =
                        decisions.save(
                                c,
                                input,
                                result,
                                decisionBytes,
                                decisionSha,
                                outputBytes,
                                outputSha);
                c.commit();
                c.setAutoCommit(true);
                return new Derived(decision, before);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
        throw new IllegalStateException(
                "Learning state changed during adaptation; recommendation pending, retry on"
                        + " demand");
    }

    /** Commands are independent durable learner choices; never evidence or negative performance. */
    public Choice respond(LearnerCommand command) {
        return connection(
                c -> {
                    var replay = decisions.choice(c, command.id());
                    if (replay.isPresent()) return sameCommand(replay.get(), command);
                    var original =
                            command.decisionId() == null
                                    ? null
                                    : decisions
                                            .find(c, command.decisionId())
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalArgumentException(
                                                                    "Unknown decision"));
                    GraphRef graph =
                            original == null
                                    ? JavaDebuggingSkillSeed.GRAPH
                                    : original.input().graph();
                    validateOriginal(command, original);
                    boolean selecting =
                            Set.of(
                                            CommandKind.ACCEPT,
                                            CommandKind.CHOOSE_ALTERNATIVE,
                                            CommandKind.EXPLORE)
                                    .contains(command.kind());
                    for (int retry = 0; retry < 3; retry++) {
                        long before;
                        Preferences current;
                        Instant at = clock.instant();
                        if (selecting) {
                            var derived = derive(c, graph, at);
                            before = derived.version();
                            current = derived.decision().input().preferences();
                            if (command.kind() == CommandKind.ACCEPT
                                    && command.challenge() == null) {
                                if (derived.decision().result().primary() == null
                                        || derived.decision().result().primary().action()
                                                != Action.CURRICULUM
                                        || !Objects.equals(
                                                derived.decision().input().curriculumProblem(),
                                                original.input().curriculumProblem()))
                                    throw new IllegalStateException(
                                            "Curriculum frontier changed; request a current"
                                                    + " decision");
                            } else requireEligible(command, derived.decision());
                        } else {
                            before = version(c);
                            c.setAutoCommit(false);
                            try {
                                current = decisions.preferences(c, at);
                                c.commit();
                            } catch (SQLException | RuntimeException e) {
                                c.rollback();
                                throw e;
                            }
                            c.setAutoCommit(true);
                        }
                        c.setAutoCommit(false);
                        try {
                            catalog.lock(c);
                            // Concurrent replay converges even if another responder committed
                            // first.
                            replay = decisions.choice(c, command.id());
                            if (replay.isPresent()) {
                                var choice = sameCommand(replay.get(), command);
                                c.commit();
                                c.setAutoCommit(true);
                                return choice;
                            }
                            if (version(c) != before) {
                                c.rollback();
                                c.setAutoCommit(true);
                                continue;
                            }
                            if (command.kind() == CommandKind.CHANGE_GOAL
                                    && command.goal() != null) {
                                var selected =
                                        new SkillCatalogRepository()
                                                .findGraph(c, graph)
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalArgumentException(
                                                                        "Unknown exact graph"));
                                if (selected.definitions().stream()
                                        .noneMatch(s -> s.ref().equals(command.goal())))
                                    throw new IllegalArgumentException("Goal not in exact graph");
                            }
                            if (command.kind() == CommandKind.RESUME_CANDIDATE
                                    && catalog.findChallenge(c, command.challenge()).isEmpty())
                                throw new IllegalArgumentException("Unknown exact candidate");
                            var choice = decisions.append(c, command, graph, at, current);
                            c.commit();
                            c.setAutoCommit(true);
                            return choice;
                        } catch (SQLException | RuntimeException e) {
                            c.rollback();
                            throw e;
                        }
                    }
                    throw new IllegalStateException(
                            "Learning state changed during choice; retry same command");
                });
    }

    /**
     * Actual presentation, separate from accept. New starts revalidate current eligibility
     * atomically.
     */
    public ChallengePresentationService.View start(
            String responseCommand, QuestTypes.Familiarity familiarity) {
        Objects.requireNonNull(responseCommand);
        Objects.requireNonNull(familiarity);
        int lockIndex =
                Math.floorMod(
                        Objects.hash(DatabaseConfig.currentDatabaseUrl(), responseCommand),
                        START_LOCKS.length);
        synchronized (START_LOCKS[lockIndex]) {
            return startLocked(responseCommand, familiarity);
        }
    }

    private ChallengePresentationService.View startLocked(
            String responseCommand, QuestTypes.Familiarity familiarity) {
        String presentation =
                connection(
                        c -> {
                            var choice =
                                    decisions
                                            .choice(c, responseCommand)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalArgumentException(
                                                                    "Unknown accepted choice"));
                            var command = choice.command();
                            if (!Set.of(
                                            CommandKind.ACCEPT,
                                            CommandKind.CHOOSE_ALTERNATIVE,
                                            CommandKind.EXPLORE)
                                    .contains(command.kind()))
                                throw new IllegalArgumentException("Not a selected task");
                            var original = decisions.find(c, command.decisionId()).orElseThrow();
                            if (command.challenge() == null)
                                throw new IllegalArgumentException(
                                        "Ordinary curriculum uses existing navigation");
                            var candidate =
                                    original.input().candidates().stream()
                                            .filter(x -> x.challenge().equals(command.challenge()))
                                            .findFirst()
                                            .orElseThrow();
                            String id =
                                    candidate.cohort() == QuestTypes.Cohort.BASELINE
                                            ? BaselineDiagnosticService.presentationId(
                                                    candidate.challenge())
                                            : "adaptive-"
                                                    + CatalogCodec.digest(command.id())
                                                            .substring(0, 48);
                            var request =
                                    new ChallengePresentation.Request(
                                            id,
                                            candidate.quest(),
                                            candidate.challenge(),
                                            candidate.cohort(),
                                            familiarity);
                            // A lost response after presentation commit must be resumable even
                            // though novelty changed.
                            var existing = new ChallengeExposureRepository().find(c, id);
                            if (existing.isPresent()) {
                                var p = existing.get();
                                if (!p.quest().equals(request.quest())
                                        || !p.challenge().equals(request.challenge())
                                        || p.declaration() != familiarity)
                                    throw new IllegalArgumentException(
                                            "Presentation identity conflict");
                                return id;
                            }
                            for (int retry = 0; retry < 3; retry++) {
                                var derived = derive(c, choice.graph(), clock.instant());
                                var concurrentPresentation =
                                        new ChallengeExposureRepository().find(c, id);
                                if (concurrentPresentation.isPresent()) {
                                    var receipt = concurrentPresentation.get();
                                    if (!receipt.quest().equals(request.quest())
                                            || !receipt.challenge().equals(request.challenge())
                                            || receipt.declaration() != familiarity)
                                        throw new IllegalArgumentException(
                                                "Presentation identity conflict");
                                    return id;
                                }
                                requireEligible(command, derived.decision());
                                c.setAutoCommit(false);
                                try {
                                    catalog.lock(c);
                                    if (version(c) != derived.version()) {
                                        c.rollback();
                                        c.setAutoCommit(true);
                                        continue;
                                    }
                                    String receipt = presentations.present(c, request);
                                    c.commit();
                                    c.setAutoCommit(true);
                                    return receipt;
                                } catch (SQLException | RuntimeException e) {
                                    c.rollback();
                                    throw e;
                                }
                            }
                            throw new IllegalStateException(
                                    "Learning state changed before presentation; retry same"
                                            + " command");
                        });
        return presentations.resume(
                presentation); // exposure committed before content leaves the service
    }

    private static Choice sameCommand(Choice existing, LearnerCommand command) {
        if (!existing.command().equals(command))
            throw new IllegalArgumentException("Learner command identity conflict");
        return existing;
    }

    private static void validateOriginal(LearnerCommand command, Decision original) {
        if (original == null) return;
        if (command.kind() == CommandKind.ACCEPT && command.challenge() == null) {
            if (original.result().primary() == null
                    || original.result().primary().action() != Action.CURRICULUM)
                throw new IllegalArgumentException("Accept requires an actual recommendation");
            return;
        }
        var candidate =
                original.input().candidates().stream()
                        .filter(c -> c.challenge().equals(command.challenge()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "Candidate absent from decision"));
        if (command.kind() == CommandKind.ACCEPT
                && (original.result().primary() == null
                        || !candidate.challenge().equals(original.result().primary().challenge())))
            throw new IllegalArgumentException("Accept must select the primary recommendation");
        if (command.kind() == CommandKind.CHOOSE_ALTERNATIVE
                && original.result().alternatives().stream()
                        .noneMatch(
                                s ->
                                        s.challenge().equals(candidate.challenge())
                                                && !s.exploratory()))
            throw new IllegalArgumentException("Not an eligible alternative");
        if (command.kind() == CommandKind.EXPLORE
                && original.result().candidates().stream()
                        .noneMatch(
                                r ->
                                        r.candidateKey().equals(candidate.key())
                                                && r.exploratoryEligible()))
            throw new IllegalArgumentException("Not an eligible exploratory practice candidate");
    }

    private static void requireEligible(LearnerCommand command, Decision current) {
        if (current.input().preferences().paused()
                || current.input().preferences().track() != Track.EVOLUTION)
            throw new IllegalStateException(
                    "Track paused or changed; explicitly resume/select it before starting");
        var candidate =
                current.input().candidates().stream()
                        .filter(c -> c.challenge().equals(command.challenge()))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("Exact candidate unavailable"));
        var ranking =
                current.result().candidates().stream()
                        .filter(r -> r.candidateKey().equals(candidate.key()))
                        .findFirst()
                        .orElseThrow();
        if (command.kind() == CommandKind.EXPLORE
                ? !ranking.exploratoryEligible()
                : !ranking.eligible())
            throw new IllegalStateException(
                    "Candidate no longer eligible; request a current decision");
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T connection(Work<T> work) {
        try (var c = DatabaseConfig.getConnection()) {
            try (var s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("PRAGMA busy_timeout=5000");
            }
            try {
                return work.run(c);
            } catch (SQLException | RuntimeException e) {
                if (!c.getAutoCommit()) c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to use local adaptation state", e);
        }
    }

    private static long version(Connection c) throws SQLException {
        try (var s = c.createStatement();
                var r = s.executeQuery("PRAGMA data_version")) {
            if (!r.next()) throw new SQLException("Missing data_version");
            return r.getLong(1);
        }
    }
}

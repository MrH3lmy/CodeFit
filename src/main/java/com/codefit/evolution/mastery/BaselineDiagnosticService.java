package com.codefit.evolution.mastery;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.QuestTypes.*;
import com.codefit.repository.*;
import com.codefit.service.ChallengePresentationService;

import java.sql.*;
import java.time.*;
import java.util.*;

/** UI-ready baseline orchestration; selection/adaptation and screen rendering remain #215/#216. */
public final class BaselineDiagnosticService {
    public record Diagnostic(
            CatalogRef quest,
            CatalogRef challenge,
            List<BehaviorTarget> targets,
            String measures,
            boolean skipped,
            String presentationId,
            List<String> submissions) {
        public Diagnostic {
            targets = List.copyOf(targets);
            submissions = List.copyOf(submissions);
        }
    }

    public record Status(
            GraphRef graph,
            boolean skipped,
            List<Diagnostic> diagnostics,
            List<EstimateRevisionRepository.Revision> estimates,
            List<NextAction> nextActions) {
        public Status {
            diagnostics = List.copyOf(diagnostics);
            estimates = List.copyOf(estimates);
            nextActions = List.copyOf(nextActions);
        }
    }

    /** Informational eligibility for #215, not a selected quest. */
    public record NextAction(SkillRef skill, String need, List<MasteryEstimate.Reason> reasons) {
        public NextAction {
            reasons = List.copyOf(reasons);
        }
    }

    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private final ChallengePresentationService presentations;
    private final ChallengeEvaluationCoordinator evaluator;
    private final Clock clock;

    public BaselineDiagnosticService() {
        this(
                new ChallengePresentationService(),
                new ChallengeEvaluationCoordinator(),
                Clock.systemUTC());
    }

    public BaselineDiagnosticService(
            ChallengePresentationService presentations,
            ChallengeEvaluationCoordinator evaluator,
            Clock clock) {
        this.presentations = Objects.requireNonNull(presentations);
        this.evaluator = Objects.requireNonNull(evaluator);
        this.clock = Objects.requireNonNull(clock);
    }

    public Status status(Instant asOf) {
        var tasks = catalog.transaction(c -> diagnostics(c, asOf));
        boolean skipped = catalog.transaction(c -> skipped(c, "*", asOf));
        var estimates = new MasteryEstimateService().pilot(asOf);
        var next =
                estimates.stream()
                        .map(
                                r -> {
                                    var e = r.estimate();
                                    String need =
                                            e.contradicted()
                                                    ? "VALIDATE_CONTRADICTION"
                                                    : e.freshness() == MasteryTypes.Freshness.STALE
                                                            ? "FRESH_VALIDATION"
                                                            : e.band() == MasteryTypes.Band.UNKNOWN
                                                                    ? "DIAGNOSTIC_OR_INTRODUCTION"
                                                                    : !e
                                                                                    .currentCapabilitySupported()
                                                                            ? "INDEPENDENT_VALIDATION"
                                                                            : "SUPPORTED_WITHIN_SCOPE";
                                    return new NextAction(e.skill(), need, e.reasons());
                                })
                        .toList();
        return new Status(JavaDebuggingSkillSeed.GRAPH, skipped, tasks, estimates, next);
    }

    public ChallengePresentationService.View present(
            CatalogRef challenge, Familiarity familiarity) {
        var diagnostic =
                catalog.transaction(
                        c -> {
                            if (skipped(c, "*", clock.instant())
                                    || skipped(c, challenge.tag(), clock.instant()))
                                throw new IllegalStateException(
                                        "Diagnostic paused; explicitly resume before presenting");
                            return requireDiagnostic(c, challenge);
                        });
        return presentations.present(
                new ChallengePresentation.Request(
                        presentationId(challenge),
                        diagnostic.quest(),
                        challenge,
                        Cohort.BASELINE,
                        familiarity));
    }

    /**
     * Resume already exposed content without asking the UI to reconstruct its original declaration.
     */
    public ChallengePresentationService.View resumePresentation(CatalogRef challenge) {
        catalog.transaction(
                c -> {
                    requireDiagnostic(c, challenge);
                    return null;
                });
        return presentations.resume(presentationId(challenge));
    }

    /** Uses the exact authorized baseline receipt and existing immutable submission coordinator. */
    public ChallengeEvaluationCoordinator.View submit(
            CatalogRef challenge, SubmissionRequest request) {
        catalog.transaction(
                c -> {
                    requireDiagnostic(c, challenge);
                    if (skipped(c, "*", clock.instant())
                            || skipped(c, challenge.tag(), clock.instant())) {
                        try (var statement =
                                c.prepareStatement(
                                        "SELECT 1 FROM evolution_submissions WHERE command_id=? AND"
                                                + " presentation_id=?")) {
                            statement.setString(1, request.id());
                            statement.setString(2, presentationId(challenge));
                            try (var rows = statement.executeQuery()) {
                                if (!rows.next())
                                    throw new IllegalStateException(
                                            "Diagnostic paused; resume before a new submission");
                            }
                        }
                    }

                    return null;
                });
        if (!request.presentationId().equals(presentationId(challenge)))
            throw new IllegalArgumentException("Submission must use this baseline receipt");
        return evaluator.submit(request);
    }

    public ChallengeEvaluationCoordinator.View resumeSubmission(String command) {
        // Only commands belonging to this baseline may be resumed through this boundary.
        boolean found =
                catalog.transaction(
                        c -> {
                            for (var d : diagnostics(c, clock.instant()))
                                if (d.submissions().contains(command)) return true;
                            return false;
                        });
        if (!found) throw new IllegalArgumentException("Unknown baseline command");
        return evaluator.resume(command);
    }

    public ChallengeEvaluationCoordinator.View submissionStatus(String command) {
        boolean found =
                catalog.transaction(
                        c ->
                                diagnostics(c, clock.instant()).stream()
                                        .anyMatch(d -> d.submissions().contains(command)));
        if (!found) throw new IllegalArgumentException("Unknown baseline command");
        return evaluator.status(command);
    }

    public void skipTask(CatalogRef task, String command) {
        choice(task, command, "SKIP");
    }

    public void resumeTask(CatalogRef task, String command) {
        choice(task, command, "RESUME");
    }

    public void skipAll(String command) {
        choice(null, command, "SKIP");
    }

    public void resumeAll(String command) {
        choice(null, command, "RESUME");
    }

    private void choice(CatalogRef task, String command, String action) {
        ChallengePresentation.identity(command);
        catalog.transaction(
                c -> {
                    if (task != null) requireDiagnostic(c, task);
                    String key = task == null ? "*" : task.tag();
                    try (var s =
                            c.prepareStatement(
                                    "SELECT task,action FROM evolution_baseline_choices WHERE"
                                            + " command_id=?")) {
                        s.setString(1, command);
                        try (var r = s.executeQuery()) {
                            if (r.next()) {
                                if (!key.equals(r.getString(1)) || !action.equals(r.getString(2)))
                                    throw new IllegalArgumentException(
                                            "Baseline choice identity conflict");
                                return null;
                            }
                        }
                    }
                    Instant at = clock.instant();
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " evolution_baseline_choices(command_id,graph_id,graph_version,task,action,chosen_at)"
                                        + " VALUES(?,?,?,?,?,?)")) {
                        s.setString(1, command);
                        s.setString(2, JavaDebuggingSkillSeed.GRAPH.graphId());
                        s.setInt(3, JavaDebuggingSkillSeed.GRAPH.graphVersion());
                        s.setString(4, key);
                        s.setString(5, action);
                        s.setString(6, at.toString());
                        s.executeUpdate();
                    }
                    if (action.equals("SKIP")) {
                        var exposure = new ChallengeExposureRepository();
                        for (var spec : baselineChallenges())
                            if (task == null || spec.ref().equals(task)) {
                                String id = presentationId(spec.ref());
                                if (exposure.find(c, id).isPresent())
                                    exposure.event(
                                            c,
                                            "skip-"
                                                    + CatalogCodec.digest(command + id)
                                                            .substring(0, 40),
                                            id,
                                            ExposureAction.SKIP,
                                            0,
                                            at);
                            }
                    }
                    return null;
                });
    }

    private List<Diagnostic> diagnostics(Connection c, Instant asOf) throws SQLException {
        var result = new ArrayList<Diagnostic>();
        for (var spec : baselineChallenges()) {
            var diagnostic = requireDiagnostic(c, spec.ref());
            String id = presentationId(spec.ref());
            var receipt =
                    new ChallengeExposureRepository()
                            .find(c, id)
                            .filter(p -> !p.presentedAt().isAfter(asOf));
            var commands = new ArrayList<String>();
            long cursor = 0;
            var evaluations = new DurableEvaluationRepository();
            while (true) {
                var page = evaluations.submissionExposurePage(c, cursor, 128);
                for (var submission : page)
                    if (submission.presentationId().equals(id)
                            && !submission.submittedAt().isAfter(asOf))
                        commands.add(submission.command());
                if (page.size() < 128) break;
                cursor = page.getLast().id();
            }
            result.add(
                    new Diagnostic(
                            diagnostic.quest(),
                            spec.ref(),
                            spec.targets(),
                            spec.objective(),
                            skipped(c, spec.ref().tag(), asOf),
                            receipt.map(ChallengePresentation::id).orElse(null),
                            commands));
        }
        return List.copyOf(result);
    }

    private Diagnostic requireDiagnostic(Connection c, CatalogRef challenge) throws SQLException {
        var spec =
                catalog.findChallenge(c, challenge)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown diagnostic"));
        if (spec.cohort() != Cohort.BASELINE || !spec.graph().equals(JavaDebuggingSkillSeed.GRAPH))
            throw new IllegalArgumentException("Not a supported exact baseline task");
        for (var quest : JavaTrainingPack.quests())
            if (quest.cohort() == Cohort.BASELINE && quest.challenges().contains(challenge)) {
                if (catalog.findQuest(c, quest.ref()).filter(quest::equals).isEmpty())
                    throw new IllegalStateException("Exact baseline quest unavailable");
                return new Diagnostic(
                        quest.ref(),
                        challenge,
                        spec.targets(),
                        spec.objective(),
                        false,
                        null,
                        List.of());
            }
        throw new IllegalArgumentException("No supported baseline quest");
    }

    private boolean skipped(Connection c, String task, Instant asOf) throws SQLException {
        boolean skipped = false;
        try (var s =
                c.prepareStatement(
                        "SELECT action,chosen_at FROM evolution_baseline_choices WHERE graph_id=?"
                                + " AND graph_version=? AND task=? ORDER BY sequence")) {
            s.setString(1, JavaDebuggingSkillSeed.GRAPH.graphId());
            s.setInt(2, JavaDebuggingSkillSeed.GRAPH.graphVersion());
            s.setString(3, task);
            try (var r = s.executeQuery()) {
                while (r.next())
                    if (!Instant.parse(r.getString(2)).isAfter(asOf))
                        skipped = r.getString(1).equals("SKIP");
            }
        }
        return skipped;
    }

    private static List<ChallengeSpec> baselineChallenges() {
        return JavaTrainingPack.challenges().stream()
                .filter(
                        s ->
                                s.graph().equals(JavaDebuggingSkillSeed.GRAPH)
                                        && s.cohort() == Cohort.BASELINE)
                .toList();
    }

    public static String presentationId(CatalogRef challenge) {
        return "baseline-"
                + CatalogCodec.digest(JavaDebuggingSkillSeed.GRAPH + "/" + challenge.tag())
                        .substring(0, 40);
    }
}

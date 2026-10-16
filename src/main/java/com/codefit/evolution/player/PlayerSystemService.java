package com.codefit.evolution.player;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.adaptation.*;
import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.mastery.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.*;

import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Local application projection. Call from a worker, never the JavaFX application thread. */
public final class PlayerSystemService {
    public record Overview(
            SkillGraph graph,
            AdaptationDecisionRepository.Decision decision,
            List<EstimateRevisionRepository.Revision> estimates,
            BaselineDiagnosticService.Status baseline,
            List<EvolutionJournal.Entry> recent,
            List<EvolutionQuest> quests,
            ChallengeEvaluationCoordinator.View latest,
            Map<SkillRef, EstimateRevisionRepository.Revision> prior) {}

    public record Work(
            String presentationId,
            CatalogRef challenge,
            String prompt,
            String source,
            String reflection,
            EvaluationTypes.OutsideAssistance assistance,
            boolean java,
            boolean practiceAllowed,
            List<Integer> hints,
            String exposure,
            List<String> publicChecks) {}

    public record Draft(
            String presentationId,
            String source,
            String reflection,
            EvaluationTypes.OutsideAssistance assistance) {
        public Draft {
            // Same bounds and encoding as the deliberate command; no claim that this is submitted.
            new SubmissionRequest(
                    "draft-validation", presentationId, source, assistance, reflection);
        }
    }

    private final Clock clock;
    private final ChallengePresentationService presentations;
    private final AdaptiveQuestService adaptive;
    private final BaselineDiagnosticService baseline;
    private final ChallengeEvaluationCoordinator evaluation;
    private final Supplier<AdaptationDecisionRepository.Decision> recommend;
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private final BundledAssets assets = new BundledAssets();

    public PlayerSystemService() {
        this(Clock.systemUTC());
    }

    public PlayerSystemService(Clock clock) {
        this(
                clock,
                new ChallengePresentationService(
                        new QuestCatalogRepository(), new BundledAssets(), clock),
                new AdaptiveQuestService(
                        clock,
                        new BundledAssets(),
                        () -> new JavaCodeRunner().isAvailable(),
                        new ProblemLibraryService()),
                new ChallengeEvaluationCoordinator(clock),
                null);
    }

    public PlayerSystemService(
            Clock clock,
            ChallengePresentationService presentations,
            AdaptiveQuestService adaptive,
            ChallengeEvaluationCoordinator evaluation,
            Supplier<AdaptationDecisionRepository.Decision> recommend) {
        this.clock = Objects.requireNonNull(clock);
        this.presentations = presentations;
        this.adaptive = adaptive;
        this.evaluation = evaluation;
        this.baseline = new BaselineDiagnosticService(presentations, evaluation, clock);
        this.recommend = recommend == null ? () -> adaptive.recommend(clock.instant()) : recommend;
    }

    public Overview overview() {
        var decision = recommend.get(); // Always current time; history uses stored decisions below.
        var revisions = new EstimateRevisionRepository();
        var estimates =
                decision.input().skills().stream()
                        .map(s -> revisions.find(s.revision()).orElseThrow())
                        .toList();
        var prior = new HashMap<SkillRef, EstimateRevisionRepository.Revision>();
        for (var revision : estimates)
            revisions
                    .previous(revision)
                    .ifPresent(previous -> prior.put(revision.estimate().skill(), previous));
        var recent = new EvolutionJournal().recent();
        var last = recent.stream().filter(e -> e.kind().equals("SUBMISSION")).findFirst();
        var latest = last.map(e -> evaluation.status(e.ref())).orElse(null);
        return new Overview(
                new SkillCatalogRepository().findGraph(JavaDebuggingSkillSeed.GRAPH).orElseThrow(),
                decision,
                estimates,
                baseline.status(clock.instant()),
                recent,
                catalog.quests(),
                latest,
                Map.copyOf(prior));
    }

    public BaselineDiagnosticService baseline() {
        return baseline;
    }

    public AdaptiveQuestService adaptive() {
        return adaptive;
    }

    public ChallengeEvaluationCoordinator evaluation() {
        return evaluation;
    }

    public Instant now() {
        return clock.instant();
    }

    public Work openBaseline(CatalogRef ref, QuestTypes.Familiarity familiarity, boolean resume) {
        return work(resume ? baseline.resumePresentation(ref) : baseline.present(ref, familiarity));
    }

    public Work start(String choice, QuestTypes.Familiarity familiarity) {
        return work(adaptive.start(choice, familiarity));
    }

    public Work resume(String presentation) {
        return work(presentations.resume(presentation));
    }

    private Work work(ChallengePresentationService.View view) {
        // Content is only obtained from the AFTER-COMMIT presentation service.
        var context = presentations.contextForSubmission(view.receipt().id());
        String source = view.draft() == null ? "" : view.draft().getSourceCode();
        if (view.draft() != null) {
            String harness =
                    assets.read(
                            context.challenge().assets().stream()
                                    .filter(a -> a.kind() == QuestTypes.AssetKind.HARNESS)
                                    .findFirst()
                                    .orElseThrow());
            if (source.endsWith(harness))
                source =
                        source.substring(0, source.length() - harness.length()).stripTrailing()
                                + "\n";
        }
        String initial = source;
        return catalog.transaction(
                c -> {
                    String text = initial, reflection = "";
                    var aid = EvaluationTypes.OutsideAssistance.UNKNOWN;
                    try (var s =
                            c.prepareStatement(
                                    "SELECT response,reflection,assistance FROM"
                                            + " evolution_working_notes WHERE presentation_id=?")) {
                        s.setString(1, view.receipt().id());
                        try (var r = s.executeQuery()) {
                            if (r.next()) {
                                if (view.draft() == null) text = r.getString(1);
                                reflection = r.getString(2);
                                aid = EvaluationTypes.OutsideAssistance.valueOf(r.getString(3));
                            }
                        }
                    }
                    var x = view.currentContext();
                    return new Work(
                            view.receipt().id(),
                            view.receipt().challenge(),
                            view.prompt(),
                            text,
                            reflection,
                            aid,
                            view.draft() != null,
                            view.draft() != null
                                    && Set.of(
                                                    QuestTypes.Cohort.BASELINE,
                                                    QuestTypes.Cohort.PRACTICE)
                                            .contains(view.receipt().cohort()),
                            context.challenge().hints().stream().map(h -> h.level()).toList(),
                            "Family: "
                                    + view.receipt().familyId()
                                    + " · "
                                    + view.receipt().cohort()
                                    + " · declaration: "
                                    + view.receipt().declaration()
                                    + " · "
                                    + x.kind()
                                    + " · family presentations: "
                                    + x.familyPresentations()
                                    + " · hint level: "
                                    + x.highestHint()
                                    + " · solution revealed: "
                                    + x.solutionRevealed()
                                    + " · known familiar: "
                                    + x.knownFamiliar(),
                            view.publicChecks().stream()
                                    .map(
                                            check ->
                                                    check.id()
                                                            + ": "
                                                            + check.specification()
                                                            + "\nScope: "
                                                            + check.limitations())
                                    .toList());
                });
    }

    public void save(Draft draft) {
        catalog.transaction(
                c -> {
                    var ctx = presentations.contextForSubmission(c, draft.presentationId());
                    boolean java = ctx.backing().problemId() != null;
                    if (java) {
                        var repository = new JavaSolutionDraftRepository();
                        var old =
                                repository
                                        .findByProblemId(c, ctx.backing().problemId())
                                        .orElseThrow();
                        old.setSourceCode(draft.source());
                        repository.update(c, old);
                    }
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO"
                                        + " evolution_working_notes(presentation_id,response,reflection,assistance,updated_at)"
                                        + " VALUES(?,?,?,?,?) ON CONFLICT(presentation_id) DO"
                                        + " UPDATE SET"
                                        + " response=excluded.response,reflection=excluded.reflection,assistance=excluded.assistance,updated_at=excluded.updated_at")) {
                        s.setString(1, draft.presentationId());
                        s.setString(2, java ? null : draft.source());
                        s.setString(3, draft.reflection());
                        s.setString(4, draft.assistance().name());
                        s.setString(5, clock.instant().toString());
                        s.executeUpdate();
                    }
                    return null;
                });
    }

    public String hint(String presentation, String command, int level) {
        return presentations.revealHint(presentation, command, level);
    }

    public String solution(String presentation, String command) {
        return presentations.revealSolution(presentation, command);
    }

    public void familiar(String presentation, String command) {
        presentations.declareFamiliar(presentation, command);
    }

    public List<ChallengeEvaluationCoordinator.CheckFeedback> practice(
            String presentation, String source, RunCancellationToken token) {
        return practice(presentation, "practice-" + UUID.randomUUID(), source, token);
    }

    public List<ChallengeEvaluationCoordinator.CheckFeedback> practice(
            String presentation, String command, String source, RunCancellationToken token) {
        if (!presentations.beginPractice(presentation, command))
            return List.of(
                    new ChallengeEvaluationCoordinator.CheckFeedback(
                            "practice-replay",
                            EvaluationTypes.CheckStatus.NOT_RUN,
                            "This practice command is already recorded. Start a new practice run to"
                                    + " execute again; no evidence was recorded."));
        var context = presentations.contextForSubmission(presentation);
        return new JavaChallengeEvaluator().practice(context.challenge(), source, token);
    }

    /** Redacted committed view first; deriving overview is a separate, retryable operation. */
    public ChallengeEvaluationCoordinator.View submit(SubmissionRequest request) {
        return submit(request, () -> false);
    }

    public ChallengeEvaluationCoordinator.View submit(
            SubmissionRequest request, BooleanSupplier cancellation) {
        var ctx = presentations.contextForSubmission(request.presentationId());
        if (ctx.receipt().cohort() == QuestTypes.Cohort.BASELINE)
            baseline.prepare(ctx.receipt().challenge(), request);
        else evaluation.prepare(request);
        if (cancellation.getAsBoolean()) evaluation.cancel(request.id());
        return evaluation.resume(request.id());
    }

    public com.codefit.evolution.evidence.StoredEvidence evidence(String id) {
        return catalog.transaction(c -> new EvidenceRepository().find(c, id).orElseThrow());
    }

    /** Safe immutable identity for every durable stage; does not authorize or reveal content. */
    public DurableEvaluationRepository.SubmissionExposure submissionMetadata(String command) {
        SubmissionRequest.identity(command);
        return catalog.transaction(
                c ->
                        new DurableEvaluationRepository()
                                .submissionMetadata(c, command)
                                .orElseThrow());
    }

    public Optional<DurableEvaluationRepository.EvaluationInput> resultContext(String command) {
        return new DurableEvaluationRepository().evaluationInput(command);
    }
}

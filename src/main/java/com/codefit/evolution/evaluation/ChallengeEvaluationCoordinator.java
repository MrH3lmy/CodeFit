package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.DurableEvaluationRepository.*;
import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.evidence.EvidenceTypes.*;

import com.codefit.evolution.evidence.*;
import com.codefit.evolution.progression.ProgressionService;
import com.codefit.evolution.quest.*;
import com.codefit.model.*;
import com.codefit.repository.*;
import com.codefit.service.*;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evolution-only deliberate submission boundary. No JDBC transaction encloses learner execution.
 * PREPARED -> fenced RUNNING -> immutable staged EVALUATED -> atomic source/evidence FINALIZED.
 */
public final class ChallengeEvaluationCoordinator {
    public record CheckFeedback(String id, CheckStatus status, String feedback) {}

    /**
     * Safe to render. Does not expose hidden I/O, harness, answer keys, raw compiler text or
     * source.
     */
    public record View(
            String command,
            Stage stage,
            EvaluationTypes.Outcome outcome,
            boolean withdrawn,
            List<CheckFeedback> checks) {}

    enum Boundary {
        PREPARED,
        CLAIMED,
        EXECUTED,
        STAGED,
        BEFORE_COMMIT,
        AFTER_COMMIT
    }

    interface Faults {
        void at(Boundary boundary);
    }

    private static final Map<String, RunCancellationToken> ACTIVE = new ConcurrentHashMap<>();
    private final QuestCatalogRepository catalog;
    private final ChallengePresentationService presentations;
    private final DurableEvaluationRepository repository = new DurableEvaluationRepository();
    private final BundledAssets assets;
    private final JavaChallengeEvaluator evaluator;
    private final Clock clock;
    private final Faults faults;

    public ChallengeEvaluationCoordinator() {
        this(Clock.systemUTC());
    }

    public ChallengeEvaluationCoordinator(Clock clock) {
        this(
                new QuestCatalogRepository(),
                new BundledAssets(),
                new JavaChallengeEvaluator(new JavaCodeRunner(), new BundledAssets(), clock),
                clock,
                b -> {});
    }

    ChallengeEvaluationCoordinator(
            QuestCatalogRepository catalog,
            BundledAssets assets,
            JavaChallengeEvaluator evaluator,
            Clock clock,
            Faults faults) {
        this.catalog = catalog;
        this.assets = assets;
        this.evaluator = evaluator;
        this.clock = clock;
        this.faults = faults;
        this.presentations = new ChallengePresentationService(catalog, assets, clock);
    }

    public View submit(SubmissionRequest request) {
        prepare(request);
        return resume(request.id());
    }

    public View prepare(SubmissionRequest request) {
        View view =
                catalog.transaction(
                        c -> {
                            var existing = repository.find(c, request.id());
                            if (existing.isPresent()) {
                                if (!existing.get().snapshot().request().equals(request))
                                    throw new IllegalArgumentException(
                                            "Command ID already binds a different payload");
                                return view(c, existing.get());
                            }
                            var context =
                                    presentations.contextForSubmission(c, request.presentationId());
                            var spec = context.challenge();
                            assets.validate(spec);
                            var quest =
                                    catalog.findQuest(c, context.receipt().quest()).orElseThrow();
                            if (!quest.challenges().contains(spec.ref()))
                                throw new IllegalArgumentException(
                                        "Presentation quest does not contain challenge");
                            int family = 0, variant = 0;
                            try (var s =
                                    c.prepareStatement(
                                            "SELECT variant_id,COUNT(*) AS n FROM"
                                                + " evolution_submissions WHERE family_id=? GROUP"
                                                + " BY variant_id")) {
                                s.setString(1, spec.familyId());
                                try (var r = s.executeQuery()) {
                                    while (r.next()) {
                                        family += r.getInt("n");
                                        if (r.getString(1).equals(spec.variantId()))
                                            variant = r.getInt("n");
                                    }
                                }
                            }
                            ProblemSolvingSession session =
                                    context.backing().problemId() == null
                                            ? null
                                            : new ProblemSolvingSessionRepository()
                                                    .findByProblemId(
                                                            c, context.backing().problemId())
                                                    .orElse(null);
                            var frozen =
                                    new SubmissionSnapshot(
                                            request,
                                            context.receipt(),
                                            CatalogCodec.encode(quest),
                                            CatalogCodec.encode(spec),
                                            context.backing().problemId(),
                                            context.backing().assessmentItemId(),
                                            context.currentContext(),
                                            family,
                                            variant,
                                            clock.instant(),
                                            JavaChallengeEvaluator.VERSION,
                                            RunnerProfile.defaults(),
                                            JavaChallengeEvaluator.assemble(
                                                    spec, request.artifact(), assets),
                                            session == null
                                                    ? null
                                                    : session.getReadingSecondsElapsed(),
                                            session == null
                                                    ? null
                                                    : session.getThinkingSecondsElapsed(),
                                            session == null
                                                    ? null
                                                    : session.getCodingSecondsElapsed(),
                                            session == null
                                                    ? null
                                                    : session.getDebuggingSecondsElapsed(),
                                            session == null
                                                    ? null
                                                    : session.getHighestHintLevelOpened());
                            new EvidenceMappingRepository().publish(c, mapping(frozen));
                            byte[] bytes = EvaluationCodec.encode(frozen);
                            update(
                                    c,
                                    "INSERT INTO"
                                        + " evolution_submissions(command_id,presentation_id,request_sha,snapshot,snapshot_sha,artifact_sha,executable_sha,family_id,variant_id,state)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,'PREPARED')",
                                    request.id(),
                                    request.presentationId(),
                                    EvaluationCodec.requestDigest(request),
                                    bytes,
                                    CatalogCodec.digest(bytes),
                                    frozen.artifactSha256(),
                                    frozen.executableSha256(),
                                    spec.familyId(),
                                    spec.variantId());
                            return view(c, repository.find(c, request.id()).orElseThrow());
                        });
        faults.at(Boundary.PREPARED);
        return view;
    }

    public View status(String command) {
        SubmissionRequest.identity(command);
        return catalog.transaction(c -> view(c, required(c, command)));
    }

    public View resume(String command) {
        SubmissionRequest.identity(command);
        var fence = new java.util.concurrent.atomic.AtomicReference<String>();
        var token =
                new RunCancellationToken(
                        process ->
                                catalog.transaction(
                                        c -> {
                                            var owner = required(c, command);
                                            if (owner.stage() != Stage.RUNNING
                                                    || !Objects.equals(owner.token(), fence.get()))
                                                throw new IllegalStateException(
                                                        "Process execution fence changed");
                                            update(
                                                    c,
                                                    "UPDATE evolution_submissions SET"
                                                        + " child_pid=?,child_started=? WHERE id=?",
                                                    process.pid(),
                                                    processStart(process),
                                                    owner.id());
                                            if (owner.cancel()) process.destroyForcibly();
                                            return null;
                                        }));
        try (var operation = JavaExecutionCoordinator.tryStart(token)) {
            if (operation == null) return status(command);
            var previous = catalog.transaction(c -> required(c, command));
            if (previous.stage() == Stage.RUNNING && !otherOwnerAlive(previous))
                cleanupChild(previous);
            var exhausted = new java.util.concurrent.atomic.AtomicBoolean();
            var claimed =
                    catalog.transaction(
                            c -> {
                                var s = required(c, command);
                                if (s.stage() == Stage.FINALIZED || s.stage() == Stage.EVALUATED)
                                    return s;
                                if (s.stage() == Stage.RUNNING && otherOwnerAlive(s)) return s;
                                exhausted.set(s.executions() >= 3);
                                String nextFence = UUID.randomUUID().toString();
                                update(
                                        c,
                                        "UPDATE evolution_submissions SET"
                                            + " state='RUNNING',run_token=?,owner_pid=?,owner_started=?,execution_number=?,child_pid=NULL,child_started=NULL"
                                            + " WHERE id=?",
                                        nextFence,
                                        ProcessHandle.current().pid(),
                                        processStart(ProcessHandle.current()),
                                        Math.min(3, s.executions() + 1),
                                        s.id());
                                return required(c, command);
                            });
            if (claimed.stage() == Stage.FINALIZED) return status(command);
            if (claimed.stage() == Stage.EVALUATED) return finalizeSubmission(command);
            if (otherOwnerAlive(claimed)) return status(command);
            fence.set(claimed.token());
            ACTIVE.put(command, token);
            try {
                faults.at(Boundary.CLAIMED);
                if (claimed.cancel()) token.cancel();
                EvaluationResult result;
                if (exhausted.get())
                    result =
                            evaluator.unavailable(
                                    claimed.snapshot(),
                                    EvaluationTypes.Outcome.INCONCLUSIVE,
                                    "Recovery execution budget exhausted",
                                    clock.instant());
                else {
                    // Revalidate pinned installed manifests/assets before executing, never mutable
                    // draft bytes. Already finalized replays do not depend on installed assets.
                    boolean exact;
                    try {
                        exact =
                                catalog.transaction(
                                        c -> {
                                            var spec =
                                                    catalog.findChallenge(
                                                                    c,
                                                                    claimed.snapshot()
                                                                            .presentation()
                                                                            .challenge())
                                                            .orElseThrow();
                                            catalog.verifyBinding(c, spec);
                                            return CatalogCodec.encode(spec)
                                                            .equals(
                                                                    claimed.snapshot()
                                                                            .challengeManifest())
                                                    && CatalogCodec.encode(
                                                                    catalog.findQuest(
                                                                                    c,
                                                                                    claimed.snapshot()
                                                                                            .presentation()
                                                                                            .quest())
                                                                            .orElseThrow())
                                                            .equals(
                                                                    claimed.snapshot()
                                                                            .questManifest());
                                        });
                    } catch (RuntimeException missingContext) {
                        exact = false;
                    }
                    result =
                            exact
                                    ? evaluator.evaluate(claimed.snapshot(), token)
                                    : evaluator.unavailable(
                                            claimed.snapshot(),
                                            EvaluationTypes.Outcome.EVALUATOR_ERROR,
                                            "Exact published context unavailable",
                                            clock.instant());
                }
                faults.at(Boundary.EXECUTED);
                EvaluationResult evaluated = result;
                catalog.transaction(
                        c -> {
                            var current = required(c, command);
                            if (current.stage() != Stage.RUNNING
                                    || !Objects.equals(current.token(), claimed.token()))
                                throw new IllegalStateException("Execution ownership changed");
                            var finalResult =
                                    current.cancel()
                                            ? evaluator.unavailable(
                                                    current.snapshot(),
                                                    EvaluationTypes.Outcome.CANCELLED,
                                                    "Cancellation recorded before staging",
                                                    evaluated.startedAt())
                                            : evaluated;
                            byte[] bytes = EvaluationCodec.encode(finalResult);
                            update(
                                    c,
                                    "INSERT INTO"
                                        + " evolution_evaluation_runs(token,submission_id,execution_number,payload,digest)"
                                        + " VALUES(?,?,?,?,?)",
                                    claimed.token(),
                                    claimed.id(),
                                    claimed.executions(),
                                    bytes,
                                    CatalogCodec.digest(bytes));
                            update(
                                    c,
                                    "UPDATE evolution_submissions SET"
                                            + " state='EVALUATED',staged_run=? WHERE id=?",
                                    claimed.token(),
                                    claimed.id());
                            return null;
                        });
                faults.at(Boundary.STAGED);
                return finalizeSubmission(command);
            } finally {
                ACTIVE.remove(command, token);
            }
        }
    }

    private static void cleanupChild(Submission previous) {
        if (previous.childPid() == null) return;
        var process = ProcessHandle.of(previous.childPid()).filter(ProcessHandle::isAlive);
        if (process.isEmpty()) return;
        String started = processStart(process.get());
        if (previous.childStarted() == null
                || previous.childStarted().equals("unknown")
                || started.equals("unknown"))
            throw new IllegalStateException(
                    "Cannot safely identify orphan process; manual local recovery required");
        if (!previous.childStarted().equals(started)) return; // PID was recycled; do not kill it.
        process.get().descendants().forEach(ProcessHandle::destroyForcibly);
        process.get().destroyForcibly();
        try {
            process.get().onExit().get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Orphan process cleanup has not completed", e);
        }
    }

    private static boolean otherOwnerAlive(Submission s) {
        if (s.ownerPid() == null || s.ownerPid() == ProcessHandle.current().pid()) return false;
        return ProcessHandle.of(s.ownerPid())
                .filter(ProcessHandle::isAlive)
                .filter(
                        p ->
                                s.ownerStarted() == null
                                        || s.ownerStarted().equals("unknown")
                                        || s.ownerStarted().equals(processStart(p)))
                .isPresent();
    }

    private static String processStart(ProcessHandle process) {
        return process.info().startInstant().map(Instant::toString).orElse("unknown");
    }

    public View cancel(String command) {
        SubmissionRequest.identity(command);
        catalog.transaction(
                c -> {
                    var s = required(c, command);
                    if (s.stage() == Stage.PREPARED || s.stage() == Stage.RUNNING)
                        update(
                                c,
                                "UPDATE evolution_submissions SET cancel_requested=1 WHERE id=?",
                                s.id());
                    return null;
                });
        var token = ACTIVE.get(command);
        if (token != null) token.cancel();
        return status(command);
    }

    private View finalizeSubmission(String command) {
        View result =
                catalog.transaction(
                        c -> {
                            var s = required(c, command);
                            if (s.stage() == Stage.FINALIZED) return view(c, s);
                            if (s.stage() != Stage.EVALUATED)
                                throw new IllegalStateException("Evaluation is not staged");
                            var evaluation = repository.staged(c, s);
                            var context = s.snapshot();
                            Long attempt = null, assessment = null;
                            if (context.problemId() != null) {
                                var attempts = new ProblemAttemptRepository();
                                var saved =
                                        attempts.saveLocalEvaluation(
                                                c,
                                                new ProblemAttempt(
                                                        0,
                                                        context.problemId(),
                                                        attempts.nextAttemptNumber(
                                                                c, context.problemId()),
                                                        SubmissionResult.LOCAL_EVALUATION,
                                                        context.readingSeconds(),
                                                        context.thinkingSeconds(),
                                                        context.codingSeconds(),
                                                        context.debuggingSeconds(),
                                                        LocalDateTime.ofInstant(
                                                                context.submittedAt(),
                                                                ZoneOffset.UTC),
                                                        null,
                                                        SessionFinishOutcome.SUBMITTED,
                                                        CompletionOrigin.FRESH_ATTEMPT));
                                attempt = saved.id();
                            } else if (Set.of(
                                            EvaluationTypes.Outcome.PASSED,
                                            EvaluationTypes.Outcome.FAILED,
                                            EvaluationTypes.Outcome.PARTIAL)
                                    .contains(evaluation.outcome())) {
                                // Reuse the reserved assessment bank source, not a fabricated Java
                                // attempt. Other
                                // outcomes have no boolean grade and remain solely durable
                                // unavailable evaluations.
                                String category, module;
                                try (var query =
                                        c.prepareStatement(
                                                "SELECT skill_category,module_name FROM"
                                                        + " assessment_items WHERE id=?")) {
                                    query.setLong(1, context.assessmentItemId());
                                    try (var row = query.executeQuery()) {
                                        if (!row.next())
                                            throw new IllegalStateException(
                                                    "Reserved assessment missing");
                                        category = row.getString(1);
                                        module = row.getString(2);
                                    }
                                }
                                var saved =
                                        new AssessmentAttemptRepository()
                                                .save(
                                                        c,
                                                        new AssessmentAttempt(
                                                                0,
                                                                context.assessmentItemId(),
                                                                0,
                                                                category,
                                                                module,
                                                                evaluation.outcome()
                                                                        == EvaluationTypes.Outcome
                                                                                .PASSED,
                                                                context.request().artifact(),
                                                                null,
                                                                LocalDateTime.ofInstant(
                                                                        context.submittedAt(),
                                                                        ZoneOffset.UTC),
                                                                "evolution:" + command));
                                assessment = saved.id();
                                update(
                                        c,
                                        "INSERT INTO"
                                            + " evolution_evaluation_assessment_aliases(assessment_attempt_id,submission_id)"
                                            + " VALUES(?,?)",
                                        assessment,
                                        s.id());
                            }
                            insertRevision(
                                    c,
                                    s.id(),
                                    1,
                                    "original:" + command,
                                    evaluation,
                                    Change.ORIGINAL,
                                    ChangeReason.ORIGINAL_RECORD,
                                    Actor.SYSTEM,
                                    null);
                            update(
                                    c,
                                    "UPDATE evolution_submissions SET"
                                        + " state='FINALIZED',final_revision=1,problem_attempt_id=?,assessment_attempt_id=?"
                                        + " WHERE id=?",
                                    attempt,
                                    assessment,
                                    s.id());
                            ProgressionService.markOptedOutSubmission(
                                    c,
                                    s.id(),
                                    command,
                                    context.presentation().quest(),
                                    evaluation.outcome());
                            var completed = required(c, command);
                            new EvidenceRepository()
                                    .append(
                                            c,
                                            repository.observation(c, completed, 1),
                                            mapping(context).ref(),
                                            context.presentation().familyId());
                            faults.at(Boundary.BEFORE_COMMIT);
                            return view(c, completed);
                        });
        faults.at(Boundary.AFTER_COMMIT);
        return result;
    }

    /**
     * Conservative explicit invalidation; never regrade an old artifact into a new independent
     * solve.
     */
    public View invalidate(String command, String correctionId, boolean retract) {
        SubmissionRequest.identity(command);
        SubmissionRequest.identity(correctionId);
        return catalog.transaction(
                c -> {
                    var s = required(c, command);
                    if (s.stage() != Stage.FINALIZED)
                        throw new IllegalStateException(
                                "Only finalized submissions may be corrected");
                    try (var q =
                            c.prepareStatement(
                                    "SELECT submission_id,change_kind FROM"
                                            + " evolution_evaluation_revisions WHERE"
                                            + " correction_id=?")) {
                        q.setString(1, correctionId);
                        try (var r = q.executeQuery()) {
                            if (r.next()) {
                                if (r.getLong(1) != s.id()
                                        || !r.getString(2)
                                                .equals(retract ? "RETRACTION" : "SUPERSESSION"))
                                    throw new IllegalArgumentException(
                                            "Conflicting correction command");
                                return view(c, s);
                            }
                        }
                    }
                    var old = repository.revision(c, s, s.revision());
                    if (old.change() == Change.RETRACTION)
                        throw new IllegalStateException(
                                "Retracted submissions cannot be resurrected");
                    int revision = s.revision() + 1;
                    var prior = repository.observation(c, s, s.revision());
                    var result =
                            retract
                                    ? old.result()
                                    : evaluator.unavailable(
                                            s.snapshot(),
                                            EvaluationTypes.Outcome.INCONCLUSIVE,
                                            "Evaluation invalidated: broken or insufficient check",
                                            clock.instant());
                    insertRevision(
                            c,
                            s.id(),
                            revision,
                            correctionId,
                            result,
                            retract ? Change.RETRACTION : Change.SUPERSESSION,
                            retract ? ChangeReason.LEARNER_RETRACTED : ChangeReason.BROKEN_CHECK,
                            retract ? Actor.LEARNER : Actor.SYSTEM,
                            EvidenceIds.id(prior.source()));
                    update(
                            c,
                            "UPDATE evolution_submissions SET final_revision=? WHERE id=?",
                            revision,
                            s.id());
                    var corrected = required(c, command);
                    new EvidenceRepository()
                            .append(
                                    c,
                                    repository.observation(c, corrected, revision),
                                    mapping(s.snapshot()).ref(),
                                    s.snapshot().presentation().familyId());
                    return view(c, corrected);
                });
    }

    private static void insertRevision(
            Connection c,
            long id,
            int revision,
            String correction,
            EvaluationResult result,
            Change change,
            ChangeReason reason,
            Actor actor,
            String previous)
            throws SQLException {
        byte[] bytes = EvaluationCodec.encode(result);
        update(
                c,
                "INSERT INTO"
                    + " evolution_evaluation_revisions(submission_id,revision,correction_id,payload,digest,change_kind,reason,actor,previous_evidence_id)"
                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                id,
                revision,
                correction,
                bytes,
                CatalogCodec.digest(bytes),
                change.name(),
                reason.name(),
                actor.name(),
                previous);
    }

    private Submission required(Connection c, String command) throws SQLException {
        return repository
                .find(c, command)
                .orElseThrow(() -> new IllegalArgumentException("Unknown submission command"));
    }

    private View view(Connection c, Submission s) throws SQLException {
        if (s.stage() != Stage.FINALIZED && s.stage() != Stage.EVALUATED)
            return new View(s.snapshot().request().id(), s.stage(), null, false, List.of());
        var revision =
                s.stage() == Stage.FINALIZED ? repository.revision(c, s, s.revision()) : null;
        var result = revision == null ? repository.staged(c, s) : revision.result();
        boolean withdrawn = revision != null && revision.change() == Change.RETRACTION;
        return new View(
                s.snapshot().request().id(),
                s.stage(),
                withdrawn ? EvaluationTypes.Outcome.INCONCLUSIVE : result.outcome(),
                withdrawn,
                result.checks().stream()
                        .map(
                                check ->
                                        new CheckFeedback(
                                                check.checkId(),
                                                withdrawn && check.status() != CheckStatus.UNGRADED
                                                        ? CheckStatus.INCONCLUSIVE
                                                        : check.status(),
                                                withdrawn
                                                        ? "Evaluation withdrawn; no effective"
                                                                + " completion"
                                                        : publicFeedback(s.snapshot(), check)))
                        .toList());
    }

    private static String publicFeedback(SubmissionSnapshot snapshot, CheckResult check) {
        var definition =
                snapshot.challenge().rubric().checks().stream()
                        .filter(c -> c.id().equals(check.checkId()))
                        .findFirst()
                        .orElseThrow();
        if (definition.kind() == com.codefit.evolution.quest.QuestTypes.CheckKind.CONTROLLED_TRACE
                && Set.of(CheckStatus.PASSED, CheckStatus.FAILED, CheckStatus.UNSUPPORTED)
                        .contains(check.status())) return check.detail();
        if (!check.runs().isEmpty())
            return feedback(check.status())
                    + "; "
                    + check.runs().size()
                    + " bounded run(s), no universal scheduling guarantee";
        return feedback(check.status());
    }

    private static String feedback(CheckStatus status) {
        return switch (status) {
            case PASSED -> "Specified check passed within its documented scope";
            case FAILED -> "Observed behavior did not meet the specified requirement";
            case UNGRADED -> "Reflection is retained without objective grading";
            case NOT_RUN -> "Check did not execute; see the submission outcome";
            case UNSUPPORTED ->
                    "This required mechanism is outside the supported verifier; no verified"
                            + " completion";
            case TIMED_OUT -> "Execution exceeded its bound; this does not prove deadlock";
            default -> "Check unavailable: " + status;
        };
    }
}

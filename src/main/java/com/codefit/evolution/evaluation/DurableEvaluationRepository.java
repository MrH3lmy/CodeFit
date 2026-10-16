package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;
import static com.codefit.evolution.evidence.EvidenceTypes.*;

import com.codefit.evolution.evidence.*;
import com.codefit.evolution.quest.CatalogCodec;
import com.codefit.repository.*;

import java.sql.*;
import java.util.*;

/** Private local durable evaluation source. Scoped reads also work inside evidence finalization. */
public final class DurableEvaluationRepository {
    public record Submission(
            long id,
            SubmissionSnapshot snapshot,
            Stage stage,
            int executions,
            String token,
            Long ownerPid,
            String ownerStarted,
            Long childPid,
            String childStarted,
            boolean cancel,
            String stagedRun,
            Integer revision,
            Long problemAttempt,
            Long assessmentAttempt) {}

    public record Revision(
            int number,
            EvaluationResult result,
            Change change,
            ChangeReason reason,
            Actor actor,
            String previous) {}

    /**
     * Metadata-only input for #212; no source, answer keys, hidden cases or compiler diagnostics.
     */
    public record CheckSummary(
            String id,
            com.codefit.evolution.quest.QuestTypes.CheckKind kind,
            boolean required,
            List<com.codefit.evolution.quest.BehaviorTarget> targets,
            CheckStatus status,
            int runCount,
            String limitations) {
        public CheckSummary {
            targets = List.copyOf(targets);
        }
    }

    public record EvaluationInput(
            EvidenceObservation observation,
            EvidenceMapping mapping,
            com.codefit.evolution.quest.CatalogRef quest,
            com.codefit.evolution.quest.CatalogRef challenge,
            com.codefit.evolution.quest.QuestTypes.Cohort cohort,
            List<CheckSummary> checks) {
        public EvaluationInput {
            checks = List.copyOf(checks);
        }
    }

    public Optional<EvaluationInput> evaluationInput(String command) {
        SubmissionRequest.identity(command);
        return transactions.transaction(c -> evaluationInput(c, command));
    }

    /**
     * Share the evidence reader's snapshot when consuming a page; compare exact evidence revisions.
     */
    public Optional<EvaluationInput> evaluationInput(Connection c, String command)
            throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("Caller read snapshot required");
        var source = find(c, command);
        if (source.isEmpty() || source.get().stage() != Stage.FINALIZED) return Optional.empty();
        var s = source.get();
        var o = observation(c, s, s.revision());
        if (!matches(c, o)
                || new EvidenceRepository()
                        .find(c, EvidenceIds.id(o.source()))
                        .filter(stored -> stored.observation().equals(o))
                        .isEmpty()) return Optional.empty();
        var result = revision(c, s, s.revision()).result();
        var spec = s.snapshot().challenge();
        var checks =
                result.checks().stream()
                        .map(
                                check -> {
                                    var definition =
                                            spec.rubric().checks().stream()
                                                    .filter(d -> d.id().equals(check.checkId()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    return new CheckSummary(
                                            check.checkId(),
                                            definition.kind(),
                                            definition.required(),
                                            definition.targets(),
                                            o.availability() == Availability.RETRACTED
                                                    ? CheckStatus.INCONCLUSIVE
                                                    : check.status(),
                                            check.runs().size(),
                                            definition.limitations());
                                })
                        .toList();
        return Optional.of(
                new EvaluationInput(
                        o,
                        mapping(s.snapshot()),
                        s.snapshot().presentation().quest(),
                        spec.ref(),
                        spec.cohort(),
                        checks));
    }

    private final QuestCatalogRepository transactions = new QuestCatalogRepository();

    public Optional<Submission> find(String command) {
        SubmissionRequest.identity(command);
        return transactions.transaction(c -> find(c, command));
    }

    Optional<Submission> find(Connection c, String command) throws SQLException {
        try (var s = c.prepareStatement("SELECT * FROM evolution_submissions WHERE command_id=?")) {
            s.setString(1, command);
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(read(r)) : Optional.empty();
            }
        }
    }

    private Submission read(ResultSet r) throws SQLException {
        byte[] bytes = r.getBytes("snapshot");
        if (!CatalogCodec.digest(bytes).equals(r.getString("snapshot_sha")))
            throw new IllegalStateException("Corrupt submission snapshot");
        var s = EvaluationCodec.submission(bytes);
        if (!s.request().id().equals(r.getString("command_id"))
                || !EvaluationCodec.requestDigest(s.request()).equals(r.getString("request_sha"))
                || !s.artifactSha256().equals(r.getString("artifact_sha"))
                || !s.executableSha256().equals(r.getString("executable_sha"))
                || !s.presentation().id().equals(r.getString("presentation_id"))
                || !s.presentation().familyId().equals(r.getString("family_id"))
                || !s.presentation().variantId().equals(r.getString("variant_id")))
            throw new IllegalStateException("Corrupt submission identity");
        return new Submission(
                r.getLong("id"),
                s,
                Stage.valueOf(r.getString("state")),
                r.getInt("execution_number"),
                r.getString("run_token"),
                nullableLong(r, "owner_pid"),
                r.getString("owner_started"),
                nullableLong(r, "child_pid"),
                r.getString("child_started"),
                r.getInt("cancel_requested") != 0,
                r.getString("staged_run"),
                (Integer) r.getObject("final_revision"),
                nullableLong(r, "problem_attempt_id"),
                nullableLong(r, "assessment_attempt_id"));
    }

    static Long nullableLong(ResultSet r, String name) throws SQLException {
        long n = r.getLong(name);
        return r.wasNull() ? null : n;
    }

    static int update(Connection c, String sql, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s.executeUpdate();
        }
    }

    public List<String> unfinished(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 64)
            throw new IllegalArgumentException("Bounded recovery page required");
        return transactions.transaction(
                c -> {
                    var out = new ArrayList<String>();
                    try (var s =
                            c.prepareStatement(
                                    "SELECT command_id FROM evolution_submissions WHERE"
                                            + " state<>'FINALIZED' AND id>? ORDER BY id LIMIT ?")) {
                        s.setLong(1, afterId);
                        s.setInt(2, limit);
                        try (var r = s.executeQuery()) {
                            while (r.next()) out.add(r.getString(1));
                        }
                    }
                    return List.copyOf(out);
                });
    }

    Revision revision(Connection c, Submission s, int version) throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT * FROM evolution_evaluation_revisions WHERE submission_id=? AND"
                                + " revision=?")) {
            q.setLong(1, s.id());
            q.setInt(2, version);
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Missing durable evaluation");
                return new Revision(
                        version,
                        decodeResult(r),
                        Change.valueOf(r.getString("change_kind")),
                        ChangeReason.valueOf(r.getString("reason")),
                        Actor.valueOf(r.getString("actor")),
                        r.getString("previous_evidence_id"));
            }
        }
    }

    EvaluationResult staged(Connection c, Submission s) throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT * FROM evolution_evaluation_runs WHERE submission_id=? AND token=?"
                                + " AND execution_number=?")) {
            q.setLong(1, s.id());
            q.setString(2, s.stagedRun());
            q.setInt(3, s.executions());
            try (var r = q.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Missing staged evaluation");
                return decodeResult(r);
            }
        }
    }

    private static EvaluationResult decodeResult(ResultSet r) throws SQLException {
        byte[] b = r.getBytes("payload");
        if (!CatalogCodec.digest(b).equals(r.getString("digest")))
            throw new IllegalStateException("Corrupt evaluation payload");
        return EvaluationCodec.result(b);
    }

    public static EvidenceMapping mapping(SubmissionSnapshot s) {
        return mapping(s.challenge());
    }

    /**
     * Discover exact interpretation identities from the catalog without reading private artifacts.
     */
    public static EvidenceMapping mapping(com.codefit.evolution.quest.ChallengeSpec spec) {
        String digest = CatalogCodec.digest(CatalogCodec.encode(spec));
        return new EvidenceMapping(
                new EvidenceMappingRef("challenge-" + digest.substring(0, 32), 1),
                spec.graph(),
                spec.targets().stream()
                        .map(
                                t ->
                                        new EvidenceMapping.Rule(
                                                SourceType.CHALLENGE_EVALUATION,
                                                spec.familyId(),
                                                t.skill()))
                        .distinct()
                        .toList());
    }

    public EvidenceObservation observation(Connection c, Submission s, int revision)
            throws SQLException {
        Revision rev = revision(c, s, revision);
        var context = s.snapshot();
        var result = rev.result();
        validateResult(context, result);
        var aid = context.exposureAtSubmission();
        int hintLevel =
                Math.max(
                        aid.highestHint(),
                        context.legacyHint() == null ? 0 : context.legacyHint().ordinal() + 1);
        Assistance assistance;
        if (aid.solutionRevealed()
                || context.legacyHint() == com.codefit.model.HintLevel.EXPLANATION)
            assistance = Assistance.EXPLANATION_OR_SOLUTION;
        else if (context.request().outsideAssistance() == OutsideAssistance.DECLARED)
            assistance = Assistance.DECLARED_EXTERNAL;
        else if (hintLevel > 0) assistance = Assistance.HINT;
        else if (context.request().outsideAssistance() == OutsideAssistance.NONE_DECLARED)
            assistance = Assistance.NONE_OBSERVED;
        else assistance = Assistance.UNKNOWN;
        Exposure exposure;
        if (context.priorVariantSubmissions() > 0
                || context.presentation().prior().variantPresentations() > 0)
            exposure = Exposure.REPEAT;
        else if (context.priorFamilySubmissions() > 0
                || context.presentation().prior().familyPresentations() > 0)
            exposure = Exposure.RELATED_VARIANT;
        else if (aid.knownFamiliar()
                || context.presentation().declaration()
                        == com.codefit.evolution.quest.QuestTypes.Familiarity.DECLARED_FAMILIAR)
            exposure = Exposure.FAMILIAR_RECALL;
        else exposure = Exposure.FIRST_PRESENTATION;
        var outcome =
                switch (result.outcome()) {
                    case PASSED -> EvidenceTypes.Outcome.PASS;
                    case FAILED -> EvidenceTypes.Outcome.FAIL;
                    case PARTIAL -> EvidenceTypes.Outcome.PARTIAL;
                    default -> EvidenceTypes.Outcome.UNAVAILABLE;
                };
        var limitations =
                EnumSet.of(
                        Limitation.BOUNDED_RUBRIC_SCOPE,
                        Limitation.FINITE_CONCURRENCY_OBSERVATIONS);
        if (context.request().outsideAssistance() == OutsideAssistance.UNKNOWN)
            limitations.add(Limitation.OUTSIDE_ASSISTANCE_UNKNOWN);
        if (outcome == EvidenceTypes.Outcome.UNAVAILABLE)
            limitations.add(Limitation.INCONCLUSIVE_EXECUTION);
        var o =
                new EvidenceObservation(
                        new EvidenceSourceRef(SourceType.CHALLENGE_EVALUATION, s.id(), 1),
                        new EvidenceTime(
                                context.submittedAt().toString(),
                                context.submittedAt(),
                                TimeBasis.EXACT_UTC),
                        Provenance.LOCAL_CHECK,
                        outcome,
                        assistance,
                        assistance == Assistance.HINT ? hintLevel : null,
                        exposure,
                        limitations,
                        Availability.AVAILABLE,
                        result.outcome().name(),
                        "LOCAL_EVOLUTION",
                        context.presentation().familyId(),
                        new EvaluationReference(
                                context.request().id(),
                                context.presentation().familyId(),
                                context.challenge().rubric().ref().tag(),
                                context.evaluatorVersion(),
                                context.artifactSha256(),
                                result.checks().stream().map(CheckResult::checkId).toList(),
                                s.problemAttempt()),
                        Change.ORIGINAL,
                        null,
                        ChangeReason.ORIGINAL_RECORD,
                        Actor.SYSTEM);
        if (rev.change() == Change.ORIGINAL) return o;
        if (rev.change() == Change.RETRACTION)
            return observation(c, s, revision - 1).retract(rev.reason(), rev.actor());
        return o.revise(revision, rev.previous(), rev.reason(), rev.actor());
    }

    private static void validateResult(SubmissionSnapshot context, EvaluationResult result) {
        if (!result.evaluatorVersion().equals(context.evaluatorVersion())
                || !result.checks().stream()
                        .map(CheckResult::checkId)
                        .sorted()
                        .toList()
                        .equals(
                                context.challenge().rubric().checks().stream()
                                        .map(ch -> ch.id())
                                        .sorted()
                                        .toList()))
            throw new IllegalStateException("Evaluation/context mismatch");
        if (result.outcome() != EvaluationTypes.Outcome.PASSED) return;
        if (!JavaChallengeEvaluator.VERSION.equals(context.evaluatorVersion()))
            throw new IllegalStateException("Unknown successful evaluator");
        for (var check : result.checks()) {
            var definition =
                    context.challenge().rubric().checks().stream()
                            .filter(c -> c.id().equals(check.checkId()))
                            .findFirst()
                            .orElseThrow();
            if (definition.required() && check.status() != CheckStatus.PASSED
                    || !Set.of(CheckStatus.PASSED, CheckStatus.UNGRADED, CheckStatus.UNSUPPORTED)
                            .contains(check.status()))
                throw new IllegalStateException("Invalid successful evaluation");
        }
    }

    /** Full same-connection projection validation, not existence-only authorization. */
    public boolean matches(Connection c, EvidenceObservation observation) throws SQLException {
        try {
            Submission s;
            try (var q =
                    c.prepareStatement("SELECT command_id FROM evolution_submissions WHERE id=?")) {
                q.setLong(1, observation.source().recordId());
                try (var r = q.executeQuery()) {
                    if (!r.next()) return false;
                    s = find(c, r.getString(1)).orElseThrow();
                }
            }
            if (s.stage() != Stage.FINALIZED || s.revision() < observation.source().revision())
                return false;
            if ((s.snapshot().problemId() != null) != (s.problemAttempt() != null)) return false;
            var original = revision(c, s, 1).result();
            boolean keyedGrade =
                    s.snapshot().assessmentItemId() != null
                            && Set.of(
                                            EvaluationTypes.Outcome.PASSED,
                                            EvaluationTypes.Outcome.FAILED,
                                            EvaluationTypes.Outcome.PARTIAL)
                                    .contains(original.outcome());
            if (keyedGrade != (s.assessmentAttempt() != null)) return false;
            if (s.problemAttempt() != null) {
                try (var q =
                        c.prepareStatement(
                                "SELECT 1 FROM problem_attempts a JOIN problems p ON"
                                        + " p.id=a.problem_id WHERE a.id=? AND a.problem_id=? AND"
                                        + " a.submission_result='LOCAL_EVALUATION' AND"
                                        + " p.platform='CODEFIT_EVOLUTION'")) {
                    q.setLong(1, s.problemAttempt());
                    q.setLong(2, s.snapshot().problemId());
                    try (var r = q.executeQuery()) {
                        if (!r.next()) return false;
                    }
                }
            }
            if (s.assessmentAttempt() != null) {
                try (var q =
                        c.prepareStatement(
                                "SELECT 1 FROM evolution_evaluation_assessment_aliases x JOIN"
                                    + " assessment_attempts a ON a.id=x.assessment_attempt_id WHERE"
                                    + " x.submission_id=? AND a.id=? AND a.assessment_item_id=? AND"
                                    + " a.correct=? AND a.submitted_answer=? AND a.variant_index=0"
                                    + " AND a.run_id=?")) {
                    q.setLong(1, s.id());
                    q.setLong(2, s.assessmentAttempt());
                    q.setLong(3, s.snapshot().assessmentItemId());
                    q.setInt(4, original.outcome() == EvaluationTypes.Outcome.PASSED ? 1 : 0);
                    q.setString(5, s.snapshot().request().artifact());
                    q.setString(6, "evolution:" + s.snapshot().request().id());
                    try (var r = q.executeQuery()) {
                        if (!r.next()) return false;
                    }
                }
            }
            return observation.equals(observation(c, s, observation.source().revision()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return false;
        }
    }
}

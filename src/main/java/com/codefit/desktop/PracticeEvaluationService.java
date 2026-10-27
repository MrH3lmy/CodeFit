package com.codefit.desktop;

import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.evaluation.EvaluationTypes.*;
import com.codefit.service.JavaExecutionCoordinator;

import java.sql.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/** Finite desktop adapter. No alternative evaluator, artifact store or durable state machine. */
final class PracticeEvaluationService implements AutoCloseable {
    record Result(
            String commandId,
            String presentationId,
            long draftRevision,
            String assistance,
            String challengeId,
            int challengeVersion,
            String submittedAt,
            String artifactSha256,
            boolean active,
            boolean cancellationRequested,
            ChallengeEvaluationCoordinator.View view) {}

    private final PracticeDraftService drafts;
    private final ChallengeEvaluationCoordinator coordinator;
    private final DurableEvaluationRepository repository = new DurableEvaluationRepository();
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        var t = new Thread(r, "practice-evaluation");
                        t.setDaemon(true);
                        return t;
                    });
    private String active;
    private boolean closed;

    PracticeEvaluationService(ExperimentalProfile profile, Clock clock) {
        drafts = new PracticeDraftService(profile, clock);
        coordinator = new ChallengeEvaluationCoordinator(clock);
    }

    synchronized Result prepare(
            String command, String presentation, long revision, String assistance)
            throws SQLException {
        identity(command);
        OutsideAssistance aid = OutsideAssistance.valueOf(assistance);
        if (revision < 0 || revision >= 9007199254740000L)
            throw new IllegalArgumentException("INVALID_INPUT");
        drafts.transaction(
                c -> {
                    var ctx = drafts.authorized(c, presentation);
                    var old = receipt(c, command, presentation);
                    if (old != null) {
                        if (old.revision() != revision || old.assistance() != aid)
                            throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                        required(c, command, presentation);
                        return null;
                    }
                    try (var s = c.createStatement();
                            var r =
                                    s.executeQuery(
                                            "SELECT 1 FROM evolution_submissions WHERE"
                                                    + " state<>'FINALIZED' LIMIT 1")) {
                        if (r.next()) throw new IllegalArgumentException("BUSY");
                    }
                    var draft = drafts.read(c, ctx);
                    if (draft.revision() != revision)
                        throw new IllegalArgumentException("STALE_REVISION");
                    coordinator.prepare(
                            c,
                            new SubmissionRequest(
                                    command,
                                    presentation,
                                    draft.source(),
                                    aid,
                                    draft.reflection()));
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO desktop_submission_commands VALUES(?,?,?,?)")) {
                        s.setString(1, command);
                        s.setString(2, presentation);
                        s.setLong(3, revision);
                        s.setString(4, assistance);
                        s.executeUpdate();
                    }
                    return null;
                });
        return status(command, presentation);
    }

    synchronized Result status(String command, String presentation) throws SQLException {
        identity(command);
        return drafts.transaction(
                c -> {
                    drafts.authorized(c, presentation);
                    var receipt = receipt(c, command, presentation);
                    if (receipt == null) {
                        // A colliding non-desktop command must never masquerade as an absent
                        // intent.
                        try (var q =
                                c.prepareStatement(
                                        "SELECT 1 FROM evolution_submissions WHERE command_id=?")) {
                            q.setString(1, command);
                            try (var r = q.executeQuery()) {
                                if (r.next()) throw new IllegalArgumentException("NOT_AUTHORIZED");
                            }
                        }
                        return null;
                    }
                    var s = required(c, command, presentation);
                    return new Result(
                            command,
                            presentation,
                            receipt.revision(),
                            receipt.assistance().name(),
                            s.snapshot().challenge().ref().id(),
                            s.snapshot().challenge().ref().version(),
                            s.snapshot().submittedAt().toString(),
                            s.snapshot().artifactSha256(),
                            command.equals(active) && s.stage() != Stage.FINALIZED,
                            s.cancel(),
                            coordinator.status(c, command));
                });
    }

    synchronized List<Result> recent(String presentation) throws SQLException {
        var ids =
                drafts.transaction(
                        c -> {
                            drafts.authorized(c, presentation);
                            var result = new ArrayList<String>();
                            try (var s =
                                    c.prepareStatement(
                                            "SELECT d.command_id FROM desktop_submission_commands d"
                                                    + " JOIN evolution_submissions e ON"
                                                    + " e.command_id=d.command_id WHERE"
                                                    + " d.presentation_id=? ORDER BY e.id DESC"
                                                    + " LIMIT 32")) {
                                s.setString(1, presentation);
                                try (var r = s.executeQuery()) {
                                    while (r.next()) result.add(r.getString(1));
                                }
                            }
                            return result;
                        });
        var result = new ArrayList<Result>();
        for (var id : ids) result.add(status(id, presentation));
        return List.copyOf(result);
    }

    synchronized Result resume(String command, String presentation) throws SQLException {
        var result = status(command, presentation);
        if (result == null) throw new IllegalArgumentException("NOT_AUTHORIZED");
        if (result.view().stage() == Stage.FINALIZED) return result;
        if (active != null) {
            if (!active.equals(command)) throw new IllegalArgumentException("BUSY");
            return result;
        }
        if (closed) throw new IllegalArgumentException("OPERATION_UNAVAILABLE");
        active = command;
        worker.submit(
                () -> {
                    try {
                        coordinator.resume(command);
                    } catch (RuntimeException ignored) {
                        /* Durable stage remains recoverable; no private diagnostics. */
                    } finally {
                        synchronized (this) {
                            active = null;
                        }
                    }
                });
        return status(command, presentation);
    }

    synchronized Result cancel(String command, String presentation) throws SQLException {
        if (status(command, presentation) == null)
            throw new IllegalArgumentException("NOT_AUTHORIZED");
        coordinator.cancel(command);
        // Explicit cancellation also finalizes a prepared/interrupted cancellation through the same
        // engine.
        return resume(command, presentation);
    }

    private record Receipt(long revision, OutsideAssistance assistance) {}

    private Receipt receipt(Connection c, String command, String presentation) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT presentation_id,draft_revision,assistance FROM"
                                + " desktop_submission_commands WHERE command_id=?")) {
            s.setString(1, command);
            try (var r = s.executeQuery()) {
                if (!r.next()) return null;
                if (!presentation.equals(r.getString(1)))
                    throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                long revision = r.getLong(2);
                if (revision < 0 || revision >= 9007199254740000L)
                    throw new SQLException("PROFILE_INVALID");
                return new Receipt(revision, OutsideAssistance.valueOf(r.getString(3)));
            }
        }
    }

    private DurableEvaluationRepository.Submission required(
            Connection c, String command, String presentation) throws SQLException {
        // Bound private blobs before repository decoding; never allocate corrupted oversized rows.
        try (var q =
                c.prepareStatement(
                        "SELECT id,length(CAST(snapshot AS BLOB)) FROM evolution_submissions WHERE"
                                + " command_id=?")) {
            q.setString(1, command);
            try (var r = q.executeQuery()) {
                if (!r.next() || r.getLong(2) > 2000000) throw new SQLException("PROFILE_INVALID");
                for (String table :
                        List.of("evolution_evaluation_runs", "evolution_evaluation_revisions")) {
                    try (var b =
                            c.prepareStatement(
                                    "SELECT 1 FROM "
                                            + table
                                            + " WHERE submission_id=? AND length(CAST(payload AS"
                                            + " BLOB))>2000000 LIMIT 1")) {
                        b.setLong(1, r.getLong(1));
                        try (var x = b.executeQuery()) {
                            if (x.next()) throw new SQLException("PROFILE_INVALID");
                        }
                    }
                }
            }
        }
        var s = repository.find(c, command).orElseThrow();
        var receipt = receipt(c, command, presentation);
        var context = drafts.authorized(c, presentation);
        if (!s.snapshot().presentation().equals(context.receipt())
                || !s.snapshot().challenge().equals(context.challenge()))
            throw new SQLException("PROFILE_INVALID");
        if (receipt == null
                || !s.snapshot().request().presentationId().equals(presentation)
                || s.snapshot().request().outsideAssistance() != receipt.assistance())
            throw new SQLException("PROFILE_INVALID");
        return s;
    }

    private static void identity(String command) {
        if (!ApplicationProtocol.uuid(command)) throw new IllegalArgumentException("INVALID_INPUT");
    }

    boolean terminated() {
        return worker.isTerminated();
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        JavaExecutionCoordinator.cancelActiveAndAwait(3, TimeUnit.SECONDS);
        worker.shutdownNow();
        try {
            worker.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

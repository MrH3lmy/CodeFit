package com.codefit.desktop;

import com.codefit.evolution.evaluation.EvaluationTypes.OutsideAssistance;
import com.codefit.evolution.player.PlayerSystemService;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;
import com.codefit.service.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Clock;
import java.util.*;

/**
 * Existing drafts/notes remain authoritative. This boundary adds CAS and immutable metadata only.
 */
final class PracticeDraftService {
    record Draft(String presentationId, long revision, String source, String reflection) {}

    record Saved(
            String commandId,
            String presentationId,
            String digest,
            long expectedRevision,
            long revision,
            String outcome) {}

    record Resumed(PracticeApplicationService.Receipt receipt, String instructions, Draft draft) {}

    private final ExperimentalProfile profile;
    private final Clock clock;
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();
    private final ChallengePresentationService presentations = new ChallengePresentationService();
    private final BundledAssets assets = new BundledAssets();

    PracticeDraftService(ExperimentalProfile profile, Clock clock) {
        this.profile = profile;
        this.clock = clock;
    }

    <T> T transaction(QuestCatalogRepository.Work<T> work) throws SQLException {
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                catalog.lock(c);
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    ChallengePresentationService.SubmissionContext authorized(Connection c, String id)
            throws SQLException {
        if (id == null
                || !id.startsWith("desktop-practice-")
                || !ApplicationProtocol.uuid(id.substring(17)))
            throw new IllegalArgumentException("NOT_AUTHORIZED");
        var ctx = presentations.contextForSubmission(c, id);
        var q =
                JavaTrainingPack.quests().stream()
                        .filter(
                                x ->
                                        x.ref().equals(ctx.receipt().quest())
                                                && x.cohort() == QuestTypes.Cohort.PRACTICE
                                                && x.challenges().contains(ctx.challenge().ref()))
                        .findFirst()
                        .orElseThrow();
        if (!catalog.findQuest(c, q.ref()).filter(q::equals).isPresent()
                || JavaTrainingPack.challenges().stream().noneMatch(ctx.challenge()::equals)
                || ctx.challenge().runner() != QuestTypes.Runner.JAVA21_MAIN
                || ctx.backing().problemId() == null)
            throw new IllegalArgumentException("NOT_AUTHORIZED");
        try (var s =
                c.prepareStatement(
                        "SELECT quest_id,quest_version,challenge_id,challenge_version FROM"
                                + " desktop_practice_commands WHERE command_id=?")) {
            s.setString(1, id.substring(17));
            try (var r = s.executeQuery()) {
                if (!r.next()
                        || !q.ref().id().equals(r.getString(1))
                        || q.ref().version() != r.getInt(2)
                        || !ctx.challenge().ref().id().equals(r.getString(3))
                        || ctx.challenge().ref().version() != r.getInt(4))
                    throw new IllegalArgumentException("NOT_AUTHORIZED");
            }
        }
        assets.validate(ctx.challenge());
        if (new ProblemSolvingSessionRepository()
                .findByProblemId(c, ctx.backing().problemId())
                .isEmpty()) throw new IllegalArgumentException("NOT_AUTHORIZED");
        return ctx;
    }

    List<PracticeApplicationService.Receipt> recent() throws SQLException {
        return transaction(
                c -> {
                    var ids = new ArrayList<String>();
                    try (var s = c.createStatement();
                            var r =
                                    s.executeQuery(
                                            "SELECT p.id FROM evolution_challenge_presentations p"
                                                + " JOIN desktop_practice_commands d ON"
                                                + " p.id='desktop-practice-'||d.command_id ORDER BY"
                                                + " p.presented_at DESC,p.id DESC LIMIT 32")) {
                        while (r.next()) ids.add(r.getString(1));
                    }
                    var result = new ArrayList<PracticeApplicationService.Receipt>();
                    for (var id : ids) result.add(receipt(authorized(c, id).receipt()));
                    return List.copyOf(result);
                });
    }

    Resumed resume(String id) throws SQLException {
        return transaction(
                c -> {
                    var ctx = authorized(c, id);
                    String prompt = asset(ctx.challenge(), QuestTypes.AssetKind.PROMPT);
                    if (prompt.length() > 24000)
                        throw new IllegalArgumentException("CONTENT_UNAVAILABLE");
                    return new Resumed(receipt(ctx.receipt()), prompt, read(c, ctx));
                });
    }

    Draft read(String id) throws SQLException {
        return transaction(c -> read(c, authorized(c, id)));
    }

    Draft read(Connection c, ChallengePresentationService.SubmissionContext ctx)
            throws SQLException {
        // Bound private rows BEFORE materialization; the starter includes the published harness.
        try (var s =
                c.prepareStatement(
                        "SELECT length(CAST(source_code AS BLOB)),length(CAST(stdin AS"
                            + " BLOB)),length(CAST(expected_output AS"
                            + " BLOB)),length(CAST(main_class_name AS BLOB)) FROM"
                            + " java_solution_drafts WHERE problem_id=?")) {
            s.setLong(1, ctx.backing().problemId());
            try (var r = s.executeQuery()) {
                if (!r.next()
                        || r.getInt(1) > 100000
                        || r.getInt(2) > 200000
                        || r.getInt(3) > 200000
                        || r.getInt(4) > 128)
                    throw new IllegalArgumentException("CONTENT_UNAVAILABLE");
            }
        }
        var draft =
                new JavaSolutionDraftRepository()
                        .findByProblemId(c, ctx.backing().problemId())
                        .orElseThrow();
        String source = draft.getSourceCode();
        String harness = asset(ctx.challenge(), QuestTypes.AssetKind.HARNESS);
        if (source.endsWith(harness))
            source = source.substring(0, source.length() - harness.length()).stripTrailing() + "\n";
        String reflection = "";
        try (var s =
                c.prepareStatement(
                        "SELECT CASE WHEN length(CAST(reflection AS BLOB))<=24000 THEN reflection"
                            + " ELSE NULL END,assistance FROM evolution_working_notes WHERE"
                            + " presentation_id=?")) {
            s.setString(1, ctx.receipt().id());
            try (var r = s.executeQuery()) {
                if (r.next()) {
                    reflection = r.getString(1);
                    OutsideAssistance.valueOf(r.getString(2));
                }
            }
        }
        validate(source, reflection);
        return new Draft(ctx.receipt().id(), PracticeDraftSchema.revision(c), source, reflection);
    }

    Saved status(String command, String presentation) throws SQLException {
        requireCommand(command);
        return transaction(
                c -> {
                    authorized(c, presentation);
                    return find(c, command, presentation);
                });
    }

    Saved save(String command, String presentation, long expected, String source, String reflection)
            throws SQLException {
        requireCommand(command);
        validate(source, reflection);
        if (expected < 0 || expected >= 9007199254740000L)
            throw new IllegalArgumentException("INVALID_INPUT");
        String digest = digest(presentation, expected, source, reflection);
        return transaction(
                c -> {
                    var ctx = authorized(c, presentation);
                    if (source.contains(asset(ctx.challenge(), QuestTypes.AssetKind.HARNESS)))
                        throw new IllegalArgumentException("INVALID_INPUT");
                    var existing = find(c, command, presentation);
                    if (existing != null) {
                        if (!existing.digest().equals(digest))
                            throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                        return existing;
                    }
                    // Also validate the backing draft/notes before touching them.
                    read(c, ctx);
                    int changed;
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE desktop_draft_clock SET revision=revision+1 WHERE id=1"
                                            + " AND revision=?")) {
                        s.setLong(1, expected);
                        changed = s.executeUpdate();
                    }
                    if (changed == 1) {
                        OutsideAssistance assistance = OutsideAssistance.UNKNOWN;
                        try (var s =
                                c.prepareStatement(
                                        "SELECT assistance FROM evolution_working_notes WHERE"
                                                + " presentation_id=?")) {
                            s.setString(1, presentation);
                            try (var r = s.executeQuery()) {
                                if (r.next())
                                    assistance = OutsideAssistance.valueOf(r.getString(1));
                            }
                        }
                        new PlayerSystemService(clock)
                                .save(
                                        c,
                                        new PlayerSystemService.Draft(
                                                presentation, source, reflection, assistance));
                    }
                    var result =
                            new Saved(
                                    command,
                                    presentation,
                                    digest,
                                    expected,
                                    PracticeDraftSchema.revision(c),
                                    changed == 1 ? "SAVED" : "CONFLICT");
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO desktop_draft_saves VALUES(?,?,?,?,?,?)")) {
                        s.setString(1, command);
                        s.setString(2, presentation);
                        s.setString(3, digest);
                        s.setLong(4, expected);
                        s.setLong(5, result.revision());
                        s.setString(6, result.outcome());
                        s.executeUpdate();
                    }
                    return result;
                });
    }

    private Saved find(Connection c, String command, String presentation) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT presentation_id,digest,expected_revision,revision,outcome FROM"
                                + " desktop_draft_saves WHERE command_id=?")) {
            s.setString(1, command);
            try (var r = s.executeQuery()) {
                if (!r.next()) return null;
                if (!presentation.equals(r.getString(1)))
                    throw new IllegalArgumentException("COMMAND_ID_CONFLICT");
                var result =
                        new Saved(
                                command,
                                presentation,
                                r.getString(2),
                                r.getLong(3),
                                r.getLong(4),
                                r.getString(5));
                if (!result.digest().matches("[0-9a-f]{64}")
                        || result.expectedRevision() < 0
                        || result.revision() < 0
                        || !Set.of("SAVED", "CONFLICT").contains(result.outcome())
                        || result.revision() > PracticeDraftSchema.revision(c)
                        || (result.outcome().equals("SAVED")
                                && result.revision() <= result.expectedRevision()))
                    throw new SQLException("PROFILE_INVALID");
                return result;
            }
        }
    }

    static String digest(String presentation, long revision, String source, String reflection) {
        try {
            var d = MessageDigest.getInstance("SHA-256");
            for (String s : List.of(presentation, Long.toString(revision), source, reflection)) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                d.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                d.update(bytes);
            }
            return HexFormat.of().formatHex(d.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void validate(String source, String reflection) {
        if (source == null
                || reflection == null
                || source.length() > 32768
                || reflection.length() > 8000
                || !StandardCharsets.UTF_8.newEncoder().canEncode(source)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(reflection))
            throw new IllegalArgumentException("INVALID_INPUT");
    }

    private static void requireCommand(String command) {
        if (!ApplicationProtocol.uuid(command)) throw new IllegalArgumentException("INVALID_INPUT");
    }

    private String asset(ChallengeSpec spec, QuestTypes.AssetKind kind) {
        return assets.read(
                spec.assets().stream().filter(x -> x.kind() == kind).findFirst().orElseThrow());
    }

    private static PracticeApplicationService.Receipt receipt(ChallengePresentation p) {
        return new PracticeApplicationService.Receipt(
                p.id().substring(17),
                p.id(),
                p.quest().id(),
                p.quest().version(),
                p.challenge().id(),
                p.challenge().version(),
                p.presentedAt().toString());
    }
}

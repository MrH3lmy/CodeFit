package com.codefit.repository;

import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.quest.*;
import com.codefit.model.*;
import com.codefit.service.AcceptedAnswerCodec;
import com.codefit.service.ProblemService;

import java.sql.*;
import java.util.*;

/**
 * Publishes one atomic pack. Exact versions are insert-only, including rubrics and family cohorts.
 */
public final class QuestCatalogRepository {
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    public <T> T transaction(Work<T> work) {
        try (var c = DatabaseConfig.getConnection()) {
            try (var s = c.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON");
            }
            c.setAutoCommit(false);
            try {
                lock(c);
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    e.addSuppressed(rollback);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Local quest transaction failed", e);
        }
    }

    public void publish(List<EvolutionQuest> quests, List<ChallengeSpec> challenges) {
        transaction(
                c -> {
                    publish(c, quests, challenges);
                    return null;
                });
    }

    /** Caller owns commit/rollback and must enable foreign keys before its transaction. */
    public void publish(Connection c, List<EvolutionQuest> quests, List<ChallengeSpec> challenges)
            throws SQLException {
        lock(c);
        if (quests.isEmpty()
                || challenges.isEmpty()
                || quests.size() > 128
                || challenges.size() > 128)
            throw new IllegalArgumentException("Bounded nonempty pack required");
        if (quests.stream().map(EvolutionQuest::ref).distinct().count() != quests.size()
                || challenges.stream().map(ChallengeSpec::ref).distinct().count()
                        != challenges.size())
            throw new IllegalArgumentException("Duplicate catalog identity");
        var skills = new SkillCatalogRepository();
        var assets = new BundledAssets();
        for (var x : challenges) {
            CatalogValidation.targets(
                    skills.findGraph(c, x.graph())
                            .orElseThrow(() -> new IllegalArgumentException("Missing exact graph")),
                    x.domainId(),
                    x.targets(),
                    x.prerequisites());
            assets.validate(x);
        }
        for (var q : quests) {
            CatalogValidation.targets(
                    skills.findGraph(c, q.graph())
                            .orElseThrow(() -> new IllegalArgumentException("Missing exact graph")),
                    q.domainId(),
                    q.targets(),
                    q.prerequisites());
            List<ChallengeSpec> resolved = new ArrayList<>();
            for (var ref : q.challenges())
                resolved.add(
                        challenges.stream()
                                .filter(x -> x.ref().equals(ref))
                                .findFirst()
                                .orElseGet(() -> findChallengeUnchecked(c, ref)));
            CatalogValidation.quest(q, resolved);
        }
        for (var x : challenges) {
            try (var s =
                    c.prepareStatement(
                            "INSERT OR IGNORE INTO evolution_challenge_families(id,cohort)"
                                    + " VALUES(?,?)")) {
                s.setString(1, x.familyId());
                s.setString(2, x.cohort().name());
                s.executeUpdate();
            }
            try (var s =
                    c.prepareStatement(
                            "SELECT cohort FROM evolution_challenge_families WHERE id=?")) {
                s.setString(1, x.familyId());
                try (var rs = s.executeQuery()) {
                    if (!rs.next() || !rs.getString(1).equals(x.cohort().name()))
                        throw new IllegalArgumentException("Family cannot change cohort");
                }
            }
            try (var s =
                    c.prepareStatement(
                            "SELECT payload FROM evolution_challenge_definitions WHERE id=?")) {
                s.setString(1, x.ref().id());
                try (var rs = s.executeQuery()) {
                    while (rs.next()) {
                        var prior = CatalogCodec.challenge(rs.getString(1));
                        if (!prior.familyId().equals(x.familyId())
                                || !prior.variantId().equals(x.variantId())
                                || prior.cohort() != x.cohort()
                                || !prior.domainId().equals(x.domainId()))
                            throw new IllegalArgumentException(
                                    "Challenge identity cannot change family, variant, cohort or"
                                            + " domain across versions");
                    }
                }
            }
            String rubric = CatalogCodec.encode(x.rubric());
            immutable(c, "evolution_challenge_rubrics", x.rubric().ref(), rubric);
            String payload = CatalogCodec.encode(x);
            String old = payload(c, "evolution_challenge_definitions", x.ref()).orElse(null);
            if (old != null) {
                if (!old.equals(payload))
                    throw new IllegalArgumentException("Published challenge version conflict");
                verifyBinding(c, x);
                continue;
            }
            // A variant is globally stable within its family, even when task IDs are changed.
            try (var s =
                    c.prepareStatement(
                            "SELECT id FROM evolution_challenge_definitions WHERE family=? AND"
                                    + " variant=?")) {
                s.setString(1, x.familyId());
                s.setString(2, x.variantId());
                try (var rs = s.executeQuery()) {
                    while (rs.next())
                        if (!rs.getString(1).equals(x.ref().id()))
                            throw new IllegalArgumentException(
                                    "Variant identity reused by another challenge");
                }
            }
            Long problem = null, assessment = null;
            if (x.runner() == Runner.JAVA21_MAIN)
                problem = new ProblemService().bindEvolutionProblem(c, x.ref()).getId();
            else {
                String prompt = assets.read(x.asset("prompt")),
                        answer = assets.read(x.asset("answer-key")).strip();
                String category = "Evolution " + bindingKey(x.ref());
                try (var lookup =
                        c.prepareStatement(
                                "SELECT id FROM assessment_items WHERE skill_category=?")) {
                    lookup.setString(1, category);
                    try (var rs = lookup.executeQuery()) {
                        if (rs.next()) {
                            assessment = rs.getLong(1);
                            if (rs.next())
                                throw new IllegalArgumentException("Ambiguous diagnostic identity");
                        }
                    }
                }
                if (assessment == null)
                    assessment =
                            new AssessmentItemRepository()
                                    .save(
                                            c,
                                            category,
                                            "Evolution Java diagnostics",
                                            CardType.RECALL,
                                            ValidationMode.EXACT,
                                            List.of(
                                                    new AssessmentItemRepository.VariantInput(
                                                            prompt,
                                                            AcceptedAnswerCodec.encode(
                                                                    List.of(answer)),
                                                            answer,
                                                            null,
                                                            null)))
                                    .getId();
            }
            try (var s =
                    c.prepareStatement(
                            "INSERT INTO"
                                + " evolution_challenge_definitions(id,version,family,variant,cohort,payload,digest,problem_id,assessment_item_id)"
                                + " VALUES(?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, x.ref().id());
                s.setInt(2, x.ref().version());
                s.setString(3, x.familyId());
                s.setString(4, x.variantId());
                s.setString(5, x.cohort().name());
                s.setString(6, payload);
                s.setString(7, CatalogCodec.digest(payload));
                if (problem == null) s.setNull(8, Types.INTEGER);
                else s.setLong(8, problem);
                if (assessment == null) s.setNull(9, Types.INTEGER);
                else s.setLong(9, assessment);
                s.executeUpdate();
            }
            if (assessment != null) reserveAssessment(c, assessment, x.ref());
            verifyBinding(c, x);
        }
        for (var q : quests) {
            immutable(c, "evolution_quest_definitions", q.ref(), CatalogCodec.encode(q));
            for (var ref : q.challenges())
                try (var s =
                        c.prepareStatement(
                                "INSERT OR IGNORE INTO evolution_quest_candidates"
                                        + " VALUES(?,?,?,?)")) {
                    s.setString(1, q.ref().id());
                    s.setInt(2, q.ref().version());
                    s.setString(3, ref.id());
                    s.setInt(4, ref.version());
                    s.executeUpdate();
                }
        }
    }

    public Optional<ChallengeSpec> findChallenge(CatalogRef ref) {
        return transaction(c -> findChallenge(c, ref));
    }

    public Optional<ChallengeSpec> findChallenge(Connection c, CatalogRef ref) throws SQLException {
        return payload(c, "evolution_challenge_definitions", ref).map(CatalogCodec::challenge);
    }

    private ChallengeSpec findChallengeUnchecked(Connection c, CatalogRef ref) {
        try {
            return findChallenge(c, ref)
                    .orElseThrow(() -> new IllegalArgumentException("Unresolved challenge"));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<EvolutionQuest> findQuest(CatalogRef ref) {
        return transaction(c -> findQuest(c, ref));
    }

    public Optional<EvolutionQuest> findQuest(Connection c, CatalogRef ref) throws SQLException {
        return payload(c, "evolution_quest_definitions", ref).map(CatalogCodec::quest);
    }

    public List<EvolutionQuest> quests() {
        return transaction(
                c -> {
                    List<EvolutionQuest> result = new ArrayList<>();
                    try (var s =
                                    c.prepareStatement(
                                            "SELECT id,version FROM evolution_quest_definitions"
                                                    + " ORDER BY id,version");
                            var rs = s.executeQuery()) {
                        while (rs.next())
                            result.add(
                                    findQuest(c, new CatalogRef(rs.getString(1), rs.getInt(2)))
                                            .orElseThrow());
                    }
                    return List.copyOf(result);
                });
    }

    public record Backing(Long problemId, Long assessmentItemId) {}

    public Backing backing(Connection c, CatalogRef ref) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT problem_id,assessment_item_id FROM evolution_challenge_definitions"
                                + " WHERE id=? AND version=?")) {
            s.setString(1, ref.id());
            s.setInt(2, ref.version());
            try (var rs = s.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown challenge");
                long p = rs.getLong(1);
                Long problem = rs.wasNull() ? null : p;
                long a = rs.getLong(2);
                return new Backing(problem, rs.wasNull() ? null : a);
            }
        }
    }

    public long problemId(Connection c, CatalogRef ref) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT problem_id FROM evolution_challenge_definitions WHERE id=? AND"
                                + " version=?")) {
            s.setString(1, ref.id());
            s.setInt(2, ref.version());
            try (var rs = s.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Unknown challenge");
                long id = rs.getLong(1);
                if (rs.wasNull())
                    throw new IllegalArgumentException(
                            "Nonexecutable challenge uses an assessment item");
                return id;
            }
        }
    }

    public void reserveAssessment(Connection c, long itemId, CatalogRef challenge)
            throws SQLException {
        lock(c);
        if (findChallenge(c, challenge).isEmpty())
            throw new IllegalArgumentException("Unknown challenge");
        try (var s =
                c.prepareStatement(
                        "SELECT challenge_id,challenge_version FROM"
                                + " evolution_assessment_reservations WHERE item_id=?")) {
            s.setLong(1, itemId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    if (!rs.getString(1).equals(challenge.id())
                            || rs.getInt(2) != challenge.version())
                        throw new IllegalArgumentException("Reservation conflict");
                    return;
                }
            }
        }
        try (var s =
                c.prepareStatement("INSERT INTO evolution_assessment_reservations VALUES(?,?,?)")) {
            s.setLong(1, itemId);
            s.setString(2, challenge.id());
            s.setInt(3, challenge.version());
            s.executeUpdate();
        }
    }

    public void verifyBinding(Connection c, ChallengeSpec x) throws SQLException {
        if (x.runner() == Runner.KEYED_RESPONSE) {
            var binding = backing(c, x.ref());
            var item =
                    new AssessmentItemRepository()
                            .findCatalogItem(c, binding.assessmentItemId())
                            .orElseThrow();
            var v = item.getVariants().getFirst();
            var assets = new BundledAssets();
            if (item.getVariants().size() != 1
                    || v.variantIndex() != 0
                    || item.getCardType() != CardType.RECALL
                    || item.getValidationMode() != ValidationMode.EXACT
                    || !v.acceptedAnswers()
                            .equals(
                                    AcceptedAnswerCodec.encode(
                                            List.of(assets.read(x.asset("answer-key")).strip())))
                    || v.hint() != null
                    || v.simulatedOutput() != null
                    || !v.scenario().equals(assets.read(x.asset("prompt")))
                    || !v.referenceAnswer().equals(assets.read(x.asset("answer-key")).strip()))
                throw new IllegalStateException("Reserved assessment content conflict");
            return;
        }
        var problem =
                new ProblemRepository()
                        .findById(c, problemId(c, x.ref()))
                        .orElseThrow(() -> new IllegalStateException("Missing bound problem"));
        if (!problem.getPlatform().equals("CODEFIT_EVOLUTION")
                || !problem.getExternalCode().equals(bindingKey(x.ref())))
            throw new IllegalStateException("Corrupt problem binding");
    }

    public static String bindingKey(CatalogRef ref) {
        return ref.id() + "@" + ref.version();
    }

    private Optional<String> payload(Connection c, String table, CatalogRef ref)
            throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT payload,digest FROM " + table + " WHERE id=? AND version=?")) {
            s.setString(1, ref.id());
            s.setInt(2, ref.version());
            try (var rs = s.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String text = rs.getString(1);
                if (!CatalogCodec.digest(text).equals(rs.getString(2)))
                    throw new IllegalStateException("Catalog digest corruption");
                return Optional.of(text);
            }
        }
    }

    private void immutable(Connection c, String table, CatalogRef ref, String text)
            throws SQLException {
        var old = payload(c, table, ref);
        if (old.isPresent()) {
            if (!old.get().equals(text))
                throw new IllegalArgumentException("Immutable catalog version conflict");
            return;
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO " + table + "(id,version,payload,digest) VALUES(?,?,?,?)")) {
            s.setString(1, ref.id());
            s.setInt(2, ref.version());
            s.setString(3, text);
            s.setString(4, CatalogCodec.digest(text));
            s.executeUpdate();
        }
    }

    /** Acquire the single SQLite writer before reading state used by a mutation. */
    public void lock(Connection c) throws SQLException {
        requireTransaction(c);
        try (var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO evolution_challenge_families(id,cohort) SELECT '', '' WHERE 0");
        }
    }

    private void requireTransaction(Connection c) throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("Caller transaction required");
        try (var s = c.createStatement();
                var rs = s.executeQuery("PRAGMA foreign_keys")) {
            if (!rs.next() || rs.getInt(1) != 1)
                throw new IllegalArgumentException("Foreign keys required");
        }
    }
}

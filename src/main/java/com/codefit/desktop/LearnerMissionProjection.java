package com.codefit.desktop;

import com.codefit.evolution.evaluation.DurableEvaluationRepository;
import com.codefit.evolution.quest.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Frozen facts, not current recommendations, assignments, recovery eligibility or learner
 * capability.
 */
final class LearnerMissionProjection {
    record Quest(String id, int version, String title, String cohort) {}

    record Activity(
            String command,
            String presentation,
            String questId,
            int questVersion,
            String challengeId,
            int challengeVersion,
            Instant submittedAt,
            String stage,
            String outcome,
            Integer revision,
            boolean withdrawn,
            String metadata,
            Instant evaluatedAt) {}

    record Decision(String id, Instant asOf) {}

    record Projection(
            String profileId,
            String sessionId,
            Instant capturedAt,
            String catalogStatus,
            List<Quest> quests,
            String historyStatus,
            List<Activity> activities,
            boolean historyTruncated,
            String decisionStatus,
            List<Decision> decisions,
            boolean decisionsTruncated,
            long reviewCount,
            long dueScheduledCards,
            String reviewDay) {}

    static Projection read(Connection c, String profile, String session, Instant captured)
            throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException("Caller-owned read snapshot required");
        var overview = LearnerReadProjection.read(c, profile, session, captured);
        var quests = new ArrayList<Quest>();
        String catalogStatus = "AVAILABLE";
        try {
            tables(c, "evolution_quest_definitions");
            for (var quest : JavaTrainingPack.quests()) {
                try (var q =
                        c.prepareStatement(
                                "SELECT CASE WHEN length(CAST(payload AS BLOB))<=200000 THEN"
                                    + " payload END, CASE WHEN length(digest)=64 THEN digest END"
                                    + " FROM evolution_quest_definitions WHERE id=? AND"
                                    + " version=?")) {
                    q.setString(1, quest.ref().id());
                    q.setInt(2, quest.ref().version());
                    try (var r = q.executeQuery()) {
                        if (!r.next()) {
                            catalogStatus = "INCOMPLETE";
                            continue;
                        }
                        String canonical = CatalogCodec.encode(quest);
                        if (!canonical.equals(r.getString(1))
                                || !CatalogCodec.digest(canonical).equals(r.getString(2))) {
                            catalogStatus = "INCOMPLETE";
                            continue;
                        }
                        quests.add(
                                new Quest(
                                        quest.ref().id(),
                                        quest.ref().version(),
                                        quest.title(),
                                        quest.cohort().name()));
                    }
                }
            }
        } catch (SQLException | RuntimeException invalid) {
            quests.clear();
            catalogStatus = "UNAVAILABLE";
        }
        List<Activity> activities = new ArrayList<>();
        String historyStatus = "AVAILABLE";
        boolean historyTruncated = false;
        try {
            tables(
                    c,
                    "evolution_submissions",
                    "evolution_evaluation_revisions",
                    "evolution_challenge_presentations");
            var rows = new DurableEvaluationRepository().recentMetadata(c, 21);
            historyTruncated = rows.size() > 20;
            for (var row : rows.stream().limit(20).toList()) {
                if (row.submittedAt().isAfter(captured)
                        || (row.evaluatedAt() != null && row.evaluatedAt().isAfter(captured)))
                    throw new IllegalArgumentException("Future history");
                boolean known =
                        quests.stream()
                                .anyMatch(
                                        q ->
                                                q.id().equals(row.quest().id())
                                                        && q.version() == row.quest().version());
                activities.add(
                        new Activity(
                                row.command(),
                                row.presentation(),
                                row.quest().id(),
                                row.quest().version(),
                                row.challenge().id(),
                                row.challenge().version(),
                                row.submittedAt(),
                                row.stage().name(),
                                row.outcome(),
                                row.revision(),
                                row.withdrawn(),
                                known ? "EXACT_PUBLISHED" : "UNAVAILABLE",
                                row.evaluatedAt()));
            }
        } catch (SQLException | RuntimeException invalid) {
            activities.clear();
            historyStatus = "UNAVAILABLE";
            historyTruncated = false;
        }
        var decisions = new ArrayList<Decision>();
        String decisionStatus = "AVAILABLE";
        boolean decisionsTruncated = false;
        try {
            tables(c, "evolution_adaptation_decisions");
            // Existence/time only: no decision outputs, learner preferences or candidate content
            // decoded.
            try (var q = c.createStatement();
                    var r =
                            q.executeQuery(
                                    "SELECT CASE WHEN length(id)=73 THEN id END, CASE WHEN"
                                        + " length(as_of)<=40 THEN as_of END FROM"
                                        + " evolution_adaptation_decisions ORDER BY sequence DESC"
                                        + " LIMIT 11")) {
                while (r.next()) {
                    if (decisions.size() == 10) {
                        decisionsTruncated = true;
                        break;
                    }
                    String id = r.getString(1);
                    if (id == null || !id.matches("decision-[0-9a-f]{64}"))
                        throw new IllegalArgumentException();
                    Instant at = Instant.parse(r.getString(2));
                    if (at.isAfter(captured)) throw new IllegalArgumentException();
                    decisions.add(new Decision(id, at));
                }
            }
        } catch (SQLException | RuntimeException invalid) {
            decisions.clear();
            decisionStatus = "UNAVAILABLE";
            decisionsTruncated = false;
        }
        return new Projection(
                profile,
                session,
                captured,
                catalogStatus,
                List.copyOf(quests),
                historyStatus,
                List.copyOf(activities),
                historyTruncated,
                decisionStatus,
                List.copyOf(decisions),
                decisionsTruncated,
                overview.reviewCount(),
                overview.dueScheduledCards(),
                overview.reviewDay());
    }

    private static void tables(Connection c, String... names) throws SQLException {
        try (var q =
                c.prepareStatement(
                        "SELECT type, CASE WHEN length(sql)<=65536 THEN sql END FROM sqlite_schema"
                                + " WHERE name=? LIMIT 2")) {
            for (String name : names) {
                q.setString(1, name);
                try (var r = q.executeQuery()) {
                    if (!r.next()
                            || !"table".equals(r.getString(1))
                            || r.getString(2) == null
                            || !r.getString(2)
                                    .stripLeading()
                                    .toUpperCase(Locale.ROOT)
                                    .startsWith("CREATE TABLE")
                            || r.next()) throw new SQLException("METADATA_UNAVAILABLE");
                }
            }
        }
    }
}

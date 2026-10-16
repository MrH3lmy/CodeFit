package com.codefit.repository;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import com.codefit.evolution.evidence.*;

import java.sql.*;
import java.util.*;

/**
 * Bounded, metadata-only source adapters; never selects answers, code, private notes or ratings as
 * objective proof.
 */
public final class LearningEvidenceSourceRepository {
    public record SourceObservation(EvidenceObservation observation, String selector) {}

    public List<SourceObservation> batch(Connection c, SourceType type, long after, int limit)
            throws SQLException {
        if (after < 0 || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Bounded positive batch required");
        if (type == SourceType.CHALLENGE_EVALUATION)
            throw new IllegalArgumentException("No historical challenge evaluator");
        try (var s = c.prepareStatement(query(type) + " WHERE a.id > ? ORDER BY a.id LIMIT ?")) {
            s.setLong(1, after);
            s.setInt(2, limit);
            var list = new ArrayList<SourceObservation>();
            try (var r = s.executeQuery()) {
                while (r.next()) list.add(read(type, r));
            }
            return List.copyOf(list);
        }
    }

    public Optional<SourceObservation> find(Connection c, EvidenceSourceRef ref)
            throws SQLException {
        if (ref.type() == SourceType.CHALLENGE_EVALUATION) return Optional.empty();
        try (var s = c.prepareStatement(query(ref.type()) + " WHERE a.id = ?")) {
            s.setLong(1, ref.recordId());
            try (var r = s.executeQuery()) {
                return r.next() ? Optional.of(read(ref.type(), r)) : Optional.empty();
            }
        }
    }

    /**
     * Rehearsal lookup on an estimator snapshot, restricted to explicitly mapped source contexts.
     */
    public List<SourceObservation> batchForSelectors(
            Connection c, SourceType type, long after, int limit, Set<String> selectors)
            throws SQLException {
        if (c.getAutoCommit() || after < 0 || limit < 1 || limit > 256 || selectors.size() > 128)
            throw new IllegalArgumentException("Bounded caller-owned source snapshot required");
        if (selectors.isEmpty()) return List.of();
        String column =
                switch (type) {
                    case REVIEW -> "f.skill_category";
                    case ASSESSMENT_ATTEMPT -> "a.skill_category";
                    case PROBLEM_ATTEMPT -> "p.topic";
                    default ->
                            throw new IllegalArgumentException(
                                    "Challenge sources belong to evaluator");
                };
        var ordered = selectors.stream().sorted().toList();
        try (var s =
                c.prepareStatement(
                        query(type)
                                + " WHERE a.id>? AND "
                                + column
                                + " IN ("
                                + String.join(",", Collections.nCopies(ordered.size(), "?"))
                                + ") ORDER BY a.id LIMIT ?")) {
            s.setLong(1, after);
            int position = 2;
            for (String selector : ordered) s.setString(position++, selector);
            s.setInt(position, limit);
            var result = new ArrayList<SourceObservation>();
            try (var r = s.executeQuery()) {
                while (r.next()) result.add(read(type, r));
            }
            return List.copyOf(result);
        }
    }

    private static String query(SourceType type) {
        return switch (type) {
            case PROBLEM_ATTEMPT ->
                    "SELECT"
                        + " a.id,a.problem_id,a.attempt_number,a.submission_result,a.completion_origin,a.submitted_at,p.topic"
                        + " AS selector FROM problem_attempts a JOIN problems p ON"
                        + " p.id=a.problem_id";
            case REVIEW ->
                    "SELECT"
                        + " a.id,a.flashcard_id,a.validation_result,a.rating,a.hint_used,a.hint_usage_recorded,a.boss_battle,a.reviewed_at,f.skill_category"
                        + " AS selector FROM review_history a JOIN flashcards f ON"
                        + " f.id=a.flashcard_id";
            case ASSESSMENT_ATTEMPT ->
                    "SELECT"
                        + " a.id,a.assessment_item_id,a.correct,a.attempted_at,f.grade_kind,f.validator,f.submitted_at"
                        + " AS fact_time,f.hint_used AS fact_hint,f.solution_revealed,f.exposure AS"
                        + " fact_exposure,a.skill_category AS selector FROM assessment_attempts a"
                        + " JOIN assessment_items i ON i.id=a.assessment_item_id LEFT JOIN"
                        + " assessment_attempt_facts f ON f.attempt_id=a.id";
            default -> throw new IllegalArgumentException("No historical challenge adapter");
        };
    }

    private static SourceObservation read(SourceType t, ResultSet r) throws SQLException {
        long id = r.getLong("id");
        var o =
                switch (t) {
                    case PROBLEM_ATTEMPT ->
                            LegacyEvidenceClassifier.problem(
                                    id,
                                    r.getLong("problem_id"),
                                    r.getInt("attempt_number"),
                                    r.getString("submission_result"),
                                    r.getString("completion_origin"),
                                    r.getString("submitted_at"));
                    case REVIEW ->
                            LegacyEvidenceClassifier.review(
                                    id,
                                    r.getLong("flashcard_id"),
                                    r.getString("validation_result"),
                                    r.getString("rating"),
                                    r.getInt("hint_used") == 1,
                                    r.getInt("hint_usage_recorded") == 1,
                                    r.getInt("boss_battle") == 1,
                                    r.getString("reviewed_at"));
                    case ASSESSMENT_ATTEMPT ->
                            r.getString("grade_kind") == null
                                    ? LegacyEvidenceClassifier.assessment(
                                            id,
                                            r.getLong("assessment_item_id"),
                                            r.getInt("correct") == 1,
                                            r.getString("attempted_at"))
                                    : LegacyEvidenceClassifier.prospectiveAssessment(
                                            id,
                                            r.getLong("assessment_item_id"),
                                            r.getInt("correct") == 1,
                                            r.getString("fact_time"),
                                            r.getString("grade_kind"),
                                            r.getString("validator"),
                                            r.getInt("fact_hint") != 0,
                                            r.getInt("solution_revealed") != 0,
                                            r.getString("fact_exposure"));
                    default -> throw new IllegalArgumentException();
                };
        String selector = Objects.toString(r.getString("selector"), "");
        return new SourceObservation(o, selector.length() > 128 ? "" : selector);
    }
}

package com.codefit.repository;

import static com.codefit.evolution.quest.QuestTypes.*;

import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.ChallengePresentation.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Caller-owned writer transactions serialize presentation history and idempotency checks. */
public final class ChallengeExposureRepository {
    private final QuestCatalogRepository catalog = new QuestCatalogRepository();

    public Optional<ChallengePresentation> find(Connection c, String id) throws SQLException {
        try (var s =
                c.prepareStatement("SELECT * FROM evolution_challenge_presentations WHERE id=?")) {
            s.setString(1, id);
            try (var rs = s.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                CatalogRef ref =
                        new CatalogRef(
                                rs.getString("challenge_id"), rs.getInt("challenge_version"));
                var spec = catalog.findChallenge(c, ref).orElseThrow();
                return Optional.of(
                        new ChallengePresentation(
                                id,
                                new CatalogRef(
                                        rs.getString("quest_id"), rs.getInt("quest_version")),
                                ref,
                                spec.familyId(),
                                spec.variantId(),
                                spec.cohort(),
                                Instant.parse(rs.getString("presented_at")),
                                Familiarity.valueOf(rs.getString("familiarity")),
                                new ExposureContext(
                                        rs.getInt("prior_family"),
                                        rs.getInt("prior_variant"),
                                        PresentationKind.valueOf(rs.getString("kind")),
                                        rs.getInt("prior_hint"),
                                        rs.getInt("prior_solution") != 0,
                                        rs.getInt("prior_familiar") != 0)));
            }
        }
    }

    public ExposureContext context(Connection c, ChallengeSpec spec) throws SQLException {
        int family = 0, variant = 0, hint = 0;
        boolean solution = false, familiar = false;
        try (var s =
                c.prepareStatement(
                        "SELECT"
                            + " p.id,p.familiarity,d.variant,p.prior_familiar,p.prior_hint,p.prior_solution"
                            + " FROM evolution_challenge_presentations p JOIN"
                            + " evolution_challenge_definitions d ON d.id=p.challenge_id AND"
                            + " d.version=p.challenge_version WHERE d.family=? ORDER BY"
                            + " p.presented_at,p.id")) {
            s.setString(1, spec.familyId());
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    familiar |= rs.getInt(4) != 0;
                    hint = Math.max(hint, rs.getInt(5));
                    solution |= rs.getInt(6) != 0;
                    family++;
                    if (rs.getString(3).equals(spec.variantId())) variant++;
                    familiar |= rs.getString(2).equals("DECLARED_FAMILIAR");
                    try (var events =
                            c.prepareStatement(
                                    "SELECT action,hint_level FROM"
                                            + " evolution_challenge_exposure_events WHERE"
                                            + " presentation_id=?")) {
                        events.setString(1, rs.getString(1));
                        try (var e = events.executeQuery()) {
                            while (e.next()) {
                                hint = Math.max(hint, e.getInt(2));
                                solution |= e.getString(1).equals("SOLUTION");
                                familiar |= e.getString(1).equals("FAMILIARITY_DECLARED");
                            }
                        }
                    }
                }
            }
        }
        // Existing source state may predate this registry; never discard known familiarity.
        try (var s =
                c.prepareStatement(
                        "SELECT d.problem_id,ss.highest_hint_level_opened,EXISTS(SELECT 1 FROM"
                            + " problem_attempts a WHERE a.problem_id=d.problem_id),EXISTS(SELECT 1"
                            + " FROM java_solution_drafts j WHERE j.problem_id=d.problem_id) FROM"
                            + " evolution_challenge_definitions d LEFT JOIN"
                            + " problem_solving_sessions ss ON ss.problem_id=d.problem_id WHERE"
                            + " d.family=?")) {
            s.setString(1, spec.familyId());
            try (var rs = s.executeQuery()) {
                while (rs.next()) {
                    familiar |= rs.getInt(3) != 0 || (family == 0 && rs.getInt(4) != 0);
                    String opened = rs.getString(2);
                    if (opened != null) {
                        familiar = true;
                    }
                }
            }
        }
        // A caller may reserve an existing #104 item. Historical attempts are familiarity, not
        // unseen.
        try (var s =
                c.prepareStatement(
                        "SELECT 1 FROM evolution_assessment_reservations r JOIN assessment_attempts"
                                + " a ON a.assessment_item_id=r.item_id JOIN"
                                + " evolution_challenge_definitions d ON d.id=r.challenge_id AND"
                                + " d.version=r.challenge_version WHERE d.family=? LIMIT 1")) {
            s.setString(1, spec.familyId());
            try (var rs = s.executeQuery()) {
                familiar |= rs.next();
            }
        }
        return new ExposureContext(
                family,
                variant,
                variant > 0
                        ? PresentationKind.REPEAT
                        : family > 0
                                ? PresentationKind.RELATED_VARIANT
                                : PresentationKind.FIRST_PRESENTATION,
                hint,
                solution,
                familiar);
    }

    public ChallengePresentation append(Connection c, Request request, Instant now)
            throws SQLException {
        catalog.lock(c);
        var existing = find(c, request.id());
        if (existing.isPresent()) {
            var p = existing.get();
            if (!p.quest().equals(request.quest())
                    || !p.challenge().equals(request.challenge())
                    || p.cohort() != request.cohort()
                    || p.declaration() != request.declaration())
                throw new IllegalArgumentException("Presentation identity conflict");
            return p;
        }
        var spec =
                catalog.findChallenge(c, request.challenge())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown challenge"));
        var quest =
                catalog.findQuest(c, request.quest())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown quest"));
        if (spec.cohort() != request.cohort()
                || quest.cohort() != request.cohort()
                || !quest.challenges().contains(spec.ref()))
            throw new IllegalArgumentException(
                    "Presentation must pin a matching quest, candidate and cohort");
        var prior = context(c, spec);
        if ((!spec.retry().allowed() && prior.variantPresentations() > 0)
                || (spec.retry().maximumPresentations() > 0
                        && prior.variantPresentations() >= spec.retry().maximumPresentations()))
            throw new IllegalStateException("Retry policy exhausted");
        try (var s =
                c.prepareStatement(
                        "INSERT INTO evolution_challenge_presentations"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1, request.id());
            s.setString(2, quest.ref().id());
            s.setInt(3, quest.ref().version());
            s.setString(4, spec.ref().id());
            s.setInt(5, spec.ref().version());
            s.setString(6, now.toString());
            s.setString(7, request.declaration().name());
            s.setInt(8, prior.familyPresentations());
            s.setInt(9, prior.variantPresentations());
            s.setString(10, prior.kind().name());
            s.setInt(11, prior.highestHint());
            s.setInt(12, prior.solutionRevealed() ? 1 : 0);
            s.setInt(13, prior.knownFamiliar() ? 1 : 0);
            // Keep explicit column count in sync with the immutable receipt schema.
            s.executeUpdate();
        }
        return find(c, request.id()).orElseThrow();
    }

    public void event(
            Connection c,
            String eventId,
            String presentationId,
            ExposureAction action,
            int level,
            Instant now)
            throws SQLException {
        catalog.lock(c);
        ChallengePresentation.identity(eventId);
        if (action == ExposureAction.HINT ? (level < 1 || level > 3) : level != 0)
            throw new IllegalArgumentException("Invalid aid level");
        if (find(c, presentationId).isEmpty())
            throw new IllegalArgumentException("Unknown presentation");
        try (var s =
                c.prepareStatement(
                        "SELECT presentation_id,action,hint_level FROM"
                                + " evolution_challenge_exposure_events WHERE id=?")) {
            s.setString(1, eventId);
            try (var rs = s.executeQuery()) {
                if (rs.next()) {
                    if (!rs.getString(1).equals(presentationId)
                            || !rs.getString(2).equals(action.name())
                            || rs.getInt(3) != level)
                        throw new IllegalArgumentException("Exposure event identity conflict");
                    return;
                }
            }
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO evolution_challenge_exposure_events VALUES(?,?,?,?,?)")) {
            s.setString(1, eventId);
            s.setString(2, presentationId);
            s.setString(3, action.name());
            s.setInt(4, level);
            s.setString(5, now.toString());
            s.executeUpdate();
        }
    }

    public List<ChallengePresentation> history(Connection c, String family) throws SQLException {
        List<ChallengePresentation> result = new ArrayList<>();
        try (var s =
                c.prepareStatement(
                        "SELECT p.id FROM evolution_challenge_presentations p JOIN"
                                + " evolution_challenge_definitions d ON d.id=p.challenge_id AND"
                                + " d.version=p.challenge_version WHERE d.family=? ORDER BY"
                                + " p.presented_at,p.id")) {
            s.setString(1, family);
            try (var rs = s.executeQuery()) {
                while (rs.next()) result.add(find(c, rs.getString(1)).orElseThrow());
            }
        }
        return result.stream()
                .sorted(
                        Comparator.comparing(ChallengePresentation::presentedAt)
                                .thenComparing(ChallengePresentation::id))
                .toList();
    }
}

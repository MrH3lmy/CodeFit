package com.codefit.evolution.player;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.evaluation.DurableEvaluationRepository;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** Read-only, metadata-only projection. No duplicate event store and no source/answer payloads. */
public final class EvolutionJournal {
    public record Entry(Instant at, String kind, String ref, String message) {}

    public record Cursor(
            Instant at,
            String kind,
            String ref,
            long submissionsThrough,
            Map<String, Long> through) {
        public Cursor {
            through = Map.copyOf(through);
        }
    }

    public record Page(List<Entry> entries, Cursor next) {
        public Page {
            entries = List.copyOf(entries);
        }
    }

    private static final Comparator<Entry> ORDER =
            Comparator.comparing(Entry::at)
                    .thenComparing(Entry::kind)
                    .thenComparing(Entry::ref)
                    .reversed();
    private static final String SOURCES =
            """
SELECT presented_at at,'PRESENTATION' kind,id ref,'Challenge presented: '||challenge_id||' / v'||challenge_version message FROM evolution_challenge_presentations
UNION ALL SELECT occurred_at,'EXPOSURE',presentation_id||'/'||id,action||' recorded; event '||id FROM evolution_challenge_exposure_events
UNION ALL SELECT recorded_at,'EVIDENCE',evidence_id,'Evidence revision '||revision||' · '||change_kind FROM evolution_evidence_observations
UNION ALL SELECT as_of,'ESTIMATE',CAST(revision AS TEXT),'Skill estimate: '||skill_id||' · '||policy FROM evolution_estimate_revisions
UNION ALL SELECT as_of,'DECISION',id,'Next action decided · '||policy FROM evolution_adaptation_decisions
UNION ALL SELECT chosen_at,'CHOICE',command_id,'Learner choice saved' FROM evolution_adaptation_choices
UNION ALL SELECT chosen_at,'BASELINE',command_id,'Diagnostic '||action||': '||task FROM evolution_baseline_choices
UNION ALL SELECT earned_at,'REWARD',CAST(sequence AS TEXT),reward_type||' · '||subject_id||' · '||status||' · +'||xp_awarded||' XP · policy v'||policy_version||' · '||reason||' · '||source_ref FROM evolution_reward_entitlements
""";
    // Normalize Instant's optional fractional digits before lexicographic ordering; retain
    // nanoseconds.
    private static final String TIME_KEY =
            "substr(at,1,19)||'.'||substr((CASE WHEN substr(at,20,1)='.' THEN"
                    + " substr(at,21,length(at)-21) ELSE '' END)||'000000000',1,9)||'Z'";

    public Page page(Cursor cursor, int limit) {
        if (limit < 1 || limit > 64)
            throw new IllegalArgumentException("Journal page 1..64 required");
        try (var c = DatabaseConfig.getConnection()) {
            c.setAutoCommit(false);
            long through = cursor == null ? maxSubmission(c) : cursor.submissionsThrough();
            var watermarks = cursor == null ? watermarks(c) : cursor.through();
            var entries = rows(c, cursor, limit + 1, watermarks);
            // Submissions own authentic timestamps inside validated immutable snapshots, not a
            // denormalized journal table. Bounded paging is only done on explicit journal requests.
            long after = 0;
            int count = 0;
            while (true) {
                var page = new DurableEvaluationRepository().submissionExposurePage(c, after, 128);
                if (page.isEmpty()) break;
                for (var submission : page) {
                    after = submission.id();
                    if (after > through) break;
                    if (++count > 10000)
                        throw new IllegalStateException(
                                "Journal submission history exceeds V1 bound; narrow history in a"
                                        + " future version");
                    var e =
                            new Entry(
                                    submission.submittedAt(),
                                    "SUBMISSION",
                                    submission.command(),
                                    "Deliberate submission · " + submission.challenge().tag());
                    if (before(e, cursor)) entries.add(e);
                }
                if (after >= through || page.size() < 128) break;
            }
            c.commit();
            entries.sort(ORDER);
            boolean more = entries.size() > limit;
            var result = List.copyOf(entries.subList(0, Math.min(limit, entries.size())));
            var last = result.isEmpty() ? null : result.getLast();
            return new Page(
                    result,
                    more
                            ? new Cursor(last.at(), last.kind(), last.ref(), through, watermarks)
                            : null);
        } catch (SQLException e) {
            throw new IllegalStateException("Journal temporarily unavailable", e);
        }
    }

    /** Bounded overview recovery links; no full submission/history scan on screen entry. */
    public List<Entry> recent() {
        try (var c = DatabaseConfig.getConnection()) {
            c.setAutoCommit(false);
            var out = new ArrayList<Entry>();
            try (var s = c.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT presented_at,id,challenge_id FROM"
                                        + " evolution_challenge_presentations ORDER BY rowid DESC"
                                        + " LIMIT 8")) {
                while (r.next())
                    out.add(
                            new Entry(
                                    Instant.parse(r.getString(1)),
                                    "PRESENTATION",
                                    r.getString(2),
                                    "Continue " + r.getString(3)));
            }
            try (var s = c.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT chosen_at,command_id FROM evolution_adaptation_choices"
                                        + " WHERE decision_id IS NOT NULL ORDER BY sequence DESC"
                                        + " LIMIT 8")) {
                while (r.next())
                    out.add(
                            new Entry(
                                    Instant.parse(r.getString(1)),
                                    "CHOICE",
                                    r.getString(2),
                                    "Inspect saved choice / continue accepted quest"));
            }
            try (var s = c.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT command_id,state FROM evolution_submissions ORDER BY id"
                                            + " DESC LIMIT 8")) {
                while (r.next())
                    out.add(
                            new Entry(
                                    new DurableEvaluationRepository()
                                            .submissionMetadata(c, r.getString(1))
                                            .orElseThrow()
                                            .submittedAt(),
                                    "SUBMISSION",
                                    r.getString(1),
                                    "Submission " + r.getString(2) + " · inspect / recover"));
            }
            try (var s = c.createStatement();
                    var r =
                            s.executeQuery(
                                    "SELECT earned_at,sequence,reward_type,subject_id,status,"
                                            + "xp_awarded,policy_version,reason,source_ref FROM"
                                            + " evolution_reward_entitlements ORDER BY sequence DESC"
                                            + " LIMIT 8")) {
                while (r.next())
                    out.add(
                            new Entry(
                                    Instant.parse(r.getString(1)),
                                    "REWARD",
                                    r.getString(2),
                                    r.getString(3)
                                            + " · "
                                            + r.getString(4)
                                            + " · "
                                            + r.getString(5)
                                            + " · +"
                                            + r.getInt(6)
                                            + " XP · policy v"
                                            + r.getString(7)
                                            + " · "
                                            + r.getString(8)
                                            + " · "
                                            + r.getString(9)));
            }
            c.commit();
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IllegalStateException("Recovery links unavailable", e);
        }
    }

    private static long maxSubmission(Connection c) throws SQLException {
        try (var s = c.createStatement();
                var r = s.executeQuery("SELECT COALESCE(MAX(id),0) FROM evolution_submissions")) {
            r.next();
            return r.getLong(1);
        }
    }

    private static boolean before(Entry e, Cursor cursor) {
        return cursor == null
                || ORDER.compare(e, new Entry(cursor.at(), cursor.kind(), cursor.ref(), "")) > 0;
    }

    private static Map<String, Long> watermarks(Connection c) throws SQLException {
        var result = new TreeMap<String, Long>();
        for (String table :
                List.of(
                        "evolution_challenge_presentations",
                        "evolution_challenge_exposure_events",
                        "evolution_evidence_observations",
                        "evolution_estimate_revisions",
                        "evolution_adaptation_decisions",
                        "evolution_adaptation_choices",
                        "evolution_baseline_choices",
                        "evolution_reward_entitlements")) {
            try (var s = c.createStatement();
                    var r = s.executeQuery("SELECT COALESCE(MAX(rowid),0) FROM " + table)) {
                r.next();
                result.put(table, r.getLong(1));
            }
        }
        return Map.copyOf(result);
    }

    private static ArrayList<Entry> rows(
            Connection c, Cursor cursor, int limit, Map<String, Long> through) throws SQLException {
        String bounded = SOURCES;
        for (var entry : through.entrySet())
            bounded =
                    bounded.replace(
                            "FROM " + entry.getKey() + "\n",
                            "FROM " + entry.getKey() + " WHERE rowid<=" + entry.getValue() + "\n");
        String sql =
                "WITH source AS ("
                        + bounded
                        + "), ordered AS (SELECT *,"
                        + TIME_KEY
                        + " timekey FROM source) SELECT at,kind,ref,message FROM ordered "
                        + (cursor == null ? "" : "WHERE (timekey,kind,ref)<(?,?,?) ")
                        + "ORDER BY timekey DESC,kind DESC,ref DESC LIMIT ?";
        try (var s = c.prepareStatement(sql)) {
            int i = 1;
            if (cursor != null) {
                s.setString(
                        i++,
                        new DateTimeFormatterBuilder()
                                .appendInstant(9)
                                .toFormatter()
                                .format(cursor.at()));
                s.setString(i++, cursor.kind());
                s.setString(i++, cursor.ref());
            }
            s.setInt(i, limit);
            var out = new ArrayList<Entry>();
            try (var r = s.executeQuery()) {
                while (r.next())
                    out.add(
                            new Entry(
                                    Instant.parse(r.getString(1)),
                                    r.getString(2),
                                    r.getString(3),
                                    r.getString(4)));
            }
            return out;
        }
    }
}

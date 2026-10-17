package com.codefit.evolution.progression;

import static com.codefit.evolution.evaluation.EvaluationTypes.Outcome;
import static com.codefit.evolution.mastery.MasteryTypes.Band;
import static com.codefit.evolution.mastery.MasteryTypes.Freshness;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.evaluation.EvaluationCodec;
import com.codefit.evolution.evaluation.EvaluationTypes;
import com.codefit.evolution.evidence.EvidenceCodec;
import com.codefit.evolution.evidence.EvidenceTypes.Availability;
import com.codefit.evolution.GraphRef;
import com.codefit.evolution.mastery.EstimateRevisionRepository;
import com.codefit.evolution.mastery.MasteryEstimateService;
import com.codefit.evolution.mastery.MasteryEstimate;
import com.codefit.evolution.quest.CatalogCodec;
import com.codefit.evolution.quest.CatalogRef;
import com.codefit.evolution.quest.EvolutionQuest;
import com.codefit.repository.QuestCatalogRepository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Local, auditable engagement rewards. XP is never an input to mastery or recommendation policy. */
public final class ProgressionService {
    private static final int EFFORT_SCAN_LIMIT = 512;
    private static final int EFFORT_SCAN_BUDGET = 4096;
    private static final int OVERVIEW_HISTORY_LIMIT = 25;

    public enum RewardType {
        EFFORT,
        INDEPENDENT_MILESTONE,
        RETENTION_MILESTONE
    }

    public record Entitlement(
            long sequence,
            String key,
            RewardType type,
            String subject,
            int definitionVersion,
            int xpAwarded,
            String status,
            String source,
            String reason,
            boolean proofAvailable,
            String policyVersion,
            String earnedAt) {}

    public record Snapshot(
            int xp,
            int level,
            String engagementRank,
            boolean rewardsEnabled,
            int pendingEffortRewards,
            List<Entitlement> history,
            Set<String> unlockedMilestones) {
        public Snapshot {
            history = List.copyOf(history);
            unlockedMilestones =
                    Collections.unmodifiableSet(new TreeSet<>(unlockedMilestones));
        }
    }

    public record HistoryPage(List<Entitlement> entries, Long nextBeforeSequence) {
        public HistoryPage {
            entries = List.copyOf(entries);
        }
    }

    /** Caller-owned transaction; the entitlement and its legacy balance update commit together. */
    public int awardEffort(
            Connection c,
            EvolutionQuest quest,
            Outcome outcome,
            String submissionId,
            long submissionSequence)
            throws SQLException {
        Objects.requireNonNull(quest);
        if (!eligibleOutcome(outcome) || submissionSequence < 1) return 0;
        String sourceId = requiredSource(submissionId);
        if (!isOriginalCompletedEvaluation(
                c, sourceId, submissionSequence, quest, outcome)) return 0;
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT historical_submission_through FROM"
                                        + " evolution_reward_state WHERE id=1")) {
            if (!r.next()) throw new SQLException("Progression boundary is unavailable");
            if (submissionSequence <= r.getLong(1)) return 0;
        }
        String key = "effort:" + quest.ref().id() + ":v" + quest.ref().version();
        return insertAndAward(
                c,
                key,
                RewardType.EFFORT,
                quest.ref().id(),
                quest.ref().version(),
                "submission:" + sourceId,
                "First attributable completed evaluation for this quest objective",
                ProgressionPolicy.EFFORT_XP);
    }

    private record PendingEffort(
            long submissionId,
            String commandId,
            EvolutionQuest quest,
            Outcome outcome) {}

    private record EffortScan(
            List<PendingEffort> candidates, List<Long> ineligible, long lastScannedId, int rows) {}

    private record EffortBatch(
            List<PendingEffort> candidates, long scannedThrough, boolean scanBudgetReached) {}

    /**
     * Bounded recovery from the immutable finalized submission source. A failed XP transaction
     * leaves no entitlement, so the same source is retried on the next Player System refresh.
     */
    public int reconcilePendingEffortRewards() {
        var batch = pendingEffortBatch(32);
        for (var candidate : batch.candidates()) {
            try {
                runTransaction(
                        c -> {
                            lock(c);
                            awardEffort(
                                    c,
                                    candidate.quest(),
                                    candidate.outcome(),
                                    candidate.commandId(),
                                    candidate.submissionId());
                            markEffortProcessed(c, candidate.submissionId(), "RECONCILED");
                            return null;
                        });
            } catch (RuntimeException retryableFailure) {
                break;
            }
        }
        return pendingEffortCount();
    }

    private EffortBatch pendingEffortBatch(int limit) {
        var result = new ArrayList<PendingEffort>();
        int scanned = 0;
        long afterId = 0;
        while (scanned < EFFORT_SCAN_BUDGET && result.size() < limit) {
            var scan = scanEffortPage(afterId);
            if (scan.rows() == 0) break;
            persistIneligible(scan.ineligible());
            result.addAll(scan.candidates().stream().limit(limit - result.size()).toList());
            scanned += scan.rows();
            afterId = scan.lastScannedId();
        }
        return new EffortBatch(List.copyOf(result), afterId, scanned >= EFFORT_SCAN_BUDGET);
    }

    private int pendingEffortCount() {
        var pending = pendingEffortBatch(128);
        if (pending.candidates().size() == 128) return 128;
        return pending.scanBudgetReached()
                        && hasUnprocessedFinalizedEffort(pending.scannedThrough())
                ? 128
                : pending.candidates().size();
    }

    private EffortScan scanEffortPage(long afterId) {
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                """
                                SELECT s.id,s.command_id,r.payload,p.quest_id,p.quest_version
                                FROM evolution_submissions s
                                JOIN evolution_evaluation_revisions r
                                  ON r.submission_id=s.id AND r.revision=1
                                JOIN evolution_challenge_presentations p ON p.id=s.presentation_id
                                WHERE s.id>? AND s.id>(
                                    SELECT historical_submission_through
                                    FROM evolution_reward_state WHERE id=1)
                                  AND s.state='FINALIZED' AND s.final_revision=1
                                  AND r.change_kind='ORIGINAL'
                                  AND NOT EXISTS (
                                    SELECT 1 FROM evolution_reward_entitlements e
                                    WHERE e.entitlement_key='effort:'||p.quest_id||':v'||p.quest_version)
                                  AND NOT EXISTS (
                                    SELECT 1 FROM evolution_reward_effort_processed x
                                    WHERE x.submission_id=s.id)
                                ORDER BY s.id LIMIT ?
                                """)) {
            s.setLong(1, afterId);
            s.setInt(2, EFFORT_SCAN_LIMIT);
            var candidates = new ArrayList<PendingEffort>();
            var ineligible = new ArrayList<Long>();
            var catalog = new QuestCatalogRepository();
            long lastScannedId = afterId;
            int rows = 0;
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    long id = r.getLong("id");
                    lastScannedId = id;
                    rows++;
                    Outcome outcome;
                    try {
                        outcome = EvaluationCodec.result(r.getBytes("payload")).outcome();
                    } catch (RuntimeException invalid) {
                        ineligible.add(id);
                        continue;
                    }
                    if (!eligibleOutcome(outcome)) {
                        ineligible.add(id);
                        continue;
                    }
                    var quest =
                            catalog.findQuest(
                                    c,
                                    new CatalogRef(
                                            r.getString("quest_id"), r.getInt("quest_version")));
                    if (quest.isEmpty()) {
                        ineligible.add(id);
                        continue;
                    }
                    candidates.add(new PendingEffort(id, r.getString("command_id"), quest.get(), outcome));
                }
            }
            return new EffortScan(
                    List.copyOf(candidates), List.copyOf(ineligible), lastScannedId, rows);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to scan finalized effort rewards", e);
        }
    }

    private void persistIneligible(List<Long> submissionIds) {
        if (submissionIds.isEmpty()) return;
        runTransaction(
                c -> {
                    for (long id : submissionIds) markEffortProcessed(c, id, "INELIGIBLE");
                    return null;
                });
    }

    private static void markEffortProcessed(Connection c, long submissionId, String disposition)
            throws SQLException {
        try (var s =
                c.prepareStatement(
                        "INSERT INTO evolution_reward_effort_processed(submission_id,disposition)"
                                + " VALUES(?,?) ON CONFLICT(submission_id) DO NOTHING")) {
            s.setLong(1, submissionId);
            s.setString(2, disposition);
            s.executeUpdate();
        }
    }

    private static boolean hasUnprocessedFinalizedEffort(long afterId) {
        try (var c = DatabaseConfig.getConnection();
                var s =
                        c.prepareStatement(
                                """
                                SELECT 1
                                FROM evolution_submissions s
                                JOIN evolution_evaluation_revisions r
                                  ON r.submission_id=s.id AND r.revision=1
                                JOIN evolution_challenge_presentations p ON p.id=s.presentation_id
                                WHERE s.id>? AND s.id>(
                                    SELECT historical_submission_through
                                    FROM evolution_reward_state WHERE id=1)
                                  AND s.state='FINALIZED' AND s.final_revision=1
                                  AND r.change_kind='ORIGINAL'
                                  AND NOT EXISTS (
                                    SELECT 1 FROM evolution_reward_entitlements e
                                    WHERE e.entitlement_key='effort:'||p.quest_id||':v'||p.quest_version)
                                  AND NOT EXISTS (
                                    SELECT 1 FROM evolution_reward_effort_processed x
                                    WHERE x.submission_id=s.id)
                                LIMIT 1
                                """)) {
            s.setLong(1, afterId);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to inspect pending effort rewards", e);
        }
    }

    private static boolean isOriginalCompletedEvaluation(
            Connection c,
            String command,
            long id,
            EvolutionQuest quest,
            Outcome expected)
            throws SQLException {
        try (var s =
                        c.prepareStatement(
                                "SELECT s.final_revision,r.change_kind,r.payload,r.digest,"
                                        + "p.quest_id,p.quest_version"
                                        + " FROM evolution_submissions s"
                                        + " JOIN evolution_evaluation_revisions r"
                                        + " ON r.submission_id=s.id AND r.revision=s.final_revision"
                                        + " JOIN evolution_challenge_presentations p"
                                        + " ON p.id=s.presentation_id"
                                        + " WHERE s.command_id=? AND s.id=? AND s.state='FINALIZED'")) {
            s.setString(1, command);
            s.setLong(2, id);
            try (var r = s.executeQuery()) {
                if (!r.next()
                        || r.getInt("final_revision") != 1
                        || !"ORIGINAL".equals(r.getString("change_kind"))
                        || !quest.ref().id().equals(r.getString("quest_id"))
                        || quest.ref().version() != r.getInt("quest_version")) return false;
                byte[] payload = r.getBytes("payload");
                if (!CatalogCodec.digest(payload).equals(r.getString("digest")))
                    throw new SQLException("Finalized evaluation digest mismatch");
                return EvaluationCodec.result(payload).outcome() == expected;
            }
        }
    }

    /**
     * Current-state reconciliation for the Player System only. It takes and publishes its own
     * fenced snapshot after the adaptation decision has committed; historical revisions are never
     * reward inputs.
     */
    public void reconcileCurrentMilestones(GraphRef graph, java.time.Instant asOf) {
        Objects.requireNonNull(graph);
        Objects.requireNonNull(asOf);
        if (!graph.equals(com.codefit.config.JavaDebuggingSkillSeed.GRAPH)) return;
        var mastery = new MasteryEstimateService();
        var estimateRevisions = new EstimateRevisionRepository();
        for (int retry = 0; retry < 3; retry++) {
            try (var c = DatabaseConfig.getConnection()) {
                long before = version(c);
                c.setAutoCommit(false);
                MasteryEstimateService.CapturedPilot captured;
                try {
                    captured = mastery.capturePilot(c, asOf);
                    c.commit();
                } catch (SQLException | RuntimeException failure) {
                    c.rollback();
                    throw failure;
                }
                c.setAutoCommit(true);
                var prepared = mastery.prepare(captured);
                c.setAutoCommit(false);
                try {
                    lock(c);
                    if (version(c) != before) {
                        c.rollback();
                        continue;
                    }
                    for (var item : prepared) {
                        var revision = mastery.publish(c, item);
                        recordEstimate(c, revision, item.estimate());
                    }
                    c.commit();
                    return;
                } catch (SQLException | RuntimeException failure) {
                    c.rollback();
                    throw failure;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to reconcile current mastery milestones", e);
            }
        }
        throw new IllegalStateException("Evidence changed during milestone reconciliation; retry");
    }

    private static void recordEstimate(
            Connection c, EstimateRevisionRepository.Revision revision, MasteryEstimate estimate)
            throws SQLException {
        if (!eligibleMilestone(estimate)) return;
        var persisted = persistedEstimate(c, revision);
        if (persisted.isEmpty() || !eligibleMilestone(persisted.get())) return;
        estimate = persisted.get();
        var skill = estimate.skill();
        var proof = postUpgradeIndependentProof(c, estimate);
        if (proof.isEmpty()) return;
        String source =
                "estimate:"
                        + estimate.graph().graphId()
                        + ":v"
                        + estimate.graph().graphVersion()
                        + ":"
                        + skill.skillId()
                        + ":v"
                        + skill.definitionVersion()
                        + ":revision:"
                        + revision.id()
                        + ":evidence:"
                        + proof.get();
        if (estimate.band().ordinal() >= Band.INDEPENDENT.ordinal()
                && !preexistingMilestone(
                        c, RewardType.INDEPENDENT_MILESTONE, skill.skillId(),
                        skill.definitionVersion()))
            insertAndAward(
                    c,
                    "independent:" + skill.skillId() + ":v" + skill.definitionVersion(),
                    RewardType.INDEPENDENT_MILESTONE,
                    skill.skillId(),
                    skill.definitionVersion(),
                    source,
                    "First current INDEPENDENT support for this skill definition",
                    ProgressionPolicy.INDEPENDENT_MILESTONE_XP);
        if (estimate.band() == Band.RETENTION_SUPPORTED
                && !preexistingMilestone(
                        c, RewardType.RETENTION_MILESTONE, skill.skillId(),
                        skill.definitionVersion()))
            insertAndAward(
                    c,
                    "retention:" + skill.skillId() + ":v" + skill.definitionVersion(),
                    RewardType.RETENTION_MILESTONE,
                    skill.skillId(),
                    skill.definitionVersion(),
                    source,
                    "First current RETENTION_SUPPORTED support for this skill definition",
                    ProgressionPolicy.RETENTION_MILESTONE_XP);
    }

    private static Optional<MasteryEstimate> persistedEstimate(
            Connection c, EstimateRevisionRepository.Revision revision) throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT input_sha,policy,graph_id,graph_version,skill_id,skill_version,inputs"
                                + " FROM evolution_estimate_revisions WHERE revision=?")) {
            s.setLong(1, revision.id());
            try (var r = s.executeQuery()) {
                if (!r.next() || !revision.inputSha().equals(r.getString("input_sha")))
                    return Optional.empty();
                byte[] bytes = r.getBytes("inputs");
                if (!CatalogCodec.digest(bytes).equals(r.getString("input_sha")))
                    throw new SQLException("Estimate revision digest mismatch");
                var input = com.codefit.evolution.mastery.EstimateCodec.decode(bytes);
                if (!input.graph().graphId().equals(r.getString("graph_id"))
                        || input.graph().graphVersion() != r.getInt("graph_version")
                        || !input.skill().ref().skillId().equals(r.getString("skill_id"))
                        || input.skill().ref().definitionVersion() != r.getInt("skill_version")
                        || !revision.estimate().policyVersion().equals(r.getString("policy")))
                    throw new SQLException("Estimate revision identity mismatch");
                return Optional.of(
                        new com.codefit.evolution.mastery.OrdinalMasteryPolicy().estimate(input));
            }
        }
    }

    private static boolean preexistingMilestone(
            Connection c, RewardType type, String skill, int definitionVersion)
            throws SQLException {
        try (var s =
                c.prepareStatement(
                        "SELECT 1 FROM evolution_reward_milestone_baseline"
                                + " WHERE reward_type=? AND subject_id=?"
                                + " AND definition_version=?")) {
            s.setString(1, type.name());
            s.setString(2, skill);
            s.setInt(3, definitionVersion);
            try (var r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    private static long version(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("PRAGMA data_version")) {
            if (!r.next()) throw new SQLException("Missing data_version");
            return r.getLong(1);
        }
    }

    public void setRewardsEnabled(boolean enabled) {
        runTransaction(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE evolution_reward_preferences SET enabled=? WHERE id=1")) {
                        s.setInt(1, enabled ? 1 : 0);
                        if (s.executeUpdate() != 1)
                            throw new SQLException("Reward preference is unavailable");
                    }
                    return null;
                });
    }

    public Snapshot snapshot() {
        try (var c = DatabaseConfig.getConnection()) {
            int xp, level, streakDays, totalReviews, enabled;
            try (var s = c.createStatement();
                    var r =
                            s.executeQuery(
                                    """
                                    SELECT p.xp,p.level,p.streak_days,p.total_reviews,r.enabled
                                    FROM user_progress p CROSS JOIN evolution_reward_preferences r
                                    WHERE p.id=1 AND r.id=1
                                    """)) {
                if (!r.next()) throw new IllegalStateException("Progression state is unavailable");
                xp = r.getInt("xp");
                level = r.getInt("level");
                streakDays = r.getInt("streak_days");
                totalReviews = r.getInt("total_reviews");
                enabled = r.getInt("enabled");
            }
            boolean consistent =
                    streakDays >= ProgressionPolicy.CONSISTENT_STREAK_DAYS
                            || totalReviews >= ProgressionPolicy.EXPERIENCED_REVIEW_COUNT;
            String rank = ProgressionPolicy.engagementRank(level, consistent);
            List<Entitlement> history = history(c, null, OVERVIEW_HISTORY_LIMIT + 1);
            if (history.size() > OVERVIEW_HISTORY_LIMIT)
                history = List.copyOf(history.subList(0, OVERVIEW_HISTORY_LIMIT));
            return new Snapshot(
                    xp,
                    level,
                    rank,
                    enabled == 1,
                    pendingEffortCount(),
                    history,
                    unlockedMilestones(c));
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read local progression", e);
        }
    }

    public HistoryPage historyPage(Long beforeSequence, int limit) {
        if (limit < 1 || limit > 64)
            throw new IllegalArgumentException("Reward history page 1..64 required");
        try (var c = DatabaseConfig.getConnection()) {
            var rows = history(c, beforeSequence, limit + 1);
            boolean more = rows.size() > limit;
            List<Entitlement> entries =
                    more ? List.copyOf(rows.subList(0, limit)) : rows;
            Long next =
                    more && !entries.isEmpty()
                            ? entries.getLast().sequence()
                            : null;
            return new HistoryPage(entries, next);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read reward history", e);
        }
    }

    private List<Entitlement> history(Connection c, Long beforeSequence, int limit)
            throws SQLException {
        List<Entitlement> result = new ArrayList<>();
        try (var s =
                        c.prepareStatement(
                                "SELECT sequence,entitlement_key,reward_type,subject_id,"
                                        + "definition_version,xp_awarded,status,source_ref,"
                                        + "reason,policy_version,earned_at FROM"
                                        + " evolution_reward_entitlements"
                                        + (beforeSequence == null ? "" : " WHERE sequence<?")
                                        + " ORDER BY sequence DESC LIMIT ?")) {
            int parameter = 1;
            if (beforeSequence != null) s.setLong(parameter++, beforeSequence);
            s.setInt(parameter, limit);
            try (var r = s.executeQuery()) {
                while (r.next())
                    result.add(
                            new Entitlement(
                                    r.getLong("sequence"),
                                    r.getString("entitlement_key"),
                                    RewardType.valueOf(r.getString("reward_type")),
                                    r.getString("subject_id"),
                                    r.getInt("definition_version"),
                                    r.getInt("xp_awarded"),
                                    r.getString("status"),
                                    r.getString("source_ref"),
                                    r.getString("reason"),
                                    true,
                                    r.getString("policy_version"),
                                    r.getString("earned_at")));
            }
        }
        return withProofAvailability(c, result);
    }

    private static List<Entitlement> withProofAvailability(
            Connection c, List<Entitlement> entitlements) throws SQLException {
        var evidenceIds = new LinkedHashSet<String>();
        for (var entitlement : entitlements) {
            String evidenceId = evidenceId(entitlement.source());
            if (evidenceId != null) evidenceIds.add(evidenceId);
        }
        var available = new HashSet<String>();
        if (!evidenceIds.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(evidenceIds.size(), "?"));
            try (var s =
                    c.prepareStatement(
                            "SELECT o.evidence_id,o.payload FROM evolution_evidence_sources s"
                                    + " JOIN evolution_evidence_observations o"
                                    + " ON o.evidence_id=s.head_id WHERE o.evidence_id IN ("
                                    + placeholders
                                    + ")")) {
                int index = 1;
                for (String id : evidenceIds) s.setString(index++, id);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        try {
                            if (EvidenceCodec.decode(r.getBytes("payload")).availability()
                                    == Availability.AVAILABLE)
                                available.add(r.getString("evidence_id"));
                        } catch (RuntimeException invalid) {
                            // Invalid or unavailable proof is displayed as unavailable.
                        }
                    }
                }
            }
        }
        return entitlements.stream()
                .map(
                        entitlement -> {
                            String id = evidenceId(entitlement.source());
                            boolean proofAvailable =
                                    id == null || (id.matches("[0-9a-f]{64}") && available.contains(id));
                            return new Entitlement(
                                    entitlement.sequence(),
                                    entitlement.key(),
                                    entitlement.type(),
                                    entitlement.subject(),
                                    entitlement.definitionVersion(),
                                    entitlement.xpAwarded(),
                                    entitlement.status(),
                                    entitlement.source(),
                                    entitlement.reason(),
                                    proofAvailable,
                                    entitlement.policyVersion(),
                                    entitlement.earnedAt());
                        })
                .toList();
    }

    private static String evidenceId(String source) {
        int marker = source.lastIndexOf(":evidence:");
        return marker < 0 ? null : source.substring(marker + ":evidence:".length());
    }

    private static Set<String> unlockedMilestones(Connection c) throws SQLException {
        var unlocked = new TreeSet<String>();
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT DISTINCT reward_type,subject_id"
                                        + " FROM evolution_reward_entitlements"
                                        + " WHERE reward_type IN ('INDEPENDENT_MILESTONE',"
                                        + "'RETENTION_MILESTONE') AND status='AWARDED'"
                                        + " ORDER BY reward_type,subject_id")) {
            while (r.next())
                unlocked.add(r.getString("reward_type") + ":" + r.getString("subject_id"));
        }
        return Collections.unmodifiableSet(unlocked);
    }

    private static int insertAndAward(
            Connection c,
            String key,
            RewardType type,
            String subject,
            int definitionVersion,
            String source,
            String reason,
            int amount)
            throws SQLException {
        int enabled;
        try (var s =
                        c.prepareStatement(
                                "SELECT enabled FROM evolution_reward_preferences WHERE id=1");
                var r = s.executeQuery()) {
            if (!r.next()) throw new SQLException("Reward preference is unavailable");
            enabled = r.getInt(1);
        }
        int awarded = enabled == 1 ? amount : 0;
        try (var s =
                c.prepareStatement(
                        """
                        INSERT INTO evolution_reward_entitlements
                          (entitlement_key,reward_type,subject_id,definition_version,policy_version,
                           xp_awarded,status,source_ref,reason)
                        VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(entitlement_key) DO NOTHING
                        """)) {
            s.setString(1, key);
            s.setString(2, type.name());
            s.setString(3, subject);
            s.setInt(4, definitionVersion);
            s.setString(5, ProgressionPolicy.VERSION);
            s.setInt(6, awarded);
            s.setString(7, enabled == 1 ? "AWARDED" : "OPTED_OUT");
            s.setString(8, source);
            s.setString(9, reason);
            if (s.executeUpdate() == 0) return 0;
        }
        if (awarded > 0) {
            try (var s =
                    c.prepareStatement(
                            "UPDATE user_progress SET xp=xp+?,level=((xp+?)/?)+1"
                                    + " WHERE id=1 AND xp<=?")) {
                s.setInt(1, awarded);
                s.setInt(2, awarded);
                s.setInt(3, ProgressionPolicy.XP_PER_LEVEL);
                s.setInt(4, Integer.MAX_VALUE - awarded);
                if (s.executeUpdate() != 1) throw new SQLException("XP balance cannot be updated");
            }
        }
        return awarded;
    }

    private static Optional<String> postUpgradeIndependentProof(
            Connection c, MasteryEstimate estimate) throws SQLException {
        long boundary;
        try (var s = c.createStatement();
                var r =
                        s.executeQuery(
                                "SELECT historical_evidence_through FROM evolution_reward_state"
                                        + " WHERE id=1")) {
            if (!r.next()) throw new SQLException("Progression boundary is unavailable");
            boundary = r.getLong(1);
        }
        try (var s =
                c.prepareStatement(
                        "SELECT 1 FROM evolution_evidence_observations o"
                                + " JOIN evolution_evidence_sources s ON s.root_id=o.root_id"
                                + " WHERE o.evidence_id=? AND o.sequence>? AND s.head_id=o.evidence_id")) {
            for (var contribution : estimate.contributions()) {
                if (!contribution.independent()) continue;
                s.setString(1, contribution.evidenceId());
                s.setLong(2, boundary);
                try (var r = s.executeQuery()) {
                    if (r.next()) return Optional.of(contribution.evidenceId());
                }
            }
        }
        return Optional.empty();
    }

    private static boolean eligibleMilestone(MasteryEstimate estimate) {
        return !estimate.contradicted()
                && estimate.currentCapabilitySupported()
                && estimate.freshness() == Freshness.CURRENT
                && estimate.band().ordinal() >= Band.INDEPENDENT.ordinal();
    }

    private static boolean eligibleOutcome(Outcome outcome) {
        return outcome == EvaluationTypes.Outcome.PASSED
                || outcome == EvaluationTypes.Outcome.FAILED
                || outcome == EvaluationTypes.Outcome.PARTIAL;
    }

    private static String requiredSource(String source) {
        if (source == null || !source.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
            throw new IllegalArgumentException("Bounded reward source required");
        return source;
    }

    private static void lock(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.executeUpdate(
                    "UPDATE evolution_reward_preferences SET enabled=enabled WHERE id=1");
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        Void run(Connection c) throws SQLException;
    }

    private static void runTransaction(SqlWork work) {
        try (Connection c = DatabaseConfig.getConnection()) {
            c.setAutoCommit(false);
            try {
                work.run(c);
                c.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Progression transaction failed", failure);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Progression transaction failed", e);
        }
    }
}

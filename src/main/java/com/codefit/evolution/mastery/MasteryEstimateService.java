package com.codefit.evolution.mastery;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.DurableEvaluationRepository;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.quest.*;
import com.codefit.evolution.progression.ProgressionService;
import com.codefit.repository.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Explicit on-demand derivation. It does not scan on startup or mutate evidence/peer aggregates;
 * the current Player System path reconciles milestones after recommendation publication.
 */
public final class MasteryEstimateService {
    private final EstimateRevisionRepository revisions = new EstimateRevisionRepository();
    private final Runnable afterRead;

    public MasteryEstimateService() {
        this(() -> {});
    }

    MasteryEstimateService(Runnable afterRead) {
        this.afterRead = Objects.requireNonNull(afterRead);
    }

    public EstimateRevisionRepository.Revision estimate(
            GraphRef graph, SkillRef skill, List<EvidenceMappingRef> mappings, Instant asOf) {
        Objects.requireNonNull(asOf);
        if (mappings == null && !graph.equals(com.codefit.config.JavaDebuggingSkillSeed.GRAPH))
            throw new IllegalArgumentException("Explicit mappings required outside pilot");
        for (int retry = 0; retry < 3; retry++) {
            try (var c = DatabaseConfig.getConnection()) {
                try (var s = c.createStatement()) {
                    s.execute("PRAGMA foreign_keys=ON");
                }
                long before = version(c);
                c.setAutoCommit(false);
                EstimateInput input;
                try {
                    input =
                            new EstimateInputReader()
                                    .read(
                                            c,
                                            graph,
                                            skill,
                                            mappings == null ? pilotMappings(c) : mappings,
                                            asOf);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
                // No read/write transaction spans policy work or binary encoding.
                c.setAutoCommit(true);
                afterRead.run();
                var result = new OrdinalMasteryPolicy().estimate(input);
                byte[] bytes = EstimateCodec.encode(input);
                var existing = revisions.existing(c, bytes, result);
                if (version(c) != before) continue;
                if (existing.isPresent()) {
                    return existing.get();
                }
                c.setAutoCommit(false);
                try {
                    try (var s = c.createStatement()) {
                        s.executeUpdate(
                                "UPDATE evolution_estimate_epochs SET started_at=started_at WHERE"
                                        + " 0");
                    }
                    // data_version is comparable only on this same connection. The writer lock now
                    // prevents a correction/reset/source edit between validation and publication.
                    if (version(c) != before) {
                        c.rollback();
                        continue;
                    }
                    var revision = revisions.save(c, input, bytes, result);
                    c.commit();
                    return revision;
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Unable to derive local skill estimate", e);
            }
        }
        throw new IllegalStateException("Evidence changed during estimation; retry on demand");
    }

    /**
     * Explicit installed graph/mappings only; publication of a newer mapping never silently selects
     * it.
     */
    public List<EstimateRevisionRepository.Revision> pilot(Instant asOf) {
        return com.codefit.config.JavaDebuggingSkillSeed.graph().definitions().stream()
                .map(
                        s ->
                                estimate(
                                        com.codefit.config.JavaDebuggingSkillSeed.GRAPH,
                                        s.ref(),
                                        null,
                                        asOf))
                .toList();
    }

    public List<EvidenceMappingRef> pilotMappings() {
        // All installed manifests pin exact mapping refs. Unpublished mappings have no
        // observations.
        try (var c = DatabaseConfig.getConnection()) {
            return pilotMappings(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to read pilot mappings", e);
        }
    }

    private List<EvidenceMappingRef> pilotMappings(Connection c) throws SQLException {
        var result = new ArrayList<EvidenceMappingRef>();
        result.add(EvidenceMappingRepository.INITIAL_MAPPING);
        for (var spec : JavaTrainingPack.challenges()) {
            var ref = DurableEvaluationRepository.mapping(spec).ref();
            if (new EvidenceMappingRepository().find(c, ref).isPresent()) result.add(ref);
        }
        return List.copyOf(result);
    }

    /** Caller owns a coherent read transaction; no writes or policy work. Installed pilot only. */
    public CapturedPilot capturePilot(Connection c, Instant asOf) throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException("Caller-owned coherent read transaction required");
        var mappings = pilotMappings(c);
        var graph =
                new SkillCatalogRepository()
                        .findGraph(c, com.codefit.config.JavaDebuggingSkillSeed.GRAPH)
                        .orElseThrow(
                                () -> new IllegalStateException("Exact pilot graph unavailable"));
        var inputs = new ArrayList<EstimateInput>();
        for (var definition : graph.definitions()) {
            inputs.add(
                    new EstimateInputReader()
                            .read(c, graph.ref(), definition.ref(), mappings, asOf));
        }
        return new CapturedPilot(inputs);
    }

    /** Only this repository-backed capture can be prepared for durable publication. */
    public static final class CapturedPilot {
        private final List<EstimateInput> inputs;

        private CapturedPilot(List<EstimateInput> inputs) {
            this.inputs = List.copyOf(inputs);
        }

        public List<EstimateInput> inputs() {
            return inputs;
        }
    }

    /** Opaque prepared estimate; compute after releasing the read snapshot. */
    public static final class PreparedEstimate {
        private final EstimateInput input;
        private final MasteryEstimate estimate;
        private final byte[] bytes;

        private PreparedEstimate(EstimateInput input) {
            this.input = input;
            this.estimate = new OrdinalMasteryPolicy().estimate(input);
            this.bytes = EstimateCodec.encode(input);
        }

        public EstimateInput input() {
            return input;
        }

        public MasteryEstimate estimate() {
            return estimate;
        }
    }

    public List<PreparedEstimate> prepare(CapturedPilot captured) {
        return captured.inputs.stream().map(PreparedEstimate::new).toList();
    }

    /**
     * Caller owns a short writer transaction and MUST validate its original same-connection
     * data_version after acquiring the writer lock. Publishes exactly the captured interpretation.
     * No re-reading, source writes, network calls or private artifact copies.
     */
    public EstimateRevisionRepository.Revision publish(Connection c, PreparedEstimate prepared)
            throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException(
                    "Caller-owned validated writer transaction required");
        return revisions.save(c, prepared.input, prepared.bytes, prepared.estimate);
    }

    public EstimateInput.Epoch reset(GraphRef graph, String commandId) {
        return revisions.reset(graph, commandId, Clock.systemUTC());
    }

    private static long version(Connection c) throws SQLException {
        try (var s = c.createStatement();
                var r = s.executeQuery("PRAGMA data_version")) {
            if (!r.next()) throw new SQLException("Missing data_version");
            return r.getLong(1);
        }
    }
}

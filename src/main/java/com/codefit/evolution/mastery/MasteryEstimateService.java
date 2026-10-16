package com.codefit.evolution.mastery;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.DurableEvaluationRepository;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Explicit on-demand derivation. No startup scan or mutation of evidence, XP or peer aggregates.
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
                if (existing.isPresent()) return existing.get();
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

package com.codefit.desktop;

import com.codefit.config.JavaDebuggingSkillSeed;
import com.codefit.evolution.*;
import com.codefit.evolution.evaluation.DurableEvaluationRepository;
import com.codefit.evolution.quest.JavaTrainingPack;
import com.codefit.repository.*;
import com.codefit.repository.GraphSelectionRepository.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Read-only catalog inspection and explicit selection; no policy publication or learning effects.
 */
final class PracticeGraphService {
    static final String TRACK = "engineering-practice";

    record Compatibility(String mapping, int mappingCount, String estimator) {}

    record Summary(
            GraphRef ref,
            String domain,
            int skills,
            String lifecycle,
            long lifecycleRevision,
            GraphRef replacement,
            String selectable,
            Compatibility compatibility) {}

    record View(
            Instant capturedAt,
            Selection selection,
            String selectionStatus,
            List<Summary> graphs,
            GraphRef inspected,
            SkillGraph detail) {}

    enum Boundary {
        BEFORE_COMMIT,
        AFTER_COMMIT
    }

    private final ExperimentalProfile profile;
    private final Clock clock;
    private final java.util.function.Consumer<Boundary> hook;
    private final GraphSelectionRepository selections = new GraphSelectionRepository();

    PracticeGraphService(ExperimentalProfile p, Clock clock) {
        this(p, clock, b -> {});
    }

    PracticeGraphService(
            ExperimentalProfile p, Clock clock, java.util.function.Consumer<Boundary> hook) {
        if (!p.isLearning()) throw new IllegalArgumentException("NOT_AUTHORIZED");
        this.profile = p;
        this.clock = clock;
        this.hook = hook;
    }

    View read(GraphRef requested) throws SQLException {
        try (var c = profile.connection(true)) {
            c.setAutoCommit(false);
            var catalog = new SkillCatalogRepository();
            var selection = selections.selected(c, TRACK);
            var refs = catalog.publishedGraphs(c);
            var summaries = new ArrayList<Summary>();
            SkillGraph detail = null;
            var inspected = requested == null ? selection.graph() : requested;
            for (var ref : refs) {
                var graph = catalog.findBoundedGraph(c, ref).orElseThrow();
                var life = selections.lifecycle(c, ref);
                String eligible = selections.eligibility(c, ref, selection.domain());
                var domains =
                        graph.definitions().stream()
                                .map(SkillDefinition::domainId)
                                .distinct()
                                .toList();
                summaries.add(
                        new Summary(
                                ref,
                                domains.size() == 1 ? domains.getFirst() : "mixed",
                                graph.definitions().size(),
                                life.state(),
                                life.revision(),
                                life.replacement(),
                                eligible,
                                compatibility(c, graph, eligible)));
                if (ref.equals(inspected)) detail = graph;
            }
            String status =
                    summaries.stream()
                            .filter(s -> s.ref().equals(selection.graph()))
                            .map(Summary::selectable)
                            .findFirst()
                            .orElse("GRAPH_MISSING");
            var result =
                    new View(
                            clock.instant(),
                            selection,
                            status,
                            List.copyOf(summaries),
                            inspected,
                            detail);
            c.commit();
            return result;
        }
    }

    Receipt choose(Command command) throws SQLException {
        validate(command);
        try (var c = profile.connection(false)) {
            c.setAutoCommit(false);
            try {
                var result = selections.choose(c, command, clock.instant());
                hook.accept(Boundary.BEFORE_COMMIT);
                c.commit();
                hook.accept(Boundary.AFTER_COMMIT);
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    Receipt status(Command command) throws SQLException {
        validate(command);
        try (var c = profile.connection(true)) {
            c.setAutoCommit(false);
            var r = selections.receipt(c, command);
            c.commit();
            return r;
        }
    }

    private void validate(Command command) {
        if (!command.profile().equals(profile.id()) || !command.track().equals(TRACK))
            throw new IllegalArgumentException("SESSION_MISMATCH");
    }

    static boolean pilotAvailable(Connection c) throws SQLException {
        var repo = new GraphSelectionRepository();
        var selected = repo.selected(c, TRACK);
        if (!selected.graph().equals(JavaDebuggingSkillSeed.GRAPH)) return false;
        String eligibility = repo.eligibility(c, selected.graph(), selected.domain());
        var graph = new SkillCatalogRepository().findBoundedGraph(c, selected.graph());
        return graph.isPresent()
                && compatibility(c, graph.get(), eligibility).estimator().equals("SUPPORTED_PILOT");
    }

    private static Compatibility compatibility(Connection c, SkillGraph graph, String eligible)
            throws SQLException {
        int count = 0;
        boolean invalid = false;
        var refs = new ArrayList<com.codefit.evolution.evidence.EvidenceMappingRef>();
        try (var q =
                c.prepareStatement(
                        "SELECT CASE WHEN length(mapping_id)<=128 THEN mapping_id"
                            + " END,mapping_version,typeof(mapping_version) FROM"
                            + " evolution_evidence_mappings WHERE graph_id=? AND graph_version=?"
                            + " AND published=1 ORDER BY mapping_id,mapping_version LIMIT 65")) {
            q.setString(1, graph.ref().graphId());
            q.setInt(2, graph.ref().graphVersion());
            try (var r = q.executeQuery()) {
                while (r.next()) {
                    if (++count > 64
                            || r.getString(1) == null
                            || !r.getString(3).equals("integer")
                            || r.getLong(2) > Integer.MAX_VALUE)
                        throw new SQLException("INPUT_LIMIT");
                    refs.add(
                            new com.codefit.evolution.evidence.EvidenceMappingRef(
                                    r.getString(1), r.getInt(2)));
                }
            }
        }
        var mappings = new EvidenceMappingRepository();
        for (var ref : refs) {
            try (var q =
                    c.prepareStatement(
                            "SELECT COUNT(*),COALESCE(MAX(length(selector)+length(skill_id)),0)"
                                + " FROM evolution_evidence_mapping_rules WHERE mapping_id=? AND"
                                + " mapping_version=?")) {
                q.setString(1, ref.mappingId());
                q.setInt(2, ref.version());
                try (var r = q.executeQuery()) {
                    r.next();
                    if (r.getInt(1) > 512 || r.getLong(2) > 256)
                        throw new SQLException("INPUT_LIMIT");
                }
            }
            var m = mappings.find(c, ref).orElseThrow();
            if (m.rules().isEmpty()
                    || !m.graph().equals(graph.ref())
                    || m.rules().stream()
                            .anyMatch(
                                    rule ->
                                            graph.definitions().stream()
                                                    .noneMatch(d -> d.ref().equals(rule.target()))))
                invalid = true;
        }
        String status = invalid ? "INCOMPATIBLE" : count == 0 ? "NONE" : "EXACT_PUBLISHED";
        String estimator = "UNSUPPORTED_GRAPH";
        if (graph.equals(JavaDebuggingSkillSeed.graph())) {
            estimator = "MAPPING_UNAVAILABLE";
            boolean exact = refs.contains(EvidenceMappingRepository.INITIAL_MAPPING) && !invalid;
            var expected = new ArrayList<com.codefit.evolution.evidence.EvidenceMappingRef>();
            expected.add(EvidenceMappingRepository.INITIAL_MAPPING);
            for (var spec : JavaTrainingPack.challenges())
                expected.add(DurableEvaluationRepository.mapping(spec).ref());
            for (var ref : expected)
                try (var q =
                        c.prepareStatement(
                                "SELECT 1 FROM evolution_evidence_mappings WHERE mapping_id=? AND"
                                    + " mapping_version>? AND published=1 LIMIT 1")) {
                    q.setString(1, ref.mappingId());
                    q.setInt(2, ref.version());
                    try (var r = q.executeQuery()) {
                        if (r.next()) exact = false;
                    }
                }
            if (exact) estimator = "SUPPORTED_PILOT";
        }
        if (!eligible.equals("ELIGIBLE")) estimator = "LIFECYCLE_UNAVAILABLE";
        return new Compatibility(status, count, estimator);
    }
}

package com.codefit.repository;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import com.codefit.evolution.*;
import com.codefit.evolution.evidence.*;

import java.sql.*;
import java.util.*;

/**
 * Explicit immutable mapping lineage. Publishing vN explicitly replaces vN-1; no latest graph
 * selection.
 */
public final class EvidenceMappingRepository {
    public static final EvidenceMappingRef INITIAL_MAPPING =
            new EvidenceMappingRef("java-practice-context", 1);

    public void publish(EvidenceMapping mapping) {
        new EvidenceRepository()
                .transaction(
                        c -> {
                            publish(c, mapping);
                            return null;
                        });
    }

    public void publish(Connection c, EvidenceMapping m) throws SQLException {
        if (c.getAutoCommit())
            throw new IllegalArgumentException("Publication requires transaction");
        var existing = find(c, m.ref());
        if (existing.isPresent()) {
            if (!existing.get().equals(m))
                throw new IllegalArgumentException("Conflicting mapping version");
            return;
        }
        int last = 0;
        try (var s =
                c.prepareStatement(
                        "SELECT COALESCE(MAX(mapping_version),0) FROM evolution_evidence_mappings"
                            + " WHERE mapping_id=?")) {
            s.setString(1, m.ref().mappingId());
            try (var r = s.executeQuery()) {
                if (r.next()) last = r.getInt(1);
            }
        }
        if (m.ref().version() != last + 1)
            throw new IllegalArgumentException(
                    "Mapping versions must be contiguous explicit replacements");
        try (var s =
                c.prepareStatement(
                        "SELECT published FROM evolution_skill_graphs WHERE graph_id=? AND"
                            + " graph_version=?")) {
            s.setString(1, m.graph().graphId());
            s.setInt(2, m.graph().graphVersion());
            try (var r = s.executeQuery()) {
                if (!r.next() || r.getInt(1) != 1)
                    throw new IllegalArgumentException("Published exact graph required");
            }
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO"
                            + " evolution_evidence_mappings(mapping_id,mapping_version,graph_id,graph_version)"
                            + " VALUES(?,?,?,?)")) {
            s.setString(1, m.ref().mappingId());
            s.setInt(2, m.ref().version());
            s.setString(3, m.graph().graphId());
            s.setInt(4, m.graph().graphVersion());
            s.executeUpdate();
        }
        try (var s =
                c.prepareStatement(
                        "INSERT INTO evolution_evidence_mapping_rules VALUES(?,?,?,?,?,?)")) {
            for (var r : m.rules()) {
                s.setString(1, m.ref().mappingId());
                s.setInt(2, m.ref().version());
                s.setString(3, r.sourceType().name());
                s.setString(4, r.selector());
                s.setString(5, r.target().skillId());
                s.setInt(6, r.target().definitionVersion());
                s.executeUpdate();
            }
        }
        try (var s =
                c.prepareStatement(
                        "UPDATE evolution_evidence_mappings SET published=1 WHERE mapping_id=? AND"
                            + " mapping_version=?")) {
            s.setString(1, m.ref().mappingId());
            s.setInt(2, m.ref().version());
            s.executeUpdate();
        }
    }

    public Optional<EvidenceMapping> find(Connection c, EvidenceMappingRef ref)
            throws SQLException {
        GraphRef graph;
        try (var s =
                c.prepareStatement(
                        "SELECT graph_id,graph_version FROM evolution_evidence_mappings WHERE"
                            + " mapping_id=? AND mapping_version=? AND published=1")) {
            s.setString(1, ref.mappingId());
            s.setInt(2, ref.version());
            try (var r = s.executeQuery()) {
                if (!r.next()) return Optional.empty();
                graph = new GraphRef(r.getString(1), r.getInt(2));
            }
        }
        var rules = new ArrayList<EvidenceMapping.Rule>();
        try (var s =
                c.prepareStatement(
                        "SELECT source_type,selector,skill_id,definition_version FROM"
                            + " evolution_evidence_mapping_rules WHERE mapping_id=? AND"
                            + " mapping_version=?")) {
            s.setString(1, ref.mappingId());
            s.setInt(2, ref.version());
            try (var r = s.executeQuery()) {
                while (r.next())
                    rules.add(
                            new EvidenceMapping.Rule(
                                    SourceType.valueOf(r.getString(1)),
                                    r.getString(2),
                                    new SkillRef(r.getString(3), r.getInt(4))));
            }
        }
        return Optional.of(new EvidenceMapping(ref, graph, rules));
    }
}

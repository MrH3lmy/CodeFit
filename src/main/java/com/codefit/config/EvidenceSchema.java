package com.codefit.config;

import static com.codefit.evolution.evidence.EvidenceTypes.SourceType.*;

import com.codefit.evolution.SkillRef;
import com.codefit.evolution.evidence.*;
import com.codefit.repository.EvidenceMappingRepository;

import java.sql.*;
import java.util.List;

/** Additive EV-03 metadata only. No source-history scan or learner-state mutation in migration. */
final class EvidenceSchema {
    private EvidenceSchema() {}

    static void create(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute(
                    """
                        CREATE TABLE evolution_evidence_mappings (
                            mapping_id TEXT NOT NULL, mapping_version INTEGER NOT NULL CHECK(mapping_version>0),
                            graph_id TEXT NOT NULL, graph_version INTEGER NOT NULL, published INTEGER NOT NULL DEFAULT 0 CHECK(published IN(0,1)),
                            PRIMARY KEY(mapping_id,mapping_version),
                            FOREIGN KEY(graph_id,graph_version) REFERENCES evolution_skill_graphs(graph_id,graph_version))
                    """);
            s.execute(
                    """
                        CREATE TABLE evolution_evidence_mapping_rules (
                            mapping_id TEXT NOT NULL, mapping_version INTEGER NOT NULL, source_type TEXT NOT NULL, selector TEXT NOT NULL,
                            skill_id TEXT NOT NULL, definition_version INTEGER NOT NULL,
                            PRIMARY KEY(mapping_id,mapping_version,source_type,selector,skill_id,definition_version),
                            FOREIGN KEY(mapping_id,mapping_version) REFERENCES evolution_evidence_mappings(mapping_id,mapping_version),
                            FOREIGN KEY(skill_id,definition_version) REFERENCES evolution_skill_definitions(skill_id,definition_version))
                    """);
            s.execute(
                    """
                        CREATE TABLE evolution_evidence_sources (
                            root_id TEXT PRIMARY KEY, source_type TEXT NOT NULL, source_record_id INTEGER NOT NULL CHECK(source_record_id>0),
                            head_id TEXT REFERENCES evolution_evidence_observations(evidence_id),
                            UNIQUE(source_type,source_record_id))
                    """);
            s.execute(
                    """
                        CREATE TABLE evolution_evidence_observations (
                            sequence INTEGER PRIMARY KEY AUTOINCREMENT, evidence_id TEXT NOT NULL UNIQUE,
                            root_id TEXT NOT NULL REFERENCES evolution_evidence_sources(root_id),
                            revision INTEGER NOT NULL CHECK(revision>0), recorded_at TEXT NOT NULL,
                            previous_id TEXT REFERENCES evolution_evidence_observations(evidence_id),
                            change_kind TEXT NOT NULL CHECK(change_kind IN('ORIGINAL','SUPERSESSION','RETRACTION')),
                            payload BLOB NOT NULL CHECK(length(payload)<=65536), UNIQUE(root_id,revision))
                    """);
            s.execute(
                    """
                        CREATE TABLE evolution_evidence_interpretations (
                            evidence_id TEXT NOT NULL REFERENCES evolution_evidence_observations(evidence_id),
                            mapping_id TEXT NOT NULL, mapping_version INTEGER NOT NULL, selector TEXT NOT NULL,
                            PRIMARY KEY(evidence_id,mapping_id,mapping_version),
                            FOREIGN KEY(mapping_id,mapping_version) REFERENCES evolution_evidence_mappings(mapping_id,mapping_version))
                    """);
            s.execute(
                    """
                        CREATE TABLE evolution_evidence_cursors (
                            mapping_id TEXT NOT NULL, mapping_version INTEGER NOT NULL, source_type TEXT NOT NULL,
                            last_source_id INTEGER NOT NULL DEFAULT 0 CHECK(last_source_id>=0),
                            PRIMARY KEY(mapping_id,mapping_version,source_type),
                            FOREIGN KEY(mapping_id,mapping_version) REFERENCES evolution_evidence_mappings(mapping_id,mapping_version))
                    """);
            s.execute(
                    """
                        CREATE TABLE evolution_evidence_evaluation_links (
                            submission_id TEXT PRIMARY KEY, root_id TEXT NOT NULL UNIQUE REFERENCES evolution_evidence_sources(root_id),
                            problem_attempt_id INTEGER UNIQUE)
                    """);
            s.execute(
                    "CREATE INDEX evolution_evidence_skill ON"
                        + " evolution_evidence_mapping_rules(skill_id,definition_version,mapping_id,mapping_version)");
            s.execute(
                    "CREATE INDEX evolution_evidence_context ON"
                        + " evolution_evidence_interpretations(mapping_id,mapping_version,selector,evidence_id)");
            s.execute(
                    """
                        CREATE TRIGGER evolution_evidence_mapping_member BEFORE INSERT ON evolution_evidence_mapping_rules
                        BEGIN
                          SELECT RAISE(ABORT,'Mapping is sealed') WHERE EXISTS(SELECT 1 FROM evolution_evidence_mappings m WHERE m.mapping_id=NEW.mapping_id AND m.mapping_version=NEW.mapping_version AND m.published=1);
                          SELECT RAISE(ABORT,'Mapping requires exact published graph member') WHERE NOT EXISTS(
                            SELECT 1 FROM evolution_evidence_mappings m JOIN evolution_skill_graphs g USING(graph_id,graph_version)
                            JOIN evolution_graph_skills k USING(graph_id,graph_version)
                            WHERE m.mapping_id=NEW.mapping_id AND m.mapping_version=NEW.mapping_version AND g.published=1
                            AND k.skill_id=NEW.skill_id AND k.definition_version=NEW.definition_version);
                        END
                    """);
            s.execute(
                    """
                        CREATE TRIGGER evolution_evidence_mapping_sealed BEFORE UPDATE ON evolution_evidence_mappings
                        WHEN OLD.published=1 BEGIN SELECT RAISE(ABORT,'Mapping is immutable'); END
                    """);
            s.execute(
                    """
                        CREATE TRIGGER evolution_evidence_interpretation_published BEFORE INSERT ON evolution_evidence_interpretations
                        WHEN NOT EXISTS(SELECT 1 FROM evolution_evidence_mappings WHERE mapping_id=NEW.mapping_id AND mapping_version=NEW.mapping_version AND published=1)
                        BEGIN SELECT RAISE(ABORT,'Interpretation requires published mapping'); END
                    """);
            for (String table :
                    List.of(
                            "evolution_evidence_observations",
                            "evolution_evidence_interpretations",
                            "evolution_evidence_mapping_rules",
                            "evolution_evidence_evaluation_links")) {
                for (String action : List.of("UPDATE", "DELETE"))
                    s.execute(
                            "CREATE TRIGGER "
                                    + table
                                    + "_"
                                    + action.toLowerCase()
                                    + " BEFORE "
                                    + action
                                    + " ON "
                                    + table
                                    + " BEGIN SELECT RAISE(ABORT,'Evidence history is immutable');"
                                    + " END");
            }
            s.execute(
                    "CREATE TRIGGER evolution_evidence_mapping_delete BEFORE DELETE ON"
                        + " evolution_evidence_mappings BEGIN SELECT RAISE(ABORT,'Mapping history"
                        + " is immutable'); END");
        }
        new EvidenceMappingRepository()
                .publish(
                        c,
                        new EvidenceMapping(
                                EvidenceMappingRepository.INITIAL_MAPPING,
                                JavaDebuggingSkillSeed.GRAPH,
                                List.of(
                                        new EvidenceMapping.Rule(
                                                REVIEW,
                                                "JCIP Ch15 - Atomics and Nonblocking",
                                                new SkillRef("java.concurrency.atomic-update", 1)),
                                        new EvidenceMapping.Rule(
                                                REVIEW,
                                                "JCIP Ch10 - Liveness Hazards",
                                                new SkillRef("java.concurrency.liveness", 1)),
                                        new EvidenceMapping.Rule(
                                                ASSESSMENT_ATTEMPT,
                                                "Concurrency",
                                                new SkillRef(
                                                        "java.concurrency.atomic-update", 1)))));
    }
}

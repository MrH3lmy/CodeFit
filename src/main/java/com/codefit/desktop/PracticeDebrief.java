package com.codefit.desktop;

import com.codefit.evolution.evaluation.*;
import com.codefit.evolution.evidence.*;
import com.codefit.evolution.quest.*;
import com.codefit.repository.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Allowlisted metadata in one query-only snapshot. Never publishes interpretations. */
final class PracticeDebrief {
    record Target(String skillId, int skillVersion, int behavior) {}

    record Check(
            String id,
            String kind,
            boolean required,
            String status,
            String feedback,
            String limitations,
            List<Target> targets) {}

    record Evidence(
            String status,
            String id,
            Integer revision,
            String previousId,
            String recordedAt,
            String provenance,
            String outcome,
            String availability,
            String assistance,
            String exposure,
            String change,
            List<String> limitations,
            String mappingId,
            Integer mappingVersion,
            String mappingStatus) {}

    record Skill(
            String id,
            int version,
            String name,
            List<String> behaviors,
            LearnerSkillProjection.Estimate estimate) {}

    record Projection(
            int v,
            String readAt,
            PracticeEvaluationService.Result evaluation,
            String questId,
            int questVersion,
            String graphId,
            int graphVersion,
            String rubricId,
            int rubricVersion,
            int evaluationRevision,
            boolean corrected,
            String familyId,
            String variantId,
            String cohort,
            String familiarity,
            boolean solutionRevealed,
            int priorFamilySubmissions,
            String limitations,
            List<Check> checks,
            Evidence evidence,
            String skillStatus,
            List<Skill> skills) {}

    static Projection read(
            Connection c,
            DurableEvaluationRepository.Submission s,
            PracticeEvaluationService.Result result,
            Instant at)
            throws SQLException {
        if (c.getAutoCommit()) throw new IllegalArgumentException("Read transaction required");
        var snapshot = s.snapshot();
        var spec = snapshot.challenge();
        if (snapshot.submittedAt().isAfter(at)) throw new SQLException("PROFILE_INVALID");
        // Existing bounded historical interpreter, not MasteryEstimateService. The response never
        // calls a historical band current support, even if its saved freshness was CURRENT.
        var graph = LearnerSkillProjection.read(c, "", "", at);
        boolean exact =
                graph.graph() != null
                        && graph.graph().id().equals(spec.graph().graphId())
                        && graph.graph().version() == spec.graph().graphVersion();
        var skills = new ArrayList<Skill>();
        if (exact)
            for (var target :
                    spec.targets().stream()
                            .map(BehaviorTarget::skill)
                            .distinct()
                            .sorted()
                            .toList()) {
                var node =
                        graph.nodes().stream()
                                .filter(
                                        n ->
                                                n.ref().id().equals(target.skillId())
                                                        && n.ref().version()
                                                                == target.definitionVersion())
                                .findFirst();
                if (node.isEmpty()) {
                    exact = false;
                    skills.clear();
                    break;
                }
                var n = node.get();
                skills.add(
                        new Skill(
                                n.ref().id(),
                                n.ref().version(),
                                n.name(),
                                n.behaviors(),
                                n.estimate()));
            }
        var checks =
                spec.rubric().checks().stream()
                        .map(
                                d -> {
                                    var feedback =
                                            result.view().checks().stream()
                                                    .filter(f -> f.id().equals(d.id()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    return new Check(
                                            d.id(),
                                            d.kind().name(),
                                            d.required(),
                                            feedback.status().name(),
                                            feedback.feedback(),
                                            d.limitations(),
                                            d.targets().stream()
                                                    .map(
                                                            t ->
                                                                    new Target(
                                                                            t.skill().skillId(),
                                                                            t.skill()
                                                                                    .definitionVersion(),
                                                                            t.behavior()))
                                                    .toList());
                                })
                        .toList();
        Evidence evidence = evidence(c, s, at);
        return new Projection(
                1,
                at.toString(),
                result,
                snapshot.presentation().quest().id(),
                snapshot.presentation().quest().version(),
                spec.graph().graphId(),
                spec.graph().graphVersion(),
                spec.rubric().ref().id(),
                spec.rubric().ref().version(),
                s.revision(),
                s.revision() > 1,
                spec.familyId(),
                spec.variantId(),
                spec.cohort().name(),
                snapshot.presentation().declaration().name(),
                snapshot.exposureAtSubmission().solutionRevealed(),
                snapshot.priorFamilySubmissions(),
                spec.rubric().limitations(),
                checks,
                evidence,
                exact ? "available" : "GRAPH_UNAVAILABLE",
                List.copyOf(skills));
    }

    private static Evidence unavailable(String status) {
        return new Evidence(
                status,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                "UNAVAILABLE");
    }

    private static Evidence evidence(
            Connection c, DurableEvaluationRepository.Submission s, Instant at)
            throws SQLException {
        var source =
                new EvidenceSourceRef(
                        EvidenceTypes.SourceType.CHALLENGE_EVALUATION, s.id(), s.revision());
        String root = EvidenceIds.root(source);
        // Bound relevant observation history before any repository payload decoding.
        try (var q =
                c.prepareStatement(
                        "SELECT COUNT(*),COALESCE(MAX(length(CAST(payload AS"
                                + " BLOB))),0),COALESCE(SUM(length(CAST(payload AS BLOB))),0) FROM"
                                + " evolution_evidence_observations WHERE root_id=?")) {
            q.setString(1, root);
            try (var r = q.executeQuery()) {
                r.next();
                if (r.getLong(1) > 64 || r.getLong(2) > 131072 || r.getLong(3) > 1048576)
                    return unavailable("LIMIT_EXCEEDED");
            }
        }
        if (s.revision() < 1 || s.revision() > 64) return unavailable("LIMIT_EXCEEDED");
        var input =
                new DurableEvaluationRepository().evaluationInput(c, s.snapshot().request().id());
        if (input.isEmpty()) return unavailable("SOURCE_UNAVAILABLE");
        var observation = input.get().observation();
        var head = new EvidenceRepository().head(c, root);
        if (head.isEmpty()) return unavailable("SOURCE_UNAVAILABLE");
        if (!head.get().observation().equals(observation))
            return unavailable("SUPERSEDED_OR_INVALID");
        var stored = head.get();
        if (stored.recordedAt() == null || stored.recordedAt().isAfter(at))
            return unavailable("SOURCE_UNAVAILABLE");
        var mapping = input.get().mapping();
        boolean mapped =
                new EvidenceMappingRepository()
                        .find(c, mapping.ref())
                        .filter(mapping::equals)
                        .isPresent();
        if (mapped) {
            try (var q =
                    c.prepareStatement(
                            "SELECT 1 FROM evolution_evidence_interpretations WHERE evidence_id=?"
                                    + " AND mapping_id=? AND mapping_version=? AND selector=?")) {
                q.setString(1, stored.id());
                q.setString(2, mapping.ref().mappingId());
                q.setInt(3, mapping.ref().version());
                q.setString(4, s.snapshot().presentation().familyId());
                try (var r = q.executeQuery()) {
                    mapped = r.next();
                }
            }
        }
        String mappingStatus = mapped ? "EXACT" : "UNAVAILABLE";
        if (mapped) {
            try (var q =
                    c.prepareStatement(
                            "SELECT 1 FROM evolution_evidence_mappings WHERE mapping_id=? AND"
                                + " mapping_version>? AND published=1 LIMIT 1")) {
                q.setString(1, mapping.ref().mappingId());
                q.setInt(2, mapping.ref().version());
                try (var r = q.executeQuery()) {
                    if (r.next()) mappingStatus = "SUPERSEDED";
                }
            }
        }
        return new Evidence(
                "RECORDED",
                stored.id(),
                observation.source().revision(),
                observation.previousEvidenceId(),
                stored.recordedAt().toString(),
                observation.provenance().name(),
                observation.outcome().name(),
                observation.availability().name(),
                observation.assistance().name(),
                observation.exposure().name(),
                observation.change().name(),
                observation.limitations().stream().map(Enum::name).sorted().toList(),
                mapping.ref().mappingId(),
                mapping.ref().version(),
                mappingStatus);
    }
}

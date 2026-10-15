package com.codefit.service;

import static com.codefit.evolution.evidence.EvidenceTypes.*;

import com.codefit.evolution.evidence.*;
import com.codefit.repository.*;

import java.sql.*;

/**
 * Finite keyset batches. Observation, mapping interpretation and durable cursor commit together.
 */
public final class EvidenceIngestionService {
    private final EvidenceRepository evidence;
    private final LearningEvidenceSourceRepository sources = new LearningEvidenceSourceRepository();

    public EvidenceIngestionService() {
        this(new EvidenceRepository());
    }

    public EvidenceIngestionService(EvidenceRepository evidence) {
        this.evidence = evidence;
    }

    public record BatchResult(int processed, long lastSourceId) {}

    public BatchResult ingestBatch(EvidenceMappingRef mapping, SourceType type, int limit) {
        if (type == SourceType.CHALLENGE_EVALUATION || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Legacy finite batch required");
        return evidence.transaction(
                c -> {
                    if (new EvidenceMappingRepository().find(c, mapping).isEmpty())
                        throw new IllegalArgumentException("Unknown mapping");
                    try (var s =
                            c.prepareStatement(
                                    "INSERT OR IGNORE INTO"
                                        + " evolution_evidence_cursors(mapping_id,mapping_version,source_type)"
                                        + " VALUES(?,?,?)")) {
                        s.setString(1, mapping.mappingId());
                        s.setInt(2, mapping.version());
                        s.setString(3, type.name());
                        s.executeUpdate();
                    }
                    long cursor;
                    try (var s =
                            c.prepareStatement(
                                    "SELECT last_source_id FROM evolution_evidence_cursors WHERE"
                                            + " mapping_id=? AND mapping_version=? AND"
                                            + " source_type=?")) {
                        s.setString(1, mapping.mappingId());
                        s.setInt(2, mapping.version());
                        s.setString(3, type.name());
                        try (var r = s.executeQuery()) {
                            r.next();
                            cursor = r.getLong(1);
                        }
                    }
                    var batch = sources.batch(c, type, cursor, limit);
                    for (var source : batch) {
                        var observed = source.observation();
                        var head = evidence.head(c, EvidenceIds.root(observed.source()));
                        if (head.isPresent()) {
                            var current = head.get().observation();
                            if (current.change() != Change.RETRACTION
                                    && !current.sameFacts(observed))
                                throw new IllegalArgumentException(
                                        "Source changed: explicit correction required");
                            // Replays and remaps preserve the current revision, including a
                            // retraction.
                            String selector = source.selector();
                            if (current.change() == Change.RETRACTION)
                                selector = evidence.context(c, head.get().id());
                            evidence.append(c, current, mapping, selector);
                        } else evidence.append(c, observed, mapping, source.selector());
                        cursor = observed.source().recordId();
                    }
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE evolution_evidence_cursors SET last_source_id=? WHERE"
                                            + " mapping_id=? AND mapping_version=? AND"
                                            + " source_type=?")) {
                        s.setLong(1, cursor);
                        s.setString(2, mapping.mappingId());
                        s.setInt(3, mapping.version());
                        s.setString(4, type.name());
                        s.executeUpdate();
                    }
                    return new BatchResult(batch.size(), cursor);
                });
    }

    /**
     * Explicit old-row refresh; no invented event revision from a dashboard or periodic full-table
     * scan.
     */
    public AppendResult refresh(
            SourceType type,
            long sourceId,
            EvidenceMappingRef mapping,
            ChangeReason reason,
            Actor actor) {
        if (reason == ChangeReason.ORIGINAL_RECORD)
            throw new IllegalArgumentException("Correction reason required");
        return evidence.transaction(
                c -> {
                    var source =
                            sources.find(c, new EvidenceSourceRef(type, sourceId, 1))
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "Source unavailable"));
                    var head =
                            evidence.head(c, EvidenceIds.root(source.observation().source()))
                                    .orElseThrow(
                                            () ->
                                                    new IllegalArgumentException(
                                                            "No history to correct"));
                    if (head.observation().sameFacts(source.observation())
                            && evidence.context(c, head.id()).equals(source.selector()))
                        return evidence.append(c, head.observation(), mapping, source.selector());
                    var revised =
                            source.observation()
                                    .revise(
                                            head.observation().source().revision() + 1,
                                            head.id(),
                                            reason,
                                            actor);
                    return evidence.append(c, revised, mapping, source.selector());
                });
    }
}

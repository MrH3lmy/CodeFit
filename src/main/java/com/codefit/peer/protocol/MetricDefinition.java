package com.codefit.peer.protocol;

import java.util.Objects;
import java.util.Set;

/**
 * A versioned metric meaning. Two values are comparable only when their {@code (metricId, version)}
 * match; changing how a metric is computed requires a new version, never a silent redefinition.
 *
 * @param minimumSampleSize samples required before a value may be MEASURED (0 for plain counts)
 * @param allowedProvenance evidence kinds this metric may be computed from
 * @param localSource       the local data this metric is projected from (documentation for #183)
 */
public record MetricDefinition(String metricId, int version, MetricUnit unit, long minimumSampleSize,
                               Set<MetricProvenance> allowedProvenance, String localSource) {
    public MetricDefinition {
        ProtocolText.identifier(metricId, "Metric id");
        if (version < 1 || version > 0xFFFF) {
            throw new IllegalArgumentException("Metric version must be 1..65535.");
        }
        Objects.requireNonNull(unit, "unit");
        if (minimumSampleSize < 0) {
            throw new IllegalArgumentException("Minimum sample size must not be negative.");
        }
        allowedProvenance = Set.copyOf(allowedProvenance);
        if (allowedProvenance.isEmpty()) {
            throw new IllegalArgumentException("A metric needs at least one allowed provenance.");
        }
        Objects.requireNonNull(localSource, "localSource");
    }
}

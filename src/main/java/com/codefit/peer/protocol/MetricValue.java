package com.codefit.peer.protocol;

import java.util.Objects;

/**
 * One aggregate metric in a {@link ProgressSummary}. Only integers travel on the wire, so every peer
 * computing over the same values gets identical results (#186 group rankings depend on this).
 *
 * <p>Structural rules apply to every value; the registry rules (unit, provenance, minimum sample) apply
 * only when the {@code (metricId, metricVersion)} is known to this build.
 */
public record MetricValue(String metricId, int metricVersion, MetricUnit unit, MetricAvailability availability,
                          long value, long sampleSize, MetricProvenance provenance, TimestampBasis timestampBasis) {

    public MetricValue {
        ProtocolText.identifier(metricId, "Metric id");
        if (metricVersion < 1 || metricVersion > 0xFFFF) {
            throw new IllegalArgumentException("Metric version must be 1..65535.");
        }
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(timestampBasis, "timestampBasis");
        if (sampleSize < 0 || sampleSize > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("Sample size must be 0..2^32-1.");
        }
        if (availability != MetricAvailability.MEASURED && value != 0) {
            throw new IllegalArgumentException("A metric that is not MEASURED must carry value 0.");
        }
        if (availability == MetricAvailability.UNAVAILABLE && sampleSize != 0) {
            throw new IllegalArgumentException("An UNAVAILABLE metric has no samples.");
        }
        long max = switch (unit) {
            case BASIS_POINTS -> 10_000;
            case PERCENT -> 100;
            case COUNT, SECONDS -> Long.MAX_VALUE;
        };
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(metricId + " value out of range for " + unit + ": " + value);
        }
        MetricRegistry.find(metricId, metricVersion).ifPresent(definition -> {
            if (definition.unit() != unit) {
                throw new IllegalArgumentException(metricId + " must use unit " + definition.unit());
            }
            if (!definition.allowedProvenance().contains(provenance)) {
                throw new IllegalArgumentException(metricId + " cannot be computed from " + provenance + " evidence.");
            }
            if (availability == MetricAvailability.MEASURED && sampleSize < definition.minimumSampleSize()) {
                throw new IllegalArgumentException(metricId + " needs " + definition.minimumSampleSize() + " samples to be MEASURED.");
            }
            if (availability == MetricAvailability.INSUFFICIENT_DATA && sampleSize >= definition.minimumSampleSize()) {
                throw new IllegalArgumentException(metricId + " has enough samples; it must be MEASURED.");
            }
        });
    }

    /** True when this build knows the metric definition, i.e. the value may take part in comparisons. */
    public boolean comparable() {
        return MetricRegistry.find(metricId, metricVersion).isPresent();
    }

    void writeTo(CanonicalWriter writer) {
        writer.string(metricId, ProtocolText.MAX_IDENTIFIER_BYTES)
                .u16(metricVersion)
                .u8(unit.code())
                .u8(availability.code())
                .i64(value)
                .u32(sampleSize)
                .u8(provenance.code())
                .u8(timestampBasis.code());
    }

    static MetricValue readFrom(CanonicalReader reader) {
        return new MetricValue(
                reader.string(ProtocolText.MAX_IDENTIFIER_BYTES),
                reader.u16(),
                WireCode.fromCode(MetricUnit.class, reader.u8()),
                WireCode.fromCode(MetricAvailability.class, reader.u8()),
                reader.i64(),
                reader.u32(),
                WireCode.fromCode(MetricProvenance.class, reader.u8()),
                WireCode.fromCode(TimestampBasis.class, reader.u8()));
    }
}

package com.codefit.peer.protocol;

/**
 * One domain of a {@link PreparationSnapshot}: the same invariants as
 * {@code InterviewDomainReadiness}, by id only (titles are local display data).
 */
public record DomainSnapshot(String domainId, int weightPercent, boolean criticalGate, Integer thresholdPercent,
                             Integer scorePercent, int coveragePercent, int measuredRequirementCount,
                             int totalRequirementCount, DomainStatus status) {

    public DomainSnapshot {
        ProtocolText.identifier(domainId, "Domain id");
        percent(weightPercent, "weight");
        percent(coveragePercent, "coverage");
        if (thresholdPercent != null) {
            percent(thresholdPercent, "threshold");
        }
        if (scorePercent != null) {
            percent(scorePercent, "score");
        }
        if (criticalGate && thresholdPercent == null) {
            throw new IllegalArgumentException("Critical domain '" + domainId + "' must carry its threshold.");
        }
        if (totalRequirementCount < 0 || totalRequirementCount > 0xFFFF
                || measuredRequirementCount < 0 || measuredRequirementCount > totalRequirementCount) {
            throw new IllegalArgumentException("Requirement counts out of range for '" + domainId + "'.");
        }
        java.util.Objects.requireNonNull(status, "status");
        boolean hasScore = scorePercent != null;
        if ((status == DomainStatus.NOT_MEASURED) == hasScore) {
            throw new IllegalArgumentException("Domain '" + domainId + "': NOT_MEASURED exactly when there is no score.");
        }
        boolean criticalStatus = status == DomainStatus.PASS || status == DomainStatus.FAIL || status == DomainStatus.PARTIAL;
        if (criticalStatus && !criticalGate) {
            throw new IllegalArgumentException("Domain '" + domainId + "': " + status + " applies only to critical gates.");
        }
        if (status == DomainStatus.MEASURED && criticalGate) {
            throw new IllegalArgumentException("Domain '" + domainId + "': a scored critical gate is PASS, FAIL, or PARTIAL.");
        }
        int expectedCoverage = totalRequirementCount == 0 ? 0
                : (measuredRequirementCount * 200 + totalRequirementCount) / (2 * totalRequirementCount);
        if (coveragePercent != expectedCoverage) {
            throw new IllegalArgumentException("Domain '" + domainId + "': coverage must be round(measured / total).");
        }
        boolean fullyMeasured = measuredRequirementCount == totalRequirementCount;
        if (status == DomainStatus.PASS && (!fullyMeasured || scorePercent < thresholdPercent)) {
            throw new IllegalArgumentException("Domain '" + domainId + "': PASS requires every requirement measured and a score at/above threshold.");
        }
        if (status == DomainStatus.PARTIAL && (fullyMeasured || scorePercent < thresholdPercent)) {
            throw new IllegalArgumentException("Domain '" + domainId + "': PARTIAL is an at/above-threshold score with unmeasured requirements.");
        }
        // FAIL may carry a score at/above threshold: direct mock evidence below threshold forces FAIL.
    }

    private static void percent(int value, String field) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("Domain " + field + " must be 0..100: " + value);
        }
    }

    void writeTo(CanonicalWriter writer) {
        writer.string(domainId, ProtocolText.MAX_IDENTIFIER_BYTES)
                .u8(weightPercent)
                .bool(criticalGate)
                .optionalU8(thresholdPercent)
                .optionalU8(scorePercent)
                .u8(coveragePercent)
                .u16(measuredRequirementCount)
                .u16(totalRequirementCount)
                .u8(status.code());
    }

    static DomainSnapshot readFrom(CanonicalReader reader) {
        return new DomainSnapshot(reader.string(ProtocolText.MAX_IDENTIFIER_BYTES), reader.u8(), reader.bool(),
                reader.optionalU8(), reader.optionalU8(), reader.u8(), reader.u16(), reader.u16(),
                WireCode.fromCode(DomainStatus.class, reader.u8()));
    }
}

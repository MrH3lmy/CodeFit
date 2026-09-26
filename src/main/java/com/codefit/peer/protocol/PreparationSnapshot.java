package com.codefit.peer.protocol;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A dated, shareable record of one interview-preparation readiness result. Readiness is otherwise
 * computed at read time, so history exists only from snapshots actually captured: an unknown past state
 * stays unknown and is never reconstructed.
 *
 * <p>Coverage and critical gates are preserved exactly: the receiver re-derives {@link #status()}
 * from the domains with the same rules as {@code InterviewReadinessService} and rejects any snapshot
 * whose declared status disagrees - a strong average with a failing or partially covered critical gate
 * cannot be sent as READY. {@code coveragePercent} is always shown beside the score, because READY
 * does not by itself imply 100% coverage of non-critical domains.
 *
 * <p>Two snapshots are comparable only if {@code profileId}, {@code profileFingerprint}, and
 * {@code scoringVersion} all match.
 */
public record PreparationSnapshot(String profileId, byte[] profileFingerprint, int scoringVersion,
                                  int overallThresholdPercent, Instant capturedAt, Integer overallPercent,
                                  int coveragePercent, PreparationStatus status, List<DomainSnapshot> domains)
        implements MessageBody {
    static final int FINGERPRINT_LENGTH = 32;
    static final int MAX_DOMAINS = 64;

    public PreparationSnapshot {
        ProtocolText.identifier(profileId, "Profile id");
        profileFingerprint = ProtocolBytes.copyExact(profileFingerprint, FINGERPRINT_LENGTH, "Profile fingerprint");
        if (scoringVersion < 1 || scoringVersion > 0xFFFF) {
            throw new IllegalArgumentException("Scoring version must be 1..65535.");
        }
        if (overallThresholdPercent < 0 || overallThresholdPercent > 100
                || coveragePercent < 0 || coveragePercent > 100
                || (overallPercent != null && (overallPercent < 0 || overallPercent > 100))) {
            throw new IllegalArgumentException("Snapshot percentages must be 0..100.");
        }
        ProtocolTime.toWireMillis(Objects.requireNonNull(capturedAt, "capturedAt"), "capturedAt");
        Objects.requireNonNull(status, "status");
        domains = List.copyOf(domains);
        if (domains.isEmpty() || domains.size() > MAX_DOMAINS) {
            throw new IllegalArgumentException("A snapshot carries 1.." + MAX_DOMAINS + " domains.");
        }
        int weights = 0;
        for (int i = 0; i < domains.size(); i++) {
            weights += domains.get(i).weightPercent();
            if (i > 0 && domains.get(i - 1).domainId().compareTo(domains.get(i).domainId()) >= 0) {
                throw new ProtocolException(RejectionReason.NON_CANONICAL, "Domains must be strictly ascending by id.");
            }
        }
        if (weights != 100) {
            throw new IllegalArgumentException("Domain weights must sum to exactly 100, found " + weights);
        }
        PreparationStatus derived = deriveStatus(domains, overallPercent, overallThresholdPercent);
        if (derived != status) {
            throw new IllegalArgumentException("Declared status " + status + " contradicts the domains (derived " + derived + ").");
        }
    }

    /** Mirrors the overall-status rule in {@code InterviewReadinessService#calculate}. */
    static PreparationStatus deriveStatus(List<DomainSnapshot> domains, Integer overallPercent, int thresholdPercent) {
        boolean anyCriticalFailed = domains.stream()
                .anyMatch(d -> d.criticalGate() && d.status() == DomainStatus.FAIL);
        boolean anyCriticalIncomplete = domains.stream()
                .anyMatch(d -> d.criticalGate() && (d.status() == DomainStatus.PARTIAL || d.status() == DomainStatus.NOT_MEASURED));
        if (anyCriticalFailed) {
            return PreparationStatus.NOT_READY;
        }
        if (anyCriticalIncomplete || overallPercent == null) {
            return PreparationStatus.INSUFFICIENT_DATA;
        }
        return overallPercent >= thresholdPercent ? PreparationStatus.READY : PreparationStatus.NOT_READY;
    }

    @Override
    public byte[] profileFingerprint() {
        return profileFingerprint.clone();
    }

    /**
     * Critical domains that are not PASS, in id order - derived, so it can't disagree with the
     * domains.
     */
    public List<String> blockingCriticalDomainIds() {
        return domains.stream()
                .filter(d -> d.criticalGate() && d.status() != DomainStatus.PASS)
                .map(DomainSnapshot::domainId)
                .toList();
    }

    public boolean comparableWith(PreparationSnapshot other) {
        return profileId.equals(other.profileId) && scoringVersion == other.scoringVersion
                && Arrays.equals(profileFingerprint, other.profileFingerprint);
    }

    @Override
    public MessageType type() {
        return MessageType.PREPARATION_SNAPSHOT;
    }

    @Override
    public void validateAgainst(EnvelopeHeader header) {
        if (capturedAt.isAfter(header.createdAt())) {
            throw new IllegalArgumentException("A snapshot cannot be captured after its envelope was created.");
        }
    }

    @Override
    public byte[] encodeBody() {
        CanonicalWriter writer = new CanonicalWriter()
                .string(profileId, ProtocolText.MAX_IDENTIFIER_BYTES)
                .fixed(profileFingerprint, FINGERPRINT_LENGTH)
                .u16(scoringVersion)
                .u8(overallThresholdPercent)
                .i64(capturedAt.toEpochMilli())
                .optionalU8(overallPercent)
                .u8(coveragePercent)
                .u8(status.code());
        writer.list(domains, MAX_DOMAINS, (w, domain) -> domain.writeTo(w));
        return writer.toByteArray();
    }

    static PreparationSnapshot readFrom(CanonicalReader reader) {
        return new PreparationSnapshot(
                reader.string(ProtocolText.MAX_IDENTIFIER_BYTES),
                reader.fixed(FINGERPRINT_LENGTH),
                reader.u16(),
                reader.u8(),
                ProtocolTime.fromWireMillis(reader.i64(), "capturedAt"),
                reader.optionalU8(),
                reader.u8(),
                WireCode.fromCode(PreparationStatus.class, reader.u8()),
                reader.list(MAX_DOMAINS, DomainSnapshot::readFrom));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PreparationSnapshot that
                && profileId.equals(that.profileId) && Arrays.equals(profileFingerprint, that.profileFingerprint)
                && scoringVersion == that.scoringVersion && overallThresholdPercent == that.overallThresholdPercent
                && capturedAt.equals(that.capturedAt) && Objects.equals(overallPercent, that.overallPercent)
                && coveragePercent == that.coveragePercent && status == that.status && domains.equals(that.domains);
    }

    @Override
    public int hashCode() {
        return Objects.hash(profileId, Arrays.hashCode(profileFingerprint), scoringVersion, capturedAt, status, domains);
    }

    @Override
    public String toString() {
        return "PreparationSnapshot[" + profileId + ", " + status + ", coverage=" + coveragePercent + "%]";
    }
}

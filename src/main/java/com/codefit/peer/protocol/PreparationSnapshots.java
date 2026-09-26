package com.codefit.peer.protocol;

import com.codefit.model.InterviewPreparationProfile;
import com.codefit.service.InterviewDomainReadiness;
import com.codefit.service.InterviewReadinessResult;

import java.time.Instant;
import java.util.Comparator;

/**
 * Adapter from an existing {@link InterviewReadinessResult} to a {@link PreparationSnapshot}. It reads
 * nothing from the database and changes nothing in the readiness engine: it copies the already computed
 * scores, coverage, and statuses, keeps only ids, and fails loudly if the result could not be
 * represented faithfully (for example if the scoring rules drift from {@link #SCORING_VERSION}).
 */
public final class PreparationSnapshots {

    /**
     * Version of the {@code InterviewReadinessService} scoring rules this adapter mirrors. Bump it (and
     * {@link PreparationSnapshot#deriveStatus}) whenever the readiness formula or gates change.
     */
    public static final int SCORING_VERSION = 1;

    private PreparationSnapshots() {
    }

    public static PreparationSnapshot fromReadiness(InterviewPreparationProfile profile, InterviewReadinessResult result,
                                                    int overallThresholdPercent, Instant capturedAt) {
        if (!profile.getId().equals(result.profileId())) {
            throw new IllegalArgumentException("Readiness result belongs to '" + result.profileId()
                    + "', not '" + profile.getId() + "'.");
        }
        var domains = result.domains().stream()
                .sorted(Comparator.comparing(InterviewDomainReadiness::domainId))
                .map(d -> new DomainSnapshot(d.domainId(), d.weightPercent(), d.criticalGate(),
                        d.minimumReadinessThresholdPercent(), d.scorePercent(), d.coveragePercent(),
                        d.measuredRequirementCount(), d.totalRequirementCount(), DomainStatus.valueOf(d.status().name())))
                .toList();
        PreparationSnapshot snapshot = new PreparationSnapshot(profile.getId(), PreparationProfileFingerprint.of(profile),
                SCORING_VERSION, overallThresholdPercent, capturedAt, result.overallReadinessPercent(),
                result.coveragePercent(), PreparationStatus.valueOf(result.status().name()), domains);
        if (!snapshot.blockingCriticalDomainIds().equals(result.blockingCriticalDomainIds().stream().sorted().toList())) {
            throw new IllegalStateException("Blocking critical domains changed during snapshotting.");
        }
        return snapshot;
    }
}

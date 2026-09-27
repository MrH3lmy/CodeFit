package com.codefit.peer.protocol;

import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;

import java.util.Comparator;
import java.util.List;

/**
 * SHA-256 over the scoring-relevant definition of an {@link InterviewPreparationProfile}: the profile
 * id; for each domain, <em>in profile order</em> (the floating-point aggregation depends on that
 * order), its id, weight, critical flag, and threshold; for each requirement (sorted by id, since
 * domain scores are order-independent integer averages), its id, AVAILABLE/PLANNED status, and
 * material reference. Titles and descriptions are excluded - they
 * change presentation, not comparability. Any change that could change a score changes the fingerprint,
 * so "73% on Revolut Java" is only compared with a peer's snapshot of the identical definition.
 *
 * <p>The fingerprint reveals only equality of definitions to peers. Profiles are bundled CodeFit
 * content, so a peer who has the same build can recognise which definition it is; that is intended.
 */
public final class PreparationProfileFingerprint {
    private static final int MAX_KEY_BYTES = 1024;

    private PreparationProfileFingerprint() {
    }

    public static byte[] of(InterviewPreparationProfile profile) {
        CanonicalWriter writer = new CanonicalWriter().string(profile.getId(), MAX_KEY_BYTES);
        List<InterviewDomain> domains = profile.getDomains();
        writer.list(domains, 0xFFFF, (w, domain) -> {
            w.string(domain.getId(), MAX_KEY_BYTES)
                    .u8(domain.getWeightPercent())
                    .bool(domain.isCriticalGate())
                    .optionalU8(domain.getMinimumReadinessThresholdPercent());
            List<InterviewRequirement> requirements = domain.getRequirements().stream()
                    .sorted(Comparator.comparing(InterviewRequirement::getId)).toList();
            w.list(requirements, 0xFFFF, (rw, requirement) -> {
                rw.string(requirement.getId(), MAX_KEY_BYTES).string(requirement.getStatus().name(), MAX_KEY_BYTES);
                boolean hasReference = requirement.getReference() != null;
                rw.bool(hasReference);
                if (hasReference) {
                    rw.string(requirement.getReference().type().name(), MAX_KEY_BYTES)
                            .string(requirement.getReference().key(), MAX_KEY_BYTES);
                }
            });
        });
        return ProtocolBytes.sha256(ProtocolVersion.PROFILE_FINGERPRINT_CONTEXT, writer.toByteArray());
    }
}

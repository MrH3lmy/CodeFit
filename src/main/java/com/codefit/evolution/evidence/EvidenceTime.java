package com.codefit.evolution.evidence;

import com.codefit.evolution.evidence.EvidenceTypes.TimeBasis;

import java.time.Instant;
import java.util.Objects;

/**
 * Original timestamp plus an explicit interpretation; unknown event time is null, never epoch zero.
 */
public record EvidenceTime(String original, Instant observedAt, TimeBasis basis) {
    public EvidenceTime {
        Objects.requireNonNull(basis, "Timestamp basis is required");
        if (original != null && original.length() > 80)
            throw new IllegalArgumentException("Timestamp is too long");
        boolean known = basis == TimeBasis.EXACT_UTC || basis == TimeBasis.LEGACY_SQLITE_UTC;
        if (known != (observedAt != null))
            throw new IllegalArgumentException("Timestamp/basis disagree");
        if (known && original == null)
            throw new IllegalArgumentException("Known time requires its original timestamp");
    }
}

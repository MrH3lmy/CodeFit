package com.codefit.evolution.evidence;

import com.codefit.evolution.evidence.EvidenceTypes.SourceType;

import java.util.Objects;

public record EvidenceSourceRef(SourceType type, long recordId, int revision) {
    public EvidenceSourceRef {
        Objects.requireNonNull(type, "Evidence source type is required");
        if (recordId < 1 || revision < 1)
            throw new IllegalArgumentException("Source ID/revision must be positive");
    }

    public String eventKind() {
        return type.eventKind();
    }
}

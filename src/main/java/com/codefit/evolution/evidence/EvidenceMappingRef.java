package com.codefit.evolution.evidence;

public record EvidenceMappingRef(String mappingId, int version) {
    public EvidenceMappingRef {
        if (mappingId == null
                || !mappingId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*")
                || version < 1) {
            throw new IllegalArgumentException("Mapping requires stable ID and positive version");
        }
    }
}

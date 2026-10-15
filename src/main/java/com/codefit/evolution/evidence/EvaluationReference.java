package com.codefit.evolution.evidence;

import java.util.HashSet;
import java.util.List;

/** Metadata pointers only. #214 owns durable evaluation/artifact storage, execution and grading. */
public record EvaluationReference(
        String submissionId,
        String familyId,
        String rubricVersion,
        String evaluatorVersion,
        String artifactSha256,
        List<String> checkIds,
        Long problemAttemptId) {
    public EvaluationReference {
        for (String tag : List.of(submissionId, familyId, rubricVersion, evaluatorVersion))
            requireTag(tag);
        if (artifactSha256 == null || !artifactSha256.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact artifact SHA-256 required");
        checkIds = List.copyOf(checkIds).stream().sorted().toList();
        if (checkIds.isEmpty()
                || checkIds.size() > 64
                || new HashSet<>(checkIds).size() != checkIds.size()) {
            throw new IllegalArgumentException(
                    "Require 1..64 distinct check IDs for one submission observation");
        }
        checkIds.forEach(EvaluationReference::requireTag);
        if (problemAttemptId != null && problemAttemptId < 1)
            throw new IllegalArgumentException("Invalid linked attempt ID");
    }

    static void requireTag(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
            throw new IllegalArgumentException("Invalid bounded metadata tag");
    }
}

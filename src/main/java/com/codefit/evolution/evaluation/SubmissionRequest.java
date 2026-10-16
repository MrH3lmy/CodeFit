package com.codefit.evolution.evaluation;

import static com.codefit.evolution.evaluation.EvaluationTypes.*;

import java.util.Objects;

/**
 * A deliberate command. Replays use its original bytes, or resume(id), never today's edited draft.
 */
public record SubmissionRequest(
        String id,
        String presentationId,
        String artifact,
        OutsideAssistance outsideAssistance,
        String reflection) {
    public SubmissionRequest {
        identity(id);
        identity(presentationId);
        Objects.requireNonNull(artifact);
        Objects.requireNonNull(outsideAssistance);
        Objects.requireNonNull(reflection);
        if (!java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(artifact)
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(reflection))
            throw new IllegalArgumentException("Valid UTF-8 artifact/reflection required");
        if (artifact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 200000
                || reflection.length() > 16000)
            throw new IllegalArgumentException("Bounded local artifact/reflection required");
    }

    public static void identity(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
            throw new IllegalArgumentException("Bounded stable submission identity required");
    }
}

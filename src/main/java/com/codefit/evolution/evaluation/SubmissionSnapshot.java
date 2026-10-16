package com.codefit.evolution.evaluation;

import com.codefit.evolution.quest.*;
import com.codefit.evolution.quest.ChallengePresentation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable private source/context snapshot. Bundled definitions remain authoritative definitions.
 */
public record SubmissionSnapshot(
        SubmissionRequest request,
        ChallengePresentation presentation,
        String questManifest,
        String challengeManifest,
        Long problemId,
        Long assessmentItemId,
        ExposureContext exposureAtSubmission,
        int priorFamilySubmissions,
        int priorVariantSubmissions,
        Instant submittedAt,
        String evaluatorVersion,
        RunnerProfile profile,
        String executableSource,
        Integer readingSeconds,
        Integer thinkingSeconds,
        Integer codingSeconds,
        Integer debuggingSeconds,
        com.codefit.model.HintLevel legacyHint) {
    public SubmissionSnapshot {
        Objects.requireNonNull(request);
        Objects.requireNonNull(presentation);
        Objects.requireNonNull(exposureAtSubmission);
        Objects.requireNonNull(submittedAt);
        Objects.requireNonNull(profile);
        Objects.requireNonNull(executableSource);
        if (!request.presentationId().equals(presentation.id())
                || (problemId == null) == (assessmentItemId == null)
                || priorFamilySubmissions < 0
                || priorVariantSubmissions < 0
                || priorVariantSubmissions > priorFamilySubmissions)
            throw new IllegalArgumentException("Invalid submission context");
        SubmissionRequest.identity(evaluatorVersion);
        var spec = CatalogCodec.challenge(challengeManifest);
        var quest = CatalogCodec.quest(questManifest);
        if (!spec.ref().equals(presentation.challenge())
                || !quest.ref().equals(presentation.quest())
                || !quest.challenges().contains(spec.ref())
                || !spec.familyId().equals(presentation.familyId())
                || !spec.variantId().equals(presentation.variantId())
                || spec.cohort() != presentation.cohort()
                || quest.cohort() != spec.cohort())
            throw new IllegalArgumentException("Mismatched frozen catalog context");
        if (executableSource.getBytes(StandardCharsets.UTF_8).length > 400000)
            throw new IllegalArgumentException("Bounded assembled source required");
        for (Integer n :
                new Integer[] {readingSeconds, thinkingSeconds, codingSeconds, debuggingSeconds})
            if (n != null && n < 0) throw new IllegalArgumentException("Negative phase duration");
    }

    public ChallengeSpec challenge() {
        return CatalogCodec.challenge(challengeManifest);
    }

    public String artifactSha256() {
        return CatalogCodec.digest(request.artifact());
    }

    public String executableSha256() {
        return CatalogCodec.digest(executableSource);
    }

    public String manifestSha256() {
        return CatalogCodec.digest(challengeManifest);
    }

    public String rubricSha256() {
        return CatalogCodec.digest(CatalogCodec.encode(challenge().rubric()));
    }
}

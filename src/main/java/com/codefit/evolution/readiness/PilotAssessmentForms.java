package com.codefit.evolution.readiness;

import com.codefit.evolution.quest.*;

/** Author/reviewer assets only. Not seeded, assigned, presented, exported or graded by the app. */
public final class PilotAssessmentForms {
    public static final String VERSION = "java-readiness-v1";
    public static final String MANIFEST =
            "evolution/packs/java-debugging-concurrency/readiness-v1/baseline-capacity/challenge.properties";
    public static final String MANIFEST_SHA256 =
            "20e2bdee8d5bd2e4073271448954f3b35d33f11ac2a2feefa065b0d642555f75";

    private PilotAssessmentForms() {}

    public static ChallengeSpec baselineCapacity() {
        var assets = new BundledAssets();
        String manifest = assets.manifest(MANIFEST);
        if (!CatalogCodec.digest(manifest).equals(MANIFEST_SHA256))
            throw new IllegalStateException(
                    "Unpublished readiness manifest changed without a version");
        var challenge = CatalogCodec.challenge(manifest);
        assets.validate(challenge);
        return challenge;
    }
}

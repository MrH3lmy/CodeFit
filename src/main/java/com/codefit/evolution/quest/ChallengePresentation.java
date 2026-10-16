package com.codefit.evolution.quest;

import static com.codefit.evolution.quest.QuestTypes.*;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable presentation receipt. FIRST_PRESENTATION means first on this database, never proven
 * unseen.
 */
public record ChallengePresentation(
        String id,
        CatalogRef quest,
        CatalogRef challenge,
        String familyId,
        String variantId,
        Cohort cohort,
        Instant presentedAt,
        Familiarity declaration,
        ExposureContext prior) {
    public record ExposureContext(
            int familyPresentations,
            int variantPresentations,
            PresentationKind kind,
            int highestHint,
            boolean solutionRevealed,
            boolean knownFamiliar) {}

    public record Request(
            String id,
            CatalogRef quest,
            CatalogRef challenge,
            Cohort cohort,
            Familiarity declaration) {
        public Request {
            identity(id);
            Objects.requireNonNull(quest);
            Objects.requireNonNull(challenge);
            Objects.requireNonNull(cohort);
            Objects.requireNonNull(declaration);
        }
    }

    public static void identity(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_.:-]{0,127}"))
            throw new IllegalArgumentException("Bounded command identity required");
    }
}

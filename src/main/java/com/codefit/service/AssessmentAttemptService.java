package com.codefit.service;

import com.codefit.evolution.quest.CatalogCodec;
import com.codefit.model.AssessmentAttempt;
import com.codefit.model.AssessmentAttemptFacts;
import com.codefit.model.AssessmentItem;
import com.codefit.model.AssessmentVariant;
import com.codefit.repository.AssessmentAttemptFactsRepository;
import com.codefit.repository.AssessmentAttemptRepository;
import com.codefit.repository.QuestCatalogRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Records a graded transfer-assessment attempt in the assessment bank's own table only. This is the
 * single write path for assessment results, and it deliberately never touches {@code
 * FlashcardRepository} or {@code ReviewHistoryRepository} — assessment results must not silently
 * alter a normal card's schedule or history (#104). If a future feature wants assessment evidence
 * to influence a specific card's interval, that must be an explicit, separately reviewed, opt-in
 * mapping, not a side effect of this method.
 */
public class AssessmentAttemptService {
    private final AssessmentAttemptRepository assessmentAttemptRepository =
            new AssessmentAttemptRepository();

    public AssessmentAttempt recordAttempt(
            AssessmentItem item,
            AssessmentVariant variant,
            boolean correct,
            String submittedAnswer,
            Integer responseTimeMs,
            String runId) {
        AssessmentAttempt attempt =
                new AssessmentAttempt(
                        0,
                        item.getId(),
                        variant.variantIndex(),
                        item.getSkillCategory(),
                        item.getModuleName(),
                        correct,
                        submittedAnswer,
                        responseTimeMs,
                        LocalDateTime.now(),
                        runId);
        return assessmentAttemptRepository.save(attempt);
    }

    /** The controller freezes these facts before revealing feedback for the submitted answer. */
    public AssessmentAttempt recordGradedAttempt(
            AssessmentItem item,
            AssessmentVariant variant,
            boolean correct,
            String answer,
            Integer responseMs,
            String runId,
            AssessmentGradingService.GradingResult grade,
            String presentationId,
            Instant presentedAt,
            boolean hintUsed,
            boolean solutionRevealed) {
        com.codefit.evolution.quest.ChallengePresentation.identity(presentationId);
        if (!item.getVariants().contains(variant))
            throw new IllegalArgumentException("Exact assessed variant required");
        if (!grade.needsSelfRating() && correct != grade.isCorrect())
            throw new IllegalArgumentException("Grade and result disagree");
        Instant submittedAt = Instant.now();
        return new QuestCatalogRepository()
                .transaction(
                        c -> {
                            // Dedicated Evolution assessment sources belong exclusively to their
                            // evaluator.
                            try (var s =
                                    c.prepareStatement(
                                            "SELECT 1 FROM evolution_assessment_reservations WHERE"
                                                    + " item_id=?")) {
                                s.setLong(1, item.getId());
                                try (var r = s.executeQuery()) {
                                    if (r.next())
                                        throw new IllegalArgumentException(
                                                "Reserved Evolution item");
                                }
                            }
                            int prior;
                            try (var s =
                                    c.prepareStatement(
                                            "SELECT COUNT(*) FROM assessment_attempts WHERE"
                                                    + " assessment_item_id=?")) {
                                s.setLong(1, item.getId());
                                try (var r = s.executeQuery()) {
                                    r.next();
                                    prior = r.getInt(1);
                                }
                            }
                            var attempt =
                                    assessmentAttemptRepository.save(
                                            c,
                                            new AssessmentAttempt(
                                                    0,
                                                    item.getId(),
                                                    variant.variantIndex(),
                                                    item.getSkillCategory(),
                                                    item.getModuleName(),
                                                    correct,
                                                    answer,
                                                    responseMs,
                                                    LocalDateTime.ofInstant(
                                                            submittedAt, ZoneOffset.UTC),
                                                    runId));
                            String digest = variantDigest(item, variant);
                            String kind =
                                    grade.needsSelfRating()
                                            ? "SELF_RATED"
                                            : grade.objective() ? "OBJECTIVE_ITEM" : "UNAVAILABLE";
                            new AssessmentAttemptFactsRepository()
                                    .append(
                                            c,
                                            attempt.id(),
                                            new AssessmentAttemptFacts(
                                                    kind,
                                                    grade.validator(),
                                                    null,
                                                    variant.id(),
                                                    variant.variantIndex(),
                                                    digest,
                                                    presentationId,
                                                    presentedAt,
                                                    submittedAt,
                                                    hintUsed,
                                                    solutionRevealed,
                                                    prior,
                                                    prior > 0 ? "REPEAT" : "UNKNOWN"));
                            return attempt;
                        });
    }

    private static String variantDigest(AssessmentItem item, AssessmentVariant variant) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            var out = new java.io.DataOutputStream(bytes);
            out.writeInt(1);
            out.writeLong(variant.id());
            out.writeInt(variant.variantIndex());
            out.writeUTF(item.getCardType().name());
            out.writeUTF(item.getValidationMode().name());
            for (String value :
                    new String[] {
                        variant.scenario(),
                        variant.acceptedAnswers(),
                        variant.referenceAnswer(),
                        variant.simulatedOutput(),
                        variant.hint()
                    }) {
                out.writeBoolean(value != null);
                if (value != null) {
                    byte[] text = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.writeInt(text.length);
                    out.write(text);
                }
            }
            out.flush();
            return CatalogCodec.digest(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

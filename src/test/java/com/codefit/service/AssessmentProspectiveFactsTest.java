package com.codefit.service;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.config.DatabaseConfig;
import com.codefit.evolution.evidence.*;
import com.codefit.model.*;
import com.codefit.repository.*;
import com.codefit.testsupport.IsolatedDatabaseExtension;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.sql.*;
import java.time.*;
import java.util.*;

@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class AssessmentProspectiveFactsTest {
    private final AssessmentAttemptService service = new AssessmentAttemptService();

    AssessmentItem item(CardType type) {
        return new AssessmentItemRepository()
                .findAll().stream()
                        .filter(i -> i.getCardType() == type)
                        .findFirst()
                        .orElseGet(
                                () ->
                                        new AssessmentItemRepository()
                                                .save(
                                                        "Concurrency",
                                                        "Prospective fixture",
                                                        type,
                                                        ValidationMode.EXACT,
                                                        List.of(
                                                                new AssessmentItemRepository
                                                                        .VariantInput(
                                                                        "Original fixture",
                                                                        AcceptedAnswerCodec.encode(
                                                                                List.of("42")),
                                                                        "42",
                                                                        null,
                                                                        "Think about the trace"))));
    }

    EvidenceObservation source(long id) throws Exception {
        try (var c = DatabaseConfig.getConnection()) {
            return new LearningEvidenceSourceRepository()
                    .find(
                            c,
                            new EvidenceSourceRef(
                                    EvidenceTypes.SourceType.ASSESSMENT_ATTEMPT, id, 1))
                    .orElseThrow()
                    .observation();
        }
    }

    @Test
    void historicalBooleanRemainsLegacyAndProspectiveSelfRatingStaysSubjective() throws Exception {
        var item = item(CardType.CONCEPT);
        var variant = item.getVariants().getFirst();
        var historical = service.recordAttempt(item, variant, true, "historical", 20, "old");
        assertTrue(new AssessmentAttemptFactsRepository().find(historical.id()).isEmpty());
        assertEquals(
                EvidenceTypes.Provenance.LEGACY_UNSPECIFIED, source(historical.id()).provenance());
        var grade =
                AssessmentGradingService.grade(
                        item.getCardType(), item.getValidationMode(), variant, "new reflection");
        assertTrue(grade.needsSelfRating());
        Instant shown = Instant.now().minusSeconds(5);
        var attempt =
                service.recordGradedAttempt(
                        item,
                        variant,
                        true,
                        "new reflection",
                        500,
                        "run",
                        grade,
                        "prospective-concept",
                        shown,
                        true,
                        true);
        var facts = new AssessmentAttemptFactsRepository().find(attempt.id()).orElseThrow();
        assertEquals("SELF_RATED", facts.gradeKind());
        assertNull(facts.gradingVersion());
        assertEquals(variant.id(), facts.variantId());
        assertEquals(shown, facts.presentedAt());
        assertTrue(facts.hintUsed());
        assertTrue(facts.solutionRevealed());
        assertTrue(facts.priorItemAttempts() >= 1);
        var observation = source(attempt.id());
        assertEquals(EvidenceTypes.Provenance.SELF_REPORTED, observation.provenance());
        assertEquals(EvidenceTypes.Assistance.EXPLANATION_OR_SOLUTION, observation.assistance());
        assertEquals(facts.submittedAt(), observation.time().observedAt());
        assertEquals(EvidenceTypes.TimeBasis.EXACT_UTC, observation.time().basis());
        assertEquals(
                EvidenceTypes.Provenance.LEGACY_UNSPECIFIED, source(historical.id()).provenance());
    }

    @Test
    void subjectiveJavaBooleanIsNeverMachineVerified() throws Exception {
        var item = item(CardType.JAVA_CODE);
        var variant = item.getVariants().getFirst();
        var grade =
                AssessmentGradingService.grade(
                        item.getCardType(), item.getValidationMode(), variant, "class MyAnswer {}");
        assertTrue(grade.needsSelfRating());
        var a =
                service.recordGradedAttempt(
                        item,
                        variant,
                        true,
                        "class MyAnswer {}",
                        10,
                        "java",
                        grade,
                        "java-subjective",
                        Instant.now().minusSeconds(1),
                        false,
                        true);
        assertEquals(EvidenceTypes.Provenance.SELF_REPORTED, source(a.id()).provenance());
        assertEquals(
                "SELF_RATED",
                new AssessmentAttemptFactsRepository().find(a.id()).orElseThrow().gradeKind());
    }

    @Test
    void objectiveItemHasOnlyReviewScopeAndDoesNotChangeSchedules() throws Exception {
        var item = item(CardType.CODE_OUTPUT);
        var variant = item.getVariants().getFirst();
        var grade =
                AssessmentGradingService.grade(
                        item.getCardType(), item.getValidationMode(), variant, "incorrect answer");
        assertTrue(grade.objective());
        long reviews = count("review_history"), cards = count("flashcards");
        var a =
                service.recordGradedAttempt(
                        item,
                        variant,
                        grade.isCorrect(),
                        "incorrect answer",
                        10,
                        "objective",
                        grade,
                        "objective-item",
                        Instant.now().minusSeconds(2),
                        false,
                        false);
        var facts = new AssessmentAttemptFactsRepository().find(a.id()).orElseThrow();
        assertEquals("AnswerValidator", facts.validator());
        assertEquals(EvidenceTypes.Provenance.OBJECTIVE_REVIEW, source(a.id()).provenance());
        assertNotEquals(EvidenceTypes.Assistance.NONE_OBSERVED, source(a.id()).assistance());
        assertEquals(EvidenceTypes.Exposure.UNKNOWN, source(a.id()).exposure());
        assertEquals(reviews, count("review_history"));
        assertEquals(cards, count("flashcards"));
    }

    @Test
    void factsFailureRollsBackTheAttemptAndLeavesLegacyWriterUsable() throws Exception {
        var item = item(CardType.CONCEPT);
        var variant = item.getVariants().getFirst();
        var grade =
                AssessmentGradingService.grade(
                        item.getCardType(), item.getValidationMode(), variant, "self rating");
        long attempts = count("assessment_attempts");
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        service.recordGradedAttempt(
                                item,
                                variant,
                                true,
                                "answer",
                                10,
                                "run",
                                grade,
                                "future-presentation",
                                Instant.now().plusSeconds(60),
                                false,
                                true));
        assertEquals(attempts, count("assessment_attempts"));
        assertNotNull(
                service.recordAttempt(item, variant, false, "legacy compatible", 5, "legacy"));
    }

    long count(String table) throws SQLException {
        try (var c = DatabaseConfig.getConnection();
                var s = c.createStatement();
                var r = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void callerCannotUpgradeHistoricalBooleanByFabricatingObjectiveProvenance() throws Exception {
        var item = item(CardType.CONCEPT);
        var attempt =
                service.recordAttempt(
                        item, item.getVariants().getFirst(), true, "legacy", 10, "old");
        var old = source(attempt.id());
        var forged =
                new EvidenceObservation(
                        old.source(),
                        old.time(),
                        EvidenceTypes.Provenance.OBJECTIVE_REVIEW,
                        old.outcome(),
                        old.assistance(),
                        old.hintLevel(),
                        old.exposure(),
                        old.limitations(),
                        old.availability(),
                        old.resultCode(),
                        old.originCode(),
                        old.correlationKey(),
                        null,
                        old.change(),
                        null,
                        old.reason(),
                        old.actor());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new EvidenceRepository()
                                .append(
                                        forged,
                                        EvidenceMappingRepository.INITIAL_MAPPING,
                                        item.getSkillCategory()));
        assertEquals(
                EvidenceTypes.Provenance.LEGACY_UNSPECIFIED, source(attempt.id()).provenance());
    }
}

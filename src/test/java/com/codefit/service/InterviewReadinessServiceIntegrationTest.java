package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.model.InterviewDomain;
import com.codefit.model.InterviewPreparationProfile;
import com.codefit.model.InterviewRequirement;
import com.codefit.repository.DeckRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link InterviewReadinessService#calculate(String)} end-to-end against a real database
 * and the real Revolut profile (#178 Slice 2), complementing {@link InterviewReadinessServiceTest}'s
 * database-free aggregation-logic tests.
 *
 * <p>{@code DatabaseConfig} seeds real content into every database on first run: {@code
 * seedStarterContent} creates the eight Java BE decks (including "Java BE 06 - Testing with
 * JUnit/Mockito", one of this profile's Live Coding/DSA requirements) with real, never-reviewed
 * starter cards, and {@code seedStageAPilotGuidance} (#171) seeds ten pilot roadmap problems with no
 * attempts. A never-reviewed real card is a genuine 0% durable-mastery score (the same semantic
 * {@code MasteryService}/{@code SyllabusService} already use everywhere else), so Live Coding/DSA is
 * legitimately measurable-and-failing on a fresh install - that is real signal, not a fabrication.
 * What {@link ProblemSolvingInterviewReadinessResolver} fixes is that the <em>problem-solving-system</em>
 * requirement specifically stays unmeasurable until real attempts exist, instead of the old behavior
 * where the seeded-but-unattempted roadmap alone produced a fabricated 0%. The tests below assert
 * that distinction directly.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}. Method
 * order matters: the "fresh install" assertion (which depends on no JCIP deck existing yet) must run
 * before the test that seeds one into this class's shared isolated database, hence the explicit
 * {@link Order}.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InterviewReadinessServiceIntegrationTest {

    private final InterviewReadinessService interviewReadinessService = new InterviewReadinessService();
    private final DeckRepository deckRepository = new DeckRepository();
    private final FlashcardService flashcardService = new FlashcardService();

    @Test
    @Order(1)
    void unknownProfileIdReturnsEmpty() {
        assertEquals(Optional.empty(), interviewReadinessService.calculate("does-not-exist"));
    }

    @Test
    @Order(2)
    void freshInstallsProblemSolvingRequirementStaysUnmeasuredEvenThoughLiveCodingFailsFromRealDeckMastery() {
        Optional<InterviewReadinessResult> result = interviewReadinessService.calculate("revolut-java-senior-swe");

        assertTrue(result.isPresent());
        InterviewReadinessResult readiness = result.get();
        assertEquals("revolut-java-senior-swe", readiness.profileId());
        assertEquals(8, readiness.domains().size());

        InterviewDomainReadiness liveCoding = domain(readiness, "live-java-coding-dsa-testing");
        InterviewRequirementReadiness problemSolvingRequirement = requirement(liveCoding, "problem-solving-system");
        assertFalse(problemSolvingRequirement.measurable(),
                "no real problem-solving attempts exist yet, so the seeded-but-unattempted pilot roadmap "
                        + "must not fabricate a score for this requirement");

        InterviewRequirementReadiness javaBe06Requirement = requirement(liveCoding, "java-be-06-testing");
        assertTrue(javaBe06Requirement.measurable(),
                "DatabaseConfig seeds the Java BE 06 deck with real never-reviewed starter cards, "
                        + "so this is genuinely measurable (a real 0%), independent of the problem-solving fix");
        assertEquals(0, javaBe06Requirement.scorePercent());

        assertTrue(liveCoding.isMeasured(), "the domain is measured via the real deck signal above, not via roadmap completion");
        assertEquals(InterviewDomainReadinessStatus.FAIL, liveCoding.status());

        InterviewDomainReadiness concurrency = domain(readiness, "java-concurrency-jmm");
        assertFalse(concurrency.isMeasured(), "no JCIP decks exist yet on a fresh install");
        assertEquals(InterviewDomainReadinessStatus.NOT_MEASURED, concurrency.status());

        assertEquals(InterviewReadinessStatus.NOT_READY, readiness.status(),
                "a real (deck-mastery-based) failing critical gate correctly forces NOT_READY");
    }

    @Test
    @Order(3)
    void seedingOneCriticalDeckWithRealMasteryAddsASecondRealFailingCriticalGate() {
        Deck jcip01 = deckRepository.save(new Deck("JCIP 01 - Fundamentals", "concurrency fundamentals"));
        flashcardService.addCard(jcip01.getId(), "TEST-FIXTURE: happens-before definition", "answer");

        InterviewReadinessResult readiness = interviewReadinessService.calculate("revolut-java-senior-swe").orElseThrow();

        InterviewDomainReadiness concurrencyDomain = domain(readiness, "java-concurrency-jmm");
        assertTrue(concurrencyDomain.isMeasured(), "JCIP 01 now has a real card, so at least one of its four requirements is measurable");
        assertEquals(0, concurrencyDomain.scorePercent(), "the single card was never reviewed, so this is a real (not fabricated) 0%");
        assertEquals(InterviewDomainReadinessStatus.FAIL, concurrencyDomain.status(), "0% is below the 70% critical-gate threshold");

        InterviewDomainReadiness systemDesignDomain = domain(readiness, "system-design");
        assertFalse(systemDesignDomain.isMeasured(), "System Design has no CodeFit material yet regardless of what was seeded above");

        assertEquals(InterviewReadinessStatus.NOT_READY, readiness.status());
        assertTrue(readiness.blockingCriticalDomainIds().contains("java-concurrency-jmm"));
        assertTrue(readiness.blockingCriticalDomainIds().contains("live-java-coding-dsa-testing"));
    }

    @Test
    @Order(4)
    void invalidProfileCannotProduceAnyReadinessResult() {
        InterviewDomain onlyDomain = new InterviewDomain("a", "Domain A", "description", 50, false, null,
                List.of(InterviewRequirement.planned("req-a", "Req A", "description")));
        InterviewPreparationProfile invalidProfile = new InterviewPreparationProfile("invalid-profile", "Invalid Profile",
                "weights do not sum to 100%", List.of(onlyDomain));
        assertFalse(invalidProfile.isValid(), "test fixture must actually be invalid (50% total, not 100%)");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> interviewReadinessService.calculate(invalidProfile));
        assertTrue(exception.getMessage().contains("100%"));
    }

    private InterviewDomainReadiness domain(InterviewReadinessResult result, String domainId) {
        return result.domains().stream()
                .filter(domain -> domain.domainId().equals(domainId))
                .findFirst()
                .orElseThrow();
    }

    private InterviewRequirementReadiness requirement(InterviewDomainReadiness domain, String requirementId) {
        return domain.requirements().stream()
                .filter(requirement -> requirement.requirementId().equals(requirementId))
                .findFirst()
                .orElseThrow();
    }
}

package com.codefit.service;

import com.codefit.model.Deck;
import com.codefit.repository.DeckRepository;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link InterviewReadinessService#calculate(String)} end-to-end against a real database
 * and the real Revolut profile (#178 Slice 2), complementing {@link InterviewReadinessServiceTest}'s
 * database-free aggregation-logic tests.
 *
 * <p>{@code DatabaseConfig} seeds a small pilot roadmap (#171, ten unsolved problems) into every real
 * database, so even a "fresh install" already gives the Live Coding/DSA critical domain a real
 * (0%) measured score via the problem-solving requirement - only the deck-backed requirements stay
 * unmeasured until a deck is created. That's why the fresh-install result below is {@code NOT_READY}
 * (a real failing critical gate), not {@code INSUFFICIENT_DATA}.
 *
 * <p>Runs against its own isolated database (#175), never the shared local {@code codefit.db}. Method
 * order matters: the "fresh install" assertion (which depends on no deck existing yet) must run
 * before the test that seeds a deck into this class's shared isolated database, hence the explicit
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
    void freshInstallReportsNotReadyFromARealFailingCriticalGateRatherThanCrashingOrFabricating() {
        Optional<InterviewReadinessResult> result = interviewReadinessService.calculate("revolut-java-senior-swe");

        assertTrue(result.isPresent());
        InterviewReadinessResult readiness = result.get();
        assertEquals("revolut-java-senior-swe", readiness.profileId());
        assertEquals(8, readiness.domains().size());

        InterviewDomainReadiness liveCoding = domain(readiness, "live-java-coding-dsa-testing");
        assertTrue(liveCoding.isMeasured(),
                "the pilot roadmap DatabaseConfig seeds into every database gives this domain a real, if 0%, score");
        assertEquals(0, liveCoding.scorePercent());
        assertEquals(InterviewDomainReadinessStatus.FAIL, liveCoding.status());

        InterviewDomainReadiness concurrency = domain(readiness, "java-concurrency-jmm");
        assertFalse(concurrency.isMeasured(), "no JCIP decks exist yet on a fresh install");
        assertEquals(InterviewDomainReadinessStatus.NOT_MEASURED, concurrency.status());

        assertEquals(InterviewReadinessStatus.NOT_READY, readiness.status(),
                "a real failing critical gate must never be softened to insufficient data just because other gates are also unmeasured");
        assertTrue(readiness.blockingCriticalDomainIds().contains("live-java-coding-dsa-testing"));
    }

    @Test
    @Order(3)
    void seedingOneCriticalDeckWithRealMasteryMovesThatDomainWhileOthersStayUnmeasured() {
        Deck jcip01 = deckRepository.save(new Deck("JCIP 01 - Fundamentals", "concurrency fundamentals"));
        flashcardService.addCard(jcip01.getId(), "TEST-FIXTURE: happens-before definition", "answer");

        InterviewReadinessResult readiness = interviewReadinessService.calculate("revolut-java-senior-swe").orElseThrow();

        InterviewDomainReadiness concurrencyDomain = domain(readiness, "java-concurrency-jmm");
        assertTrue(concurrencyDomain.isMeasured(), "JCIP 01 now has a real card, so at least one of its four requirements is measurable");
        assertEquals(0, concurrencyDomain.scorePercent(), "the single card was never reviewed, so this is a real (not fabricated) 0%");

        InterviewDomainReadiness systemDesignDomain = domain(readiness, "system-design");
        assertFalse(systemDesignDomain.isMeasured(), "System Design has no CodeFit material yet regardless of what was seeded above");

        assertEquals(InterviewReadinessStatus.NOT_READY, readiness.status(),
                "Live Coding/DSA's real failing score (from the seeded pilot roadmap) still blocks readiness on its own");
    }

    private InterviewDomainReadiness domain(InterviewReadinessResult result, String domainId) {
        return result.domains().stream()
                .filter(domain -> domain.domainId().equals(domainId))
                .findFirst()
                .orElseThrow();
    }
}

package com.codefit.service;

import com.codefit.model.CardState;
import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.ValidationMode;
import com.codefit.service.ReviewService.AdaptiveSessionPlan;
import com.codefit.service.ReviewService.CardPressure;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewServiceTest {

    private Flashcard card(long id, CardState state, LocalDate dueDate, String skillCategory) {
        Flashcard flashcard = new Flashcard(1, "front " + id, "back", CardType.RECALL, "back",
                ValidationMode.CASE_INSENSITIVE, null);
        flashcard.setId(id);
        flashcard.setCardState(state);
        flashcard.setDueDate(dueDate);
        flashcard.setSkillCategory(skillCategory);
        flashcard.setReviewCount(1);
        return flashcard;
    }

    private Flashcard due(long id) {
        return card(id, CardState.REVIEW, LocalDate.now(), "General");
    }

    private Flashcard newCard(long id) {
        return card(id, CardState.NEW, LocalDate.now(), "General");
    }

    private Flashcard newCardInDeck(long id, long deckId) {
        Flashcard flashcard = newCard(id);
        flashcard.setDeckId(deckId);
        return flashcard;
    }

    private Flashcard matureCardInDeck(long id, long deckId) {
        Flashcard flashcard = card(id, CardState.REVIEW, LocalDate.now().plusDays(10), "General");
        flashcard.setDeckId(deckId);
        return flashcard;
    }

    @Test
    void newCardIsNeverSelectedWhileAFittingDueCardIsExcluded() {
        // Two due cards (10s each) and one new card (10s), but the budget only fits one card.
        // The due card must win; the new card must not appear at all.
        List<Flashcard> due = List.of(due(1), due(2));
        List<Flashcard> newCards = List.of(newCard(100));

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, 0 /* minutes */,
                ignored -> 10);

        // 0 minutes still guarantees at least one card via the "never start empty" rule.
        assertEquals(1, plan.cards().size());
        assertEquals(1L, plan.cards().get(0).getId());
        assertFalse(plan.cards().stream().anyMatch(card -> card.getId() == 100));
    }

    @Test
    void dueCardsFillTheWholeBudgetBeforeAnyNewCardIsConsidered() {
        List<Flashcard> due = List.of(due(1), due(2), due(3));
        List<Flashcard> newCards = List.of(newCard(100), newCard(101));

        // 1 minute = 60s budget: all 3 due cards (10s each = 30s) fit, leaving 30s for new cards,
        // so both new cards (10s each = 20s) also fit.
        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, 1, ignored -> 10);

        assertEquals(5, plan.cards().size());
        assertEquals(3, plan.composition().get("Highest forgetting risk"));
        assertEquals(2, plan.composition().get("New / stretch"));
    }

    @Test
    void newCardOnlyFillsBudgetLeftOverAfterAllFittingDueCardsAreIncluded() {
        List<Flashcard> due = List.of(due(1), due(2));
        List<Flashcard> newCards = List.of(newCard(100));

        // 1 minute = 60s budget: both due cards (10s each = 20s) fit, leaving 40s of leftover
        // budget for the new card (10s), which fits comfortably.
        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, 1, ignored -> 10);

        assertEquals(3, plan.cards().size());
        assertTrue(plan.cards().stream().anyMatch(card -> card.getId() == 100));
    }

    @Test
    void leftoverBudgetTooSmallForAnyNewCardDoesNotForceAnOversizedOneIn() {
        // Regression: when due cards already fill most of the budget, the tiny leftover must
        // only be used if a new card genuinely fits it. The "never start an empty session"
        // fallback must not bleed into this top-up phase and force an oversized new card in,
        // which would silently blow past the requested time budget.
        List<Flashcard> due = List.of(due(1));
        List<Flashcard> newCards = List.of(newCard(100));

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, 1,
                card -> card.getId() == 1 ? 50 : 20);
        // 60s budget: due card (50s) fits, leaving 10s leftover; the new card (20s) does not fit
        // that leftover and must be excluded rather than force-included.

        assertEquals(1, plan.cards().size());
        assertEquals(1L, plan.cards().get(0).getId());
        assertFalse(plan.composition().containsKey("New / stretch"));
    }

    @Test
    void relearningCardsAreOrderedBeforeOtherDueCards() {
        Flashcard relearning = card(1, CardState.RELEARNING, LocalDate.now().plusDays(3), "General");
        Flashcard ordinaryDue = card(2, CardState.REVIEW, LocalDate.now().minusDays(5), "General");
        // ordinaryDue is far more overdue, but relearning must still come first.
        List<Flashcard> due = List.of(ordinaryDue, relearning);

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), List.of(), 60, ignored -> 10);

        assertEquals(1L, plan.cards().get(0).getId());
        assertTrue(plan.composition().containsKey("Recently failed"));
    }

    @Test
    void newCardsFillTheSessionWhenThereAreNoDueCards() {
        List<Flashcard> newCards = List.of(newCard(100), newCard(101), newCard(102));

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(List.of(), List.of(), newCards, 1, ignored -> 10);

        assertEquals(3, plan.cards().size());
        assertEquals(3, plan.composition().get("New / stretch"));
    }

    @Test
    void aVeryLargeDueCardDoesNotBlockSmallerDueCardsThatStillFit() {
        Flashcard huge = card(1, CardState.REVIEW, LocalDate.now().minusDays(1), "General");
        Flashcard small1 = card(2, CardState.REVIEW, LocalDate.now(), "General");
        Flashcard small2 = card(3, CardState.REVIEW, LocalDate.now(), "General");
        List<Flashcard> due = List.of(huge, small1, small2);

        // 1 minute = 60s budget; huge card alone (500s) would blow the whole budget, so it must
        // be skipped in favor of the two smaller due cards that fit comfortably.
        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), List.of(), 1,
                card -> card.getId() == 1 ? 500 : 10);

        assertEquals(List.of(2L, 3L), plan.cards().stream().map(Flashcard::getId).toList());
    }

    @Test
    void dailyNewCardLimitCapsHowManyNewCardsAreSelectedRegardlessOfCandidateCount() {
        List<Flashcard> manyCandidates = new ArrayList<>();
        for (long id = 1; id <= 10; id++) {
            manyCandidates.add(newCard(id));
        }

        List<Flashcard> selected = ReviewService.selectNewCardsMixedAcrossDecks(manyCandidates, 2);

        assertEquals(2, selected.size());
    }

    @Test
    void availableNewCardBudgetNeverExceedsRemainingDailyAllowance() {
        assertEquals(1, ReviewService.computeAvailableNewCardBudget(2, 1, 50));
        assertEquals(0, ReviewService.computeAvailableNewCardBudget(2, 2, 50));
        assertEquals(0, ReviewService.computeAvailableNewCardBudget(2, 5, 50), "already over the limit today");
    }

    @Test
    void availableNewCardBudgetIsCappedByHowManyNewCardsActuallyExist() {
        // Limit allows 2 more today, but only 1 NEW card exists in the whole collection.
        assertEquals(1, ReviewService.computeAvailableNewCardBudget(2, 0, 1));
    }

    @Test
    void suspendedCardsAreNeverIncludedInWeeklyBossCandidates() {
        Flashcard active = due(1);
        Flashcard suspended = card(2, CardState.SUSPENDED, LocalDate.now().minusDays(10), "General");

        List<Flashcard> prioritized = ReviewService.prioritizeWeeklyBossCandidates(
                List.of(active, suspended), Map.of(), LocalDate.now());

        assertEquals(List.of(1L), prioritized.stream().map(Flashcard::getId).toList());
    }

    @Test
    void suspendedGraduatedAndMasteredCardsAreAllExcludedOnlyIfSuspended() {
        // Suspension is the only state that removes a card from the weekly boss queue; GRADUATED
        // and MASTERED cards remain eligible candidates like any other non-suspended card.
        Flashcard graduated = card(1, CardState.GRADUATED, LocalDate.now().plusDays(40), "General");
        Flashcard mastered = card(2, CardState.MASTERED, LocalDate.now().plusDays(14), "General");
        Flashcard suspended = card(3, CardState.SUSPENDED, LocalDate.now(), "General");

        List<Flashcard> prioritized = ReviewService.prioritizeWeeklyBossCandidates(
                List.of(graduated, mastered, suspended), Map.of(), LocalDate.now());

        assertEquals(2, prioritized.size());
        assertFalse(prioritized.stream().anyMatch(card -> card.getId() == 3L));
    }

    @Test
    void leechCardsAreNeverIncludedInWeeklyBossCandidates() {
        // A leech is actively being worked on/rewritten, not a fair candidate for a high-pressure
        // timed assessment (issue #103).
        Flashcard active = due(1);
        Flashcard leech = card(2, CardState.LEECH, LocalDate.now().minusDays(10), "General");

        List<Flashcard> prioritized = ReviewService.prioritizeWeeklyBossCandidates(
                List.of(active, leech), Map.of(), LocalDate.now());

        assertEquals(List.of(1L), prioritized.stream().map(Flashcard::getId).toList());
    }

    @Test
    void leechCardsAreLabelledNeedsRewriteRatherThanRecentlyFailedOrWeakestSkill() {
        // The label is what session-summary UI groups by (see ReviewController); a leech must never
        // be folded into the "Recently failed"/"Weakest skill" buckets that make the priority
        // ordering keep requeuing the same card into every session.
        Flashcard leech = card(1, CardState.LEECH, LocalDate.now().minusDays(1), "General");
        List<Flashcard> due = List.of(leech);

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of("General"), List.of(), 60, ignored -> 10);

        assertEquals(1, plan.composition().get("Needs rewrite"));
        assertFalse(plan.composition().containsKey("Recently failed"));
        assertFalse(plan.composition().containsKey("Weakest skill"));
    }

    @Test
    void leechCardsAreNotBumpedAheadOfMoreOverdueDueCardsLikeRelearningIs() {
        // Unlike RELEARNING, a leech isn't force-prioritized to the front of the queue every
        // session — it falls into the normal due-date/ease ordering alongside other due cards.
        Flashcard leech = card(1, CardState.LEECH, LocalDate.now(), "General");
        Flashcard moreOverdue = card(2, CardState.REVIEW, LocalDate.now().minusDays(10), "General");
        List<Flashcard> due = List.of(leech, moreOverdue);

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), List.of(), 60, ignored -> 10);

        assertEquals(2L, plan.cards().get(0).getId(), "the more-overdue ordinary card must still come first");
    }

    @Test
    void weeklyBossCandidatesPrioritizeOverdueAndHighPressureCards() {
        Flashcard overdue = card(1, CardState.REVIEW, LocalDate.now().minusDays(5), "General");
        Flashcard notYetDue = card(2, CardState.REVIEW, LocalDate.now().plusDays(5), "General");
        CardPressure highPressure = new CardPressure();
        highPressure.reviewCount = 3;
        highPressure.againCount = 2;

        List<Flashcard> prioritized = ReviewService.prioritizeWeeklyBossCandidates(
                List.of(notYetDue, overdue), Map.of(1L, highPressure), LocalDate.now());

        assertEquals(1L, prioritized.get(0).getId());
    }

    // --- Focus-module bias and mature-card interleaving (#110) ---

    @Test
    void newCardsFavorFocusModuleWhenItHasEnoughCandidates() {
        // Deck 1 is the focus module; deck 2 is a different, non-focus module.
        List<Flashcard> candidates = List.of(
                newCardInDeck(1, 1), newCardInDeck(2, 1), newCardInDeck(3, 1),
                newCardInDeck(100, 2), newCardInDeck(101, 2));

        List<Flashcard> selected = ReviewService.selectNewCardsFavoringFocus(candidates, 2, Set.of(1L));

        assertEquals(2, selected.size());
        assertTrue(selected.stream().allMatch(card -> card.getDeckId() == 1),
                "new cards should be drawn from the focus module first while it has enough candidates");
    }

    @Test
    void newCardsFallBackToOtherModulesWhenFocusModuleRunsOut() {
        List<Flashcard> candidates = List.of(newCardInDeck(1, 1), newCardInDeck(100, 2), newCardInDeck(101, 2));

        // Focus module (deck 1) only has one candidate; the limit of 3 must still be filled from
        // the other module rather than leaving the session short on new cards.
        List<Flashcard> selected = ReviewService.selectNewCardsFavoringFocus(candidates, 3, Set.of(1L));

        assertEquals(3, selected.size());
        assertTrue(selected.stream().anyMatch(card -> card.getDeckId() == 1));
        assertTrue(selected.stream().anyMatch(card -> card.getDeckId() == 2));
    }

    @Test
    void noFocusModuleFallsBackToTheOriginalUnbiasedMix() {
        List<Flashcard> candidates = List.of(newCardInDeck(1, 1), newCardInDeck(100, 2));

        List<Flashcard> selected = ReviewService.selectNewCardsFavoringFocus(candidates, 2, Set.of());

        assertEquals(2, selected.size());
    }

    @Test
    void dueCardsFromNonFocusModulesAreNeverHiddenByFocusMode() {
        // Focus is on deck 1; a due card sits in a completely different module (deck 2). Due
        // cards aren't module-aware at all in buildAdaptiveSessionPlan, so this must still surface.
        Flashcard focusModuleDue = due(1);
        focusModuleDue.setDeckId(1);
        Flashcard otherModuleDue = due(2);
        otherModuleDue.setDeckId(2);
        List<Flashcard> due = List.of(focusModuleDue, otherModuleDue);

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), List.of(), List.of(), 15, 60, ignored -> 10);

        assertEquals(2, plan.cards().size());
        assertTrue(plan.cards().stream().anyMatch(card -> card.getDeckId() == 2),
                "a due card from a non-focus module must never be hidden by focus mode");
    }

    @Test
    void matureInterleavePercentageReservesOnlyASmallShareOfLeftoverBudget() {
        List<Flashcard> due = List.of(due(1));
        List<Flashcard> newCards = List.of(newCard(100), newCard(101), newCard(102), newCard(103));
        List<Flashcard> matureInterleave = List.of(matureCardInDeck(200, 2), matureCardInDeck(201, 2));

        // 60s budget, due card (10s) leaves 50s. 20% of 50s = 10s reserved for interleaving (one
        // 10s mature card), leaving 40s (four 10s cards) for new cards.
        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, matureInterleave, 20, 1,
                ignored -> 10);

        assertEquals(1, plan.composition().get("Interleaved review (other modules)"));
        assertEquals(4, plan.composition().get("New / stretch"));
    }

    @Test
    void zeroMatureInterleavePercentageMeansNoInterleavedCardsAppear() {
        List<Flashcard> due = List.of(due(1));
        List<Flashcard> newCards = List.of(newCard(100));
        List<Flashcard> matureInterleave = List.of(matureCardInDeck(200, 2));

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, matureInterleave, 0, 1, ignored -> 10);

        assertFalse(plan.composition().containsKey("Interleaved review (other modules)"));
        assertFalse(plan.cards().stream().anyMatch(card -> card.getDeckId() == 2));
    }

    @Test
    void higherMatureInterleavePercentageReservesMoreOfTheLeftoverBudget() {
        List<Flashcard> due = List.of(due(1));
        List<Flashcard> newCards = List.of(newCard(100), newCard(101), newCard(102), newCard(103), newCard(104));
        List<Flashcard> matureInterleave = List.of(matureCardInDeck(200, 2), matureCardInDeck(201, 2), matureCardInDeck(202, 2));

        // 60s budget, due card (10s) leaves 50s. 50% of 50s = 25s -> two 10s interleave cards fit
        // (20s used), leaving 30s (three 10s cards) for new cards.
        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, matureInterleave, 50, 1,
                ignored -> 10);

        assertEquals(2, plan.composition().get("Interleaved review (other modules)"));
        assertEquals(3, plan.composition().get("New / stretch"));
    }

    @Test
    void oldFiveArgOverloadStillBehavesExactlyAsBeforeWithNoInterleaving() {
        List<Flashcard> due = List.of(due(1), due(2), due(3));
        List<Flashcard> newCards = List.of(newCard(100), newCard(101));

        AdaptiveSessionPlan plan = ReviewService.buildAdaptiveSessionPlan(due, List.of(), newCards, 1, ignored -> 10);

        assertEquals(5, plan.cards().size());
        assertFalse(plan.composition().containsKey("Interleaved review (other modules)"));
    }
}

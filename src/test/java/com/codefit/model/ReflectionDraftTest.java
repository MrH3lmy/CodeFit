package com.codefit.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The preview screen for #102 lets the learner edit, remove, or merge generated cards before
 * anything is persisted; these are the mutation rules {@code ReflectionDraft} enforces so a
 * controller can't drift from what {@code ReflectionService.saveReflection} will actually save.
 */
class ReflectionDraftTest {

    private ReflectionDraft draft() {
        return new ReflectionDraft(ReflectionType.BUG, List.of(
                new GeneratedCard("What symptom indicated the problem?", "NPE on checkout", CardType.CONCEPT),
                new GeneratedCard("What was the root cause?", "Null customer id", CardType.CONCEPT),
                new GeneratedCard("What change fixed it?", "Added a null check", CardType.CONCEPT),
                new GeneratedCard("What prevents recurrence?", "Added a unit test", CardType.CONCEPT)
        ));
    }

    @Test
    void startsWithEveryGeneratedCard() {
        ReflectionDraft draft = draft();
        assertEquals(4, draft.size());
        assertEquals("What symptom indicated the problem?", draft.getCards().get(0).getFront());
    }

    @Test
    void editCardChangesOnlyThatCardsPromptAndAnswer() {
        ReflectionDraft draft = draft();
        draft.editCard(1, "What actually caused the bug?", "A missing null guard on customer id");

        assertEquals("What actually caused the bug?", draft.getCards().get(1).getFront());
        assertEquals("A missing null guard on customer id", draft.getCards().get(1).getBack());
        assertEquals("What symptom indicated the problem?", draft.getCards().get(0).getFront());
        assertEquals(4, draft.size());
    }

    @Test
    void removeCardDropsExactlyThatCard() {
        ReflectionDraft draft = draft();
        draft.removeCard(2);

        assertEquals(3, draft.size());
        assertEquals("What was the root cause?", draft.getCards().get(1).getFront());
        assertEquals("What prevents recurrence?", draft.getCards().get(2).getFront());
    }

    @Test
    void mergeCardsCombinesPromptsAndAnswersAndDropsTheSecondCard() {
        ReflectionDraft draft = draft();
        draft.mergeCards(1, 2);

        assertEquals(3, draft.size());
        GeneratedCard merged = draft.getCards().get(1);
        assertEquals("What was the root cause? / What change fixed it?", merged.getFront());
        assertEquals("Null customer id\nAdded a null check", merged.getBack());
    }

    @Test
    void editRemoveAndMergeRejectOutOfRangeIndexes() {
        ReflectionDraft draft = draft();
        assertThrows(IndexOutOfBoundsException.class, () -> draft.editCard(9, "front", "back"));
        assertThrows(IndexOutOfBoundsException.class, () -> draft.removeCard(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> draft.mergeCards(0, 9));
    }

    @Test
    void mergingACardWithItselfIsRejected() {
        ReflectionDraft draft = draft();
        assertThrows(IllegalArgumentException.class, () -> draft.mergeCards(1, 1));
    }

    @Test
    void getCardsIsNotDirectlyMutable() {
        ReflectionDraft draft = draft();
        assertThrows(UnsupportedOperationException.class, () -> draft.getCards().remove(0));
    }
}

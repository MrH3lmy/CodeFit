package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.Flashcard;
import com.codefit.model.GeneratedCard;
import com.codefit.model.ReflectionDraft;
import com.codefit.model.ReflectionType;
import com.codefit.model.ValidationMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a single work reflection into several atomic, independently-answerable cards instead of
 * one card that bundles the whole story (#102): a bug reflection asking for symptom, root cause,
 * fix, and prevention in one response is slow to review and hides which part the learner actually
 * knows. Every generated card answers exactly one recalled fact.
 *
 * <p>Every generated card is {@link CardType#CONCEPT} so it is self-graded and flows through the
 * normal review/scheduling system like any other card — there is no separate reflection review
 * mechanism.
 */
public class ReflectionService {
    private final FlashcardService flashcardService;
    private final ProgressService progressService;

    public ReflectionService() {
        this(new FlashcardService(), new ProgressService());
    }

    public ReflectionService(FlashcardService flashcardService, ProgressService progressService) {
        this.flashcardService = flashcardService;
        this.progressService = progressService;
    }

    public ReflectionDraft generateBugReflection(String symptom, String rootCause, String fix, String prevention) {
        requireText(symptom, "Describe the symptom that indicated the problem.");
        requireText(rootCause, "Describe the root cause.");
        requireText(fix, "Describe the change that fixed it.");
        requireText(prevention, "Describe the test, constraint, log, or checklist that prevents recurrence.");

        List<GeneratedCard> cards = List.of(
                new GeneratedCard("What symptom indicated the problem?", symptom.strip(), CardType.CONCEPT),
                new GeneratedCard("What was the root cause?", rootCause.strip(), CardType.CONCEPT),
                new GeneratedCard("What change fixed it?", fix.strip(), CardType.CONCEPT),
                new GeneratedCard("What test, constraint, log, or checklist prevents recurrence?", prevention.strip(), CardType.CONCEPT)
        );
        return new ReflectionDraft(ReflectionType.BUG, cards);
    }

    public ReflectionDraft generateCommandReflection(String wrongAttempt, String whyWrong, String correctCommand, String usageReminder) {
        requireText(wrongAttempt, "Describe what you tried that didn't work.");
        requireText(whyWrong, "Describe why it was wrong.");
        requireText(correctCommand, "Enter the correct command.");
        requireText(usageReminder, "Describe when to use it, or what will remind you next time.");

        List<GeneratedCard> cards = List.of(
                new GeneratedCard("What command (or usage) did you try that didn't work?", wrongAttempt.strip(), CardType.CONCEPT),
                new GeneratedCard("Why didn't it work?", whyWrong.strip(), CardType.CONCEPT),
                new GeneratedCard("What is the correct command?", correctCommand.strip(), CardType.CONCEPT),
                new GeneratedCard("When should you use this command, or what will remind you next time?", usageReminder.strip(), CardType.CONCEPT)
        );
        return new ReflectionDraft(ReflectionType.COMMAND, cards);
    }

    public ReflectionDraft generateMissedConceptReflection(String concept, String explanation, String missedSignal, String nextCue) {
        requireText(concept, "Name the concept you missed.");
        requireText(explanation, "Explain the concept in plain English.");
        requireText(missedSignal, "Describe what confused you.");
        requireText(nextCue, "Describe the cue that should remind you next time.");

        String topic = concept.strip();
        List<GeneratedCard> cards = List.of(
                new GeneratedCard("In plain English, what is " + topic + "?", explanation.strip(), CardType.CONCEPT),
                new GeneratedCard("What about " + topic + " initially confused you?", missedSignal.strip(), CardType.CONCEPT),
                new GeneratedCard("What cue will remind you to think about " + topic + " next time you see it?", nextCue.strip(), CardType.CONCEPT)
        );
        return new ReflectionDraft(ReflectionType.MISSED_CONCEPT, cards);
    }

    /** Generated cards whose prompt is an exact or near-exact match of a card already in the target
     *  deck, so a preview screen can warn the learner before they save a redundant card. */
    public List<GeneratedCard> findDuplicates(long deckId, ReflectionDraft draft) {
        List<GeneratedCard> duplicates = new ArrayList<>();
        for (GeneratedCard card : draft.getCards()) {
            if (flashcardService.hasNearDuplicatePromptInDeck(deckId, card.getFront())) {
                duplicates.add(card);
            }
        }
        return duplicates;
    }

    /**
     * Persists every generated card that isn't a near-duplicate of one already in the deck as a
     * regular {@link Flashcard}, then awards reflection XP exactly once for the whole reflection —
     * never once per atomic card — no matter how many cards it produced. Each card is checked (and,
     * if kept, immediately saved) before the next is checked, so two generated cards that end up
     * with the same prompt within this same reflection are caught too, not just duplicates of
     * pre-existing cards.
     */
    public ReflectionSaveResult saveReflection(long deckId, ReflectionDraft draft) {
        if (draft.getCards().isEmpty()) {
            throw new IllegalArgumentException("Add at least one card before saving.");
        }

        String skillCategory = reflectionSkillCategory(draft.getType());
        List<Flashcard> savedCards = new ArrayList<>();
        List<GeneratedCard> skippedDuplicates = new ArrayList<>();

        for (GeneratedCard generated : draft.getCards()) {
            if (flashcardService.hasNearDuplicatePromptInDeck(deckId, generated.getFront())) {
                skippedDuplicates.add(generated);
                continue;
            }
            Flashcard saved = flashcardService.addCard(deckId, generated.getFront(), generated.getBack(),
                    generated.getCardType(), generated.getBack(), ValidationMode.CASE_INSENSITIVE, null, null,
                    null, skillCategory);
            savedCards.add(saved);
        }

        int xpAwarded = savedCards.isEmpty() ? 0 : progressService.recordReflectionCardCreated();
        return new ReflectionSaveResult(savedCards, skippedDuplicates, xpAwarded);
    }

    private String reflectionSkillCategory(ReflectionType type) {
        return switch (type) {
            case BUG -> "Reflection: Bug";
            case COMMAND -> "Reflection: Command";
            case MISSED_CONCEPT -> "Reflection: Concept";
        };
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}

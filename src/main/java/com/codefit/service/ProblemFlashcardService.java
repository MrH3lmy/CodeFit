package com.codefit.service;

import com.codefit.model.CardType;
import com.codefit.model.ComplexityClass;
import com.codefit.model.Deck;
import com.codefit.model.Flashcard;
import com.codefit.model.Problem;
import com.codefit.model.ProblemProgress;
import com.codefit.model.ReflectionCardSource;
import com.codefit.model.ValidationMode;
import com.codefit.repository.DeckRepository;
import com.codefit.repository.FlashcardRepository;
import com.codefit.repository.ProblemRepository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Bridges problem-solving reflection (#146) to CodeFit's existing spaced-repetition engine (#148):
 * turns a chosen reflection field into an editable {@link ProblemFlashcardDraft}, then saves it as an
 * ordinary {@link Flashcard} (reusing {@link FlashcardService}'s validation/normalization and the
 * normal review/scheduling system — no separate card type or review path exists for these) with a
 * source link back to the problem for context and duplicate protection.
 */
public class ProblemFlashcardService {

    /** The deck offered by default when the learner hasn't chosen an existing one. */
    public static final String LESSONS_DECK_NAME = "Problem-Solving Lessons";
    private static final String LESSONS_DECK_DESCRIPTION = "Flashcards created from problem-solving reflections.";
    private static final String LESSONS_SKILL_CATEGORY = "Problem-Solving Lessons";

    private final ProblemRepository problemRepository;
    private final ProblemProgressService progressService;
    private final FlashcardService flashcardService;
    private final FlashcardRepository flashcardRepository;
    private final DeckRepository deckRepository;

    public ProblemFlashcardService() {
        this(new ProblemRepository(), new ProblemProgressService(), new FlashcardService(),
                new FlashcardRepository(), new DeckRepository());
    }

    public ProblemFlashcardService(ProblemRepository problemRepository, ProblemProgressService progressService,
                                   FlashcardService flashcardService, FlashcardRepository flashcardRepository,
                                   DeckRepository deckRepository) {
        this.problemRepository = problemRepository;
        this.progressService = progressService;
        this.flashcardService = flashcardService;
        this.flashcardRepository = flashcardRepository;
        this.deckRepository = deckRepository;
    }

    /** Builds the editable prompt/answer draft for one reflection field, pre-filled from whatever
     *  that field currently holds (blank for {@link ReflectionCardSource#EDGE_CASE}, which has no
     *  backing stored field) — never persisted until {@link #createCard} is called. */
    public ProblemFlashcardDraft buildDraft(long problemId, ReflectionCardSource source) {
        Problem problem = problemRepository.findById(problemId)
                .orElseThrow(() -> new IllegalArgumentException("No problem with id " + problemId + " exists."));
        ProblemProgress progress = progressService.getOrCreate(problemId);
        return new ProblemFlashcardDraft(problem, source, promptFor(source, problem), answerFor(source, progress));
    }

    private String promptFor(ReflectionCardSource source, Problem problem) {
        String label = "[" + problem.getExternalCode() + "] " + problem.getTitle();
        return switch (source) {
            case LESSON_LEARNED -> "What lesson did you learn from " + label + "?";
            case MISTAKE_MADE -> "What mistake did you make while solving " + label + "?";
            case KEY_OBSERVATION -> "What was the key observation in " + label + "?";
            case ALGORITHM_OR_TECHNIQUE -> "What algorithm or technique solves " + label + "?";
            case COMPLEXITY_TRADEOFF -> "What is the time/space complexity trade-off for " + label + "?";
            case EDGE_CASE -> "What edge case matters for " + label + "?";
        };
    }

    private String answerFor(ReflectionCardSource source, ProblemProgress progress) {
        return switch (source) {
            case LESSON_LEARNED -> blankToEmpty(progress.getLessonLearned());
            case MISTAKE_MADE -> blankToEmpty(progress.getMistakeNotes());
            case KEY_OBSERVATION -> blankToEmpty(progress.getImportantObservation());
            case ALGORITHM_OR_TECHNIQUE -> blankToEmpty(progress.getActualTopic());
            case COMPLEXITY_TRADEOFF -> complexityTradeoffText(progress);
            case EDGE_CASE -> "";
        };
    }

    private String complexityTradeoffText(ProblemProgress progress) {
        if (progress.getTimeComplexity() == null && progress.getSpaceComplexity() == null) {
            return "";
        }
        return "Time: " + displayComplexity(progress.getTimeComplexity()) + ", Space: " + displayComplexity(progress.getSpaceComplexity());
    }

    private String displayComplexity(ComplexityClass complexity) {
        return complexity == null ? "unknown" : complexity.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** The card, if any, already linked to this exact (problem, reflection field) pair. */
    public Optional<Flashcard> findExistingLinkedCard(long problemId, ReflectionCardSource source) {
        return flashcardRepository.findBySourceProblemIdAndReflectionField(problemId, source);
    }

    /** Every deck available to file a new lesson card into, with the dedicated lessons deck first if
     *  it already exists. Does not create the lessons deck — {@link #resolveLessonsDeckId()} does
     *  that lazily, only when the learner actually saves a card without picking another deck. */
    public List<Deck> getAvailableDecks() {
        return deckRepository.findAll();
    }

    /** Finds the dedicated "Problem-Solving Lessons" deck, creating it the first time it's needed. */
    public long resolveLessonsDeckId() {
        return deckRepository.findAll().stream()
                .filter(deck -> deck.getName().equalsIgnoreCase(LESSONS_DECK_NAME))
                .findFirst()
                .orElseGet(() -> deckRepository.save(new Deck(LESSONS_DECK_NAME, LESSONS_DECK_DESCRIPTION)))
                .getId();
    }

    /**
     * Saves {@code front}/{@code back} (already reviewed/edited by the learner) as a new
     * {@link CardType#CONCEPT} flashcard linked back to {@code problemId}/{@code source}.
     *
     * <p>By default ({@code allowDuplicate = false}) this is the duplicate-protection check itself:
     * if a card is already linked to this exact (problem, source) pair, no new card is created and
     * the existing one is returned instead (see {@link ProblemFlashcardCreationResult#alreadyLinked()}).
     * A caller that has already warned the learner and confirmed they want another one anyway passes
     * {@code allowDuplicate = true} to bypass the check.
     */
    public ProblemFlashcardCreationResult createCard(long deckId, long problemId, ReflectionCardSource source,
                                                      String front, String back, boolean allowDuplicate) {
        if (!allowDuplicate) {
            Optional<Flashcard> existing = findExistingLinkedCard(problemId, source);
            if (existing.isPresent()) {
                return new ProblemFlashcardCreationResult(existing.get(), true);
            }
        }
        Flashcard saved = flashcardService.addCard(deckId, front, back, CardType.CONCEPT, back,
                ValidationMode.CASE_INSENSITIVE, null, null, null, LESSONS_SKILL_CATEGORY);
        flashcardRepository.updateSourceLink(saved.getId(), problemId, source);
        saved.setSourceProblemId(problemId);
        saved.setSourceReflectionField(source);
        return new ProblemFlashcardCreationResult(saved, false);
    }

    /** The problem a card was generated from, for display as optional context/metadata — empty if
     *  the card wasn't generated from a problem, or that problem no longer exists. */
    public Optional<Problem> resolveSourceProblem(Flashcard flashcard) {
        return flashcard.hasSourceProblem() ? problemRepository.findById(flashcard.getSourceProblemId()) : Optional.empty();
    }

    public record ProblemFlashcardDraft(Problem sourceProblem, ReflectionCardSource source, String front, String back) {
    }

    public record ProblemFlashcardCreationResult(Flashcard card, boolean alreadyLinked) {
    }
}

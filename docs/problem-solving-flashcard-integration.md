# Flashcards from problem-solving lessons and mistakes (#148)

The Solving Workspace's Post-Solve Reflection panel (#146) gains a "Create Flashcard" section that
turns one reflection field into an ordinary {@code CONCEPT} flashcard without retyping it, reusing
CodeFit's existing spaced-repetition engine end to end — there is no separate review path or card
type for these cards.

## Sources

`ReflectionCardSource` names the six fields the issue asks for: `LESSON_LEARNED`, `MISTAKE_MADE`,
`KEY_OBSERVATION`, `ALGORITHM_OR_TECHNIQUE`, `COMPLEXITY_TRADEOFF`, `EDGE_CASE`. Five map directly onto
an existing `ProblemProgress` field (`lessonLearned`, `mistakeNotes`, `importantObservation`,
`actualTopic`, and a combined `timeComplexity`/`spaceComplexity` sentence, respectively); `EDGE_CASE`
has no dedicated stored field anywhere in the domain model, so its draft answer starts blank for the
learner to write from scratch. That's a deliberate scope decision, not an oversight — there was never
existing edge-case text to avoid retyping in the first place, so a blank, fully-editable starting
point satisfies the same acceptance criteria as the other five.

## Draft → save flow

`ProblemFlashcardService.buildDraft(problemId, source)` returns a `ProblemFlashcardDraft` (source
problem, chosen source, pre-filled prompt, pre-filled answer) without writing anything. The workspace
screen shows the prompt/answer in an editable `TextField`/`TextArea` pair; nothing is saved until the
learner clicks "Save Flashcard", and whatever they've typed by then — including a fully rewritten
prompt or answer — is exactly what gets persisted.

`createCard` reuses `FlashcardService.addCard` for validation and scheduling defaults (blank
prompt/answer rejected, due date defaults to today, `CardState.NEW`, ease factor 2.5 — the same
defaults every other card gets), then stamps `source_problem_id`/`source_reflection_field` onto the
saved row as a separate follow-up step (`FlashcardRepository.updateSourceLink`) rather than
duplicating `addCard`'s validation logic a second time.

## Deck selection

The learner can file the card into any existing deck, or the dedicated **Problem-Solving Lessons**
deck offered by default (`ProblemFlashcardService.resolveLessonsDeckId`, created lazily the first time
it's actually needed rather than eagerly at startup).

## Duplicate protection

`(source_problem_id, source_reflection_field)` identifies "a card already made from this exact
reflection field of this exact problem." `createCard(..., allowDuplicate)` checks that pair by
default: if a card is already linked, no new one is created — the existing card is returned instead
(`ProblemFlashcardCreationResult.alreadyLinked()`). The workspace screen uses this to warn the learner
with a confirmation dialog quoting the existing card's prompt before letting them create a second one
anyway (`allowDuplicate = true`). Two different reflection fields on the same problem are never
treated as duplicates of each other — the pair, not just the problem id, is what's checked.

## Source linking and problem deletion

`Flashcard.sourceProblemId`/`sourceReflectionField` are plain additive nullable columns with
**no foreign-key constraint** — deliberately, unlike every other problem-solving table's `problem_id`
column, which cascades on delete. If the source problem is ever deleted, the flashcard survives
untouched (its `source_problem_id` simply becomes a dangling reference rather than triggering a
cascade), satisfying "deleting the problem does not silently delete an already-created flashcard."
`ProblemFlashcardService.resolveSourceProblem(flashcard)` resolves a linked card back to its source
problem for optional display context, returning empty if the card has no link or the source problem
no longer exists.

## Known limitations

- Source problem code/title isn't yet surfaced in the main Decks/review screens' card display — only
  through `resolveSourceProblem`, which a future screen can call. Wiring it into every existing card
  list view was out of scope for this issue.
- `EDGE_CASE` cards always start from a blank answer; there is no dedicated edge-case text field
  anywhere upstream to pre-fill from.

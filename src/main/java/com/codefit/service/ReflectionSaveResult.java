package com.codefit.service;

import com.codefit.model.Flashcard;
import com.codefit.model.GeneratedCard;

import java.util.List;

/** Outcome of {@link ReflectionService#saveReflection}: the cards actually persisted, the generated
 *  cards skipped as near-duplicates of something already in the deck, and the reflection XP awarded
 *  (0 if the daily cap was already reached) — awarded once for the whole reflection, never per card. */
public record ReflectionSaveResult(List<Flashcard> savedCards, List<GeneratedCard> skippedDuplicates, int xpAwarded) {
}

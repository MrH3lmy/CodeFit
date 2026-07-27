package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.ProblemState;

/**
 * The combinable, clearable filter set for the Topic-Based Problem Library view (#144). Every field
 * is optional ({@code null} means "no constraint on this field"); {@link ProblemLibraryService}
 * applies them all together (a row must satisfy every non-null field to match).
 */
public record ProblemLibraryFilter(String searchText, String topic, DifficultyLevel suggestedLevel,
                                    Integer minQualityRating, String platform, ProblemState state) {

    public static ProblemLibraryFilter empty() {
        return new ProblemLibraryFilter(null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return equals(empty());
    }

    public ProblemLibraryFilter withSearchText(String searchText) {
        return new ProblemLibraryFilter(blankToNull(searchText), topic, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withTopic(String topic) {
        return new ProblemLibraryFilter(searchText, blankToNull(topic), suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withSuggestedLevel(DifficultyLevel suggestedLevel) {
        return new ProblemLibraryFilter(searchText, topic, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withMinQualityRating(Integer minQualityRating) {
        return new ProblemLibraryFilter(searchText, topic, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withPlatform(String platform) {
        return new ProblemLibraryFilter(searchText, topic, suggestedLevel, minQualityRating, blankToNull(platform), state);
    }

    public ProblemLibraryFilter withState(ProblemState state) {
        return new ProblemLibraryFilter(searchText, topic, suggestedLevel, minQualityRating, platform, state);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

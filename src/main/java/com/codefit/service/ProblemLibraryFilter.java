package com.codefit.service;

import com.codefit.model.DifficultyLevel;
import com.codefit.model.ProblemState;
import com.codefit.model.RoadmapStage;

/**
 * The combinable, clearable filter set for the Problem Library views (#144). Every field
 * is optional ({@code null} means "no constraint on this field"); {@link ProblemLibraryService}
 * applies them all together (a row must satisfy every non-null field to match).
 *
 * <p>{@code stage} matches a row's {@link com.codefit.model.RoadmapEntry} (its Blind Order membership,
 * or the Topics view's primary membership) — it exists so the Problems screen can default to the
 * learner's current stage instead of rendering the entire curriculum at once (#166).
 */
public record ProblemLibraryFilter(String searchText, String topic, RoadmapStage stage, DifficultyLevel suggestedLevel,
                                    Integer minQualityRating, String platform, ProblemState state) {

    public static ProblemLibraryFilter empty() {
        return new ProblemLibraryFilter(null, null, null, null, null, null, null);
    }

    public boolean isEmpty() {
        return equals(empty());
    }

    public ProblemLibraryFilter withSearchText(String searchText) {
        return new ProblemLibraryFilter(blankToNull(searchText), topic, stage, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withTopic(String topic) {
        return new ProblemLibraryFilter(searchText, blankToNull(topic), stage, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withStage(RoadmapStage stage) {
        return new ProblemLibraryFilter(searchText, topic, stage, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withSuggestedLevel(DifficultyLevel suggestedLevel) {
        return new ProblemLibraryFilter(searchText, topic, stage, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withMinQualityRating(Integer minQualityRating) {
        return new ProblemLibraryFilter(searchText, topic, stage, suggestedLevel, minQualityRating, platform, state);
    }

    public ProblemLibraryFilter withPlatform(String platform) {
        return new ProblemLibraryFilter(searchText, topic, stage, suggestedLevel, minQualityRating, blankToNull(platform), state);
    }

    public ProblemLibraryFilter withState(ProblemState state) {
        return new ProblemLibraryFilter(searchText, topic, stage, suggestedLevel, minQualityRating, platform, state);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

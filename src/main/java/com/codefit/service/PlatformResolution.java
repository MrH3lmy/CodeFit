package com.codefit.service;

/**
 * The result of resolving a row's platform (#160): the platform name itself, plus how it was obtained.
 * Deliberately has no side effects — {@link TrainingSheetAnalyzer} only counts a resolution's
 * {@link #source()} toward {@link WorkbookPreviewDetails}' explicit/inferred/unknown platform coverage
 * once the row is actually accepted (not a within-sheet duplicate, not a roadmap-slot conflict), so a
 * row that never becomes a membership never inflates those counts.
 */
public record PlatformResolution(String platform, PlatformSource source) {
}

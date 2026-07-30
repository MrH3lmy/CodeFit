package com.codefit.model;

import java.time.LocalDateTime;

/**
 * One problem's hint-ladder content (#162): up to four increasingly explicit levels
 * (Clarify/Observation/Approach/Explanation — see {@link HintLevel}), optional prerequisite topics,
 * and reference links, entirely separate from {@link Problem} (catalog identity) and
 * {@link ProblemProgress}/{@link ProblemSolvingSession} (a learner's own progress and in-progress
 * state). A problem has at most one {@code ProblemGuidance} row ({@code UNIQUE(problem_id)}); editing
 * guidance never touches problem identity or progress, and vice versa.
 *
 * <p>{@code prerequisites} and {@code referenceLinks} are stored pre-encoded (see
 * {@code AcceptedAnswerCodec}, the same list-of-strings codec {@link Problem#getLearningResources()}
 * already uses) — decoding into {@code List<String>} is {@code ProblemGuidanceService}'s job, kept
 * out of this plain data class the same way {@link Problem} keeps codec logic out of itself.
 *
 * <p>Missing guidance is a perfectly ordinary state (no row at all, or a row with some levels still
 * blank): {@code ProblemGuidanceService} is responsible for surfacing that clearly rather than
 * fabricating content, never this class.
 */
public class ProblemGuidance {
    private long id;
    private long problemId;
    private GuidanceSource source;
    private String clarifyText;
    private String observationText;
    private String approachText;
    private String explanationText;
    private String pseudocodeText;
    private String complexityNotes;
    private String commonMistakesText;
    private String prerequisitesEncoded;
    private String referenceLinksEncoded;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProblemGuidance(long id, long problemId, GuidanceSource source, String clarifyText, String observationText,
                           String approachText, String explanationText, String pseudocodeText, String complexityNotes,
                           String commonMistakesText, String prerequisitesEncoded, String referenceLinksEncoded,
                           LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.problemId = problemId;
        this.source = source == null ? GuidanceSource.LEARNER : source;
        this.clarifyText = clarifyText;
        this.observationText = observationText;
        this.approachText = approachText;
        this.explanationText = explanationText;
        this.pseudocodeText = pseudocodeText;
        this.complexityNotes = complexityNotes;
        this.commonMistakesText = commonMistakesText;
        this.prerequisitesEncoded = prerequisitesEncoded;
        this.referenceLinksEncoded = referenceLinksEncoded;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A fresh, empty guidance row for a problem with no authored content yet. */
    public static ProblemGuidance blank(long problemId, GuidanceSource source) {
        return new ProblemGuidance(0, problemId, source, null, null, null, null, null, null, null, null, null, null, null);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProblemId() { return problemId; }
    public GuidanceSource getSource() { return source; }
    public void setSource(GuidanceSource source) { this.source = source == null ? GuidanceSource.LEARNER : source; }
    public String getClarifyText() { return clarifyText; }
    public void setClarifyText(String clarifyText) { this.clarifyText = clarifyText; }
    public String getObservationText() { return observationText; }
    public void setObservationText(String observationText) { this.observationText = observationText; }
    public String getApproachText() { return approachText; }
    public void setApproachText(String approachText) { this.approachText = approachText; }
    public String getExplanationText() { return explanationText; }
    public void setExplanationText(String explanationText) { this.explanationText = explanationText; }
    /** The Explanation level's "idea, reasoning" prose; {@link #getPseudocodeText()},
     *  {@link #getComplexityNotes()}, and {@link #getCommonMistakesText()} are its other three
     *  required parts (#162), kept as their own fields so none of the four can be silently skipped
     *  by being folded into one opaque blob. */
    public String getPseudocodeText() { return pseudocodeText; }
    public void setPseudocodeText(String pseudocodeText) { this.pseudocodeText = pseudocodeText; }
    public String getComplexityNotes() { return complexityNotes; }
    public void setComplexityNotes(String complexityNotes) { this.complexityNotes = complexityNotes; }
    public String getCommonMistakesText() { return commonMistakesText; }
    public void setCommonMistakesText(String commonMistakesText) { this.commonMistakesText = commonMistakesText; }
    public String getPrerequisitesEncoded() { return prerequisitesEncoded; }
    public void setPrerequisitesEncoded(String prerequisitesEncoded) { this.prerequisitesEncoded = prerequisitesEncoded; }
    public String getReferenceLinksEncoded() { return referenceLinksEncoded; }
    public void setReferenceLinksEncoded(String referenceLinksEncoded) { this.referenceLinksEncoded = referenceLinksEncoded; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** {@code true} once every one of the Explanation level's four required parts (#162: idea/reasoning,
     *  pseudocode, complexity, common mistakes) has been authored — used to tell "fully authored" apart
     *  from "explanation prose exists but the other three parts are still blank" in the UI. */
    public boolean hasCompleteExplanation() {
        return isPresent(explanationText) && isPresent(pseudocodeText) && isPresent(complexityNotes) && isPresent(commonMistakesText);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** The text for {@code level}, or {@code null} if that level hasn't been authored yet.
     *  {@link HintLevel#EXPLANATION} composes all four required parts (#162) into one labeled block —
     *  see {@link #composedExplanation()}. */
    public String textForLevel(HintLevel level) {
        return switch (level) {
            case CLARIFY -> clarifyText;
            case OBSERVATION -> observationText;
            case APPROACH -> approachText;
            case EXPLANATION -> composedExplanation();
        };
    }

    /**
     * The full Explanation level must cover idea/reasoning, pseudocode, complexity, and common
     * mistakes (#162) — composed here from the four distinct stored fields rather than trusting a
     * single opaque blob to contain all of them. {@code null} only once every part is blank (so
     * {@code hasContent()} checks elsewhere correctly report "nothing authored yet"); once any part
     * exists, an unauthored part says so explicitly rather than being silently dropped, since silently
     * omitting it would look like "there are no common mistakes" rather than "nobody wrote this part
     * yet" — exactly the fabrication this issue says never to do.
     */
    private String composedExplanation() {
        if (!hasAnyExplanationContent()) {
            return null;
        }
        return "Idea & reasoning:\n" + orNotYetAuthored(explanationText)
                + "\n\nPseudocode:\n" + orNotYetAuthored(pseudocodeText)
                + "\n\nComplexity:\n" + orNotYetAuthored(complexityNotes)
                + "\n\nCommon mistakes:\n" + orNotYetAuthored(commonMistakesText);
    }

    private boolean hasAnyExplanationContent() {
        return isPresent(explanationText) || isPresent(pseudocodeText) || isPresent(complexityNotes) || isPresent(commonMistakesText);
    }

    private static String orNotYetAuthored(String value) {
        return isPresent(value) ? value : "(not yet authored)";
    }
}

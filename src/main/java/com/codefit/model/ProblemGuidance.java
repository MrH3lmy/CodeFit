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
    private String prerequisitesEncoded;
    private String referenceLinksEncoded;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public ProblemGuidance(long id, long problemId, GuidanceSource source, String clarifyText, String observationText,
                           String approachText, String explanationText, String prerequisitesEncoded,
                           String referenceLinksEncoded, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.problemId = problemId;
        this.source = source == null ? GuidanceSource.LEARNER : source;
        this.clarifyText = clarifyText;
        this.observationText = observationText;
        this.approachText = approachText;
        this.explanationText = explanationText;
        this.prerequisitesEncoded = prerequisitesEncoded;
        this.referenceLinksEncoded = referenceLinksEncoded;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A fresh, empty guidance row for a problem with no authored content yet. */
    public static ProblemGuidance blank(long problemId, GuidanceSource source) {
        return new ProblemGuidance(0, problemId, source, null, null, null, null, null, null, null, null);
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
    public String getPrerequisitesEncoded() { return prerequisitesEncoded; }
    public void setPrerequisitesEncoded(String prerequisitesEncoded) { this.prerequisitesEncoded = prerequisitesEncoded; }
    public String getReferenceLinksEncoded() { return referenceLinksEncoded; }
    public void setReferenceLinksEncoded(String referenceLinksEncoded) { this.referenceLinksEncoded = referenceLinksEncoded; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** The text for {@code level}, or {@code null} if that level hasn't been authored yet. */
    public String textForLevel(HintLevel level) {
        return switch (level) {
            case CLARIFY -> clarifyText;
            case OBSERVATION -> observationText;
            case APPROACH -> approachText;
            case EXPLANATION -> explanationText;
        };
    }
}

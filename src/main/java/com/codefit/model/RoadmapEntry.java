package com.codefit.model;

import java.time.LocalDateTime;

/**
 * One position of a {@link Problem} within the blind-order roadmap: which stage it belongs to, its
 * order within that stage, its workbook "set" grouping, whether it is mandatory, and the level
 * suggested for it at this position. Kept entirely separate from {@link Problem} so the same
 * problem can occupy positions in more than one {@link RoadmapStage} (or, in principle, more than
 * one position within a stage across re-imports) without duplicating its identity or its progress.
 *
 * <p>A given {@code (stage, sequenceOrder)} pair is a single roadmap slot: only one entry may
 * occupy it. A given {@code (problemId, stage)} pair is also unique: the same problem cannot be
 * registered twice within the same stage. Both are enforced at the database level (see the
 * {@code roadmap_entries} table) so repeated workbook imports can never create duplicate memberships.
 */
public class RoadmapEntry {
    private long id;
    private long problemId;
    private RoadmapStage stage;
    private int sequenceOrder;
    private Integer setNumber;
    private boolean mandatory;
    private DifficultyLevel suggestedLevel;
    private LocalDateTime createdAt;
    private Long importBatchId;

    public RoadmapEntry(long id, long problemId, RoadmapStage stage, int sequenceOrder, Integer setNumber,
                         boolean mandatory, DifficultyLevel suggestedLevel, LocalDateTime createdAt) {
        this.id = id;
        this.problemId = problemId;
        this.stage = stage;
        this.sequenceOrder = sequenceOrder;
        this.setNumber = setNumber;
        this.mandatory = mandatory;
        this.suggestedLevel = suggestedLevel;
        this.createdAt = createdAt;
    }

    public RoadmapEntry(long problemId, RoadmapStage stage, int sequenceOrder, Integer setNumber,
                        boolean mandatory, DifficultyLevel suggestedLevel) {
        this(0, problemId, stage, sequenceOrder, setNumber, mandatory, suggestedLevel, null);
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public long getProblemId() { return problemId; }
    public RoadmapStage getStage() { return stage; }
    public void setStage(RoadmapStage stage) { this.stage = stage; }
    public int getSequenceOrder() { return sequenceOrder; }
    public void setSequenceOrder(int sequenceOrder) { this.sequenceOrder = sequenceOrder; }
    public Integer getSetNumber() { return setNumber; }
    public void setSetNumber(Integer setNumber) { this.setNumber = setNumber; }
    public boolean isMandatory() { return mandatory; }
    public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }
    public DifficultyLevel getSuggestedLevel() { return suggestedLevel; }
    public void setSuggestedLevel(DifficultyLevel suggestedLevel) { this.suggestedLevel = suggestedLevel; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    /** Which {@link ImportBatch} created or last touched this membership (#149); null for one added
     *  manually rather than through a workbook import. */
    public Long getImportBatchId() { return importBatchId; }
    public void setImportBatchId(Long importBatchId) { this.importBatchId = importBatchId; }
}

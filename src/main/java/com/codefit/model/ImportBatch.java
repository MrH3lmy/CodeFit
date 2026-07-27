package com.codefit.model;

import java.time.LocalDateTime;

/**
 * One workbook import run (#149): records where the roadmap data it produced came from
 * ({@code sourceName}/{@code sourceUrl}/{@code author}/{@code version}) and when, so third-party
 * curriculum content stays traceable back to its source without CodeFit ever bundling or
 * redistributing the workbook itself (see {@code docs/problem-solving-source-attribution.md}).
 *
 * <p>Every {@link RoadmapEntry} created or last touched by an import records this batch's id
 * ({@link RoadmapEntry#getImportBatchId()}); deleting a batch (see
 * {@code TrainingSheetImportService#deleteImportBatch}) deletes exactly those roadmap memberships and
 * nothing else — never the underlying {@link Problem} catalog rows (which may still be referenced by
 * another batch or added manually) and never {@link ProblemProgress}/{@link ProblemAttempt}/
 * {@code Flashcard} rows, none of which reference a roadmap entry at all.
 */
public class ImportBatch {
    private long id;
    private String sourceName;
    private String sourceUrl;
    private String author;
    private String version;
    private LocalDateTime importedAt;

    public ImportBatch(long id, String sourceName, String sourceUrl, String author, String version, LocalDateTime importedAt) {
        this.id = id;
        this.sourceName = sourceName;
        this.sourceUrl = sourceUrl;
        this.author = author;
        this.version = version;
        this.importedAt = importedAt;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
    public String getSourceName() { return sourceName; }
    public String getSourceUrl() { return sourceUrl; }
    public String getAuthor() { return author; }
    public String getVersion() { return version; }
    public LocalDateTime getImportedAt() { return importedAt; }
}

package com.codefit.knowledge;

import java.util.List;

/** Historical public metadata only; unknown values never imply learner evidence or approval. */
public record PublicCoverage(
        int v, String schema, String ledgerSchema, String ledgerVersion, String ledgerSha256,
        String sourcePath, boolean localReviewOnly, boolean productionImportSupported,
        Provenance provenance, List<Book> books, ReportedClaims reportedClaims,
        Count learnerValidated, List<String> followupGaps) {
    public PublicCoverage {
        books = List.copyOf(books);
        followupGaps = List.copyOf(followupGaps);
    }

    public record Provenance(String observedAt, String sourceVersion, String source,
                             String verification, String privateSourceBodies,
                             String previousCheckpoint, List<String> limitations) {
        public Provenance { limitations = List.copyOf(limitations); }
    }

    public record Count(String status, Integer count, Integer denominator) {}

    public record Book(String id, String title, Count inventory, Count preliminaryMapped,
                       Count authorDraft, Count independentReview, Count runtimeLearning) {}

    public record FirstPassNotes(int count, int denominator) {}

    public record RevisionEvents(int count, boolean additiveToMissionBlueprintSlots,
                                 List<String> records) {
        public RevisionEvents { if (records != null) records = List.copyOf(records); }
    }

    public record ReportedClaims(int structuralUnits, int preliminaryMappedUnits,
                                 int reportedUnmappedUnits,
                                 FirstPassNotes effectiveJavaFirstPassAuthorNotes,
                                 int missionBlueprintSlots, int deepOverlaySlots,
                                 RevisionEvents revisionEvents, int independentApprovals,
                                 int productionGraders, int hunterProductionImports) {}
}

package com.codefit.model;

/**
 * Where a {@link com.codefit.model.ProblemGuidance} row's content came from (#162). CodeFit must
 * never scrape, bundle, or otherwise claim authorship of copyrighted third-party editorial text —
 * this provenance tag is what lets the product distinguish content it's responsible for from content
 * a learner typed in for themselves or imported as their own reference.
 */
public enum GuidanceSource {
    /** The learner wrote or edited this guidance themselves. */
    LEARNER,
    /** Authored by CodeFit as original content (never copied third-party editorial text). */
    CODEFIT,
    /** Imported locally by the learner from their own files/notes — reference links may point to
     *  third-party editorial/video content, but the stored text itself is not copied from it. */
    IMPORTED,
    /** Reserved for a future provider-generated-guidance integration; unused today. */
    PROVIDER
}

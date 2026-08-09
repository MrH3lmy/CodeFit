package com.codefit.service;

/**
 * Per-domain verdict within an {@link InterviewReadinessResult}. {@code PASS}/{@code FAIL} only ever
 * apply to a critical-gate domain evaluated against its minimum threshold; a measured non-critical
 * domain (no threshold to grade against) is {@code MEASURED} instead of a fabricated pass/fail.
 */
public enum InterviewDomainReadinessStatus {
    PASS,
    FAIL,
    /** Has a score, but is not a critical gate so there is no threshold to grade it against. */
    MEASURED,
    /** No requirement in this domain currently has enough data to produce a score. */
    NOT_MEASURED
}

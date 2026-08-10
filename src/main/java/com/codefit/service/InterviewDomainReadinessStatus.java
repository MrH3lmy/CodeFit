package com.codefit.service;

/**
 * Per-domain verdict within an {@link InterviewReadinessResult}. {@code PASS}/{@code FAIL} only ever
 * apply to a critical-gate domain evaluated against its minimum threshold. {@code PARTIAL} means a
 * critical gate has a score from some requirements, but does not yet have complete requirement
 * coverage, so it must not be treated as passed even when the measured subset is strong.
 */
public enum InterviewDomainReadinessStatus {
    PASS,
    FAIL,
    /** A critical gate has measurable signal, but not all of its requirements are measurable yet. */
    PARTIAL,
    /** Has a score, but is not a critical gate so there is no threshold to grade it against. */
    MEASURED,
    /** No requirement in this domain currently has enough data to produce a score. */
    NOT_MEASURED
}

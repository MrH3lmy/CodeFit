package com.codefit.service;

/**
 * Overall verdict for an {@link InterviewReadinessResult}. Deliberately separate from
 * {@link InterviewDomainReadinessStatus} - "is the candidate ready" and "did this one domain measure
 * up" are different questions, and folding them into one enum would blur which meaning a given value
 * carries at each level of the result.
 */
public enum InterviewReadinessStatus {
    READY,
    NOT_READY,
    /** At least one critical domain (or the overall score itself) cannot currently be measured. */
    INSUFFICIENT_DATA
}

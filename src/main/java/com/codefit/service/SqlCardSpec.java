package com.codefit.service;

/**
 * Per-card configuration for grading a {@link com.codefit.model.CardType#SQL_QUERY} attempt by
 * executing it against an isolated fixture database instead of comparing submitted text to a
 * saved string. Decoded from a card's accepted-answers column by {@link SqlCardSpecCodec}.
 *
 * <p>Either {@code referenceQuery} or {@code expectedError} must be set. When {@code referenceQuery}
 * is set, the attempt is graded by running it and the reference query against separate copies of
 * the same fixture and comparing their results. When {@code expectedError} is set instead, the
 * attempt is graded correct only if executing it raises an error containing that text.</p>
 */
public record SqlCardSpec(String schemaSql, String seedSql, String referenceQuery, String expectedError,
                           boolean orderMatters, boolean allowControlledDdl, int timeoutMillis) {

    public static final int DEFAULT_TIMEOUT_MILLIS = 2000;

    public boolean expectsError() {
        return expectedError != null && !expectedError.isBlank();
    }
}

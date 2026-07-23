package com.codefit.model;

/**
 * A learner's optional self-reported confidence in an attempt, captured independently of the
 * scheduler rating (Again/Hard/Good/Easy) so the two signals are never conflated. Confidence is
 * intended for later calibration statistics (e.g. "high confidence but incorrect").
 */
public enum ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static ConfidenceLevel fromDatabaseValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ConfidenceLevel.valueOf(value.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}

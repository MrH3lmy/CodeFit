package com.codefit.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfidenceLevelTest {

    @Test
    void fromDatabaseValueParsesKnownLevelsCaseInsensitively() {
        assertEquals(ConfidenceLevel.LOW, ConfidenceLevel.fromDatabaseValue("low"));
        assertEquals(ConfidenceLevel.MEDIUM, ConfidenceLevel.fromDatabaseValue("MEDIUM"));
        assertEquals(ConfidenceLevel.HIGH, ConfidenceLevel.fromDatabaseValue("High"));
    }

    @Test
    void fromDatabaseValueReturnsNullForBlankOrUnknownValues() {
        assertNull(ConfidenceLevel.fromDatabaseValue(null));
        assertNull(ConfidenceLevel.fromDatabaseValue(""));
        assertNull(ConfidenceLevel.fromDatabaseValue("   "));
        assertNull(ConfidenceLevel.fromDatabaseValue("not-a-level"));
    }
}

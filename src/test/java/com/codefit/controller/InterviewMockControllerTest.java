package com.codefit.controller;

import com.codefit.service.InterviewMockMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewMockControllerTest {

    @Test
    void scoreParserRequiresAnExplicitZeroToOneHundredValue() {
        assertTrue(InterviewMockController.parseScore(null).isEmpty());
        assertTrue(InterviewMockController.parseScore("").isEmpty());
        assertTrue(InterviewMockController.parseScore("-1").isEmpty());
        assertTrue(InterviewMockController.parseScore("101").isEmpty());
        assertTrue(InterviewMockController.parseScore("abc").isEmpty());
        assertEquals(0, InterviewMockController.parseScore("0").orElseThrow());
        assertEquals(70, InterviewMockController.parseScore("70").orElseThrow());
        assertEquals(100, InterviewMockController.parseScore("100").orElseThrow());
    }

    @Test
    void mockModesRenderAsHumanReadableLabels() {
        assertEquals("Live Coding", InterviewMockController.displayMode(InterviewMockMode.LIVE_CODING));
        assertEquals("Technical Deep Dive", InterviewMockController.displayMode(InterviewMockMode.TECHNICAL_DEEP_DIVE));
        assertEquals("Full Interview Loop", InterviewMockController.displayMode(InterviewMockMode.FULL_INTERVIEW_LOOP));
    }
}

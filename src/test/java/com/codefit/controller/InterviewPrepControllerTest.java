package com.codefit.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InterviewPrepControllerTest {

    @Test
    void measuredEvidenceIsNeutralRatherThanRenderedAsPassing() {
        assertEquals("interview-status-pass", InterviewPrepController.statusStyle("PASS"));
        assertEquals("interview-status-pass", InterviewPrepController.statusStyle("READY"));
        assertEquals("interview-status-neutral", InterviewPrepController.statusStyle("MEASURED"));
        assertEquals("interview-status-warning", InterviewPrepController.statusStyle("PARTIAL"));
        assertEquals("interview-status-fail", InterviewPrepController.statusStyle("FAIL"));
    }
}

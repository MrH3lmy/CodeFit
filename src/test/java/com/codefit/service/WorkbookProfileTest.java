package com.codefit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkbookProfileTest {

    @Test
    void aUsableCustomWorkbookWithoutAnIdentityMarkerStaysGeneric() {
        WorkbookProfile profile = new WorkbookProfile("Junior Training Sheet", "Not detected");

        assertEquals("Generic training workbook", profile.name());
        assertEquals("Not detected", profile.version());
    }

    @Test
    void aVersionedJuniorTrainingSheetKeepsItsDetectedProfile() {
        WorkbookProfile profile = new WorkbookProfile("Junior Training Sheet", "V7.0");

        assertEquals("Junior Training Sheet", profile.name());
        assertEquals("V7.0", profile.version());
    }
}

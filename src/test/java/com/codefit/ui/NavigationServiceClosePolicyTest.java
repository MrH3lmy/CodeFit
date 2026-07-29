package com.codefit.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NavigationServiceClosePolicyTest {

    @Test
    void activeImportBlocksApplicationClose() {
        assertTrue(NavigationService.shouldBlockCloseForActiveImport(true));
    }

    @Test
    void applicationMayCloseWhenNoImportIsReserved() {
        assertFalse(NavigationService.shouldBlockCloseForActiveImport(false));
    }
}

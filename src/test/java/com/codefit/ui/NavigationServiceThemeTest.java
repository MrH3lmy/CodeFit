package com.codefit.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NavigationServiceThemeTest {

    @Test
    void knownThemeClassesAreReturnedUnchanged() {
        assertEquals("theme-dark", NavigationService.sanitizeThemeClass("theme-dark"));
        assertEquals("theme-light", NavigationService.sanitizeThemeClass("theme-light"));
    }

    @Test
    void legacyThemeClassesMigrateToDark() {
        assertEquals("theme-dark", NavigationService.sanitizeThemeClass("theme-ocean"));
        assertEquals("theme-dark", NavigationService.sanitizeThemeClass("theme-forest"));
        assertEquals("theme-dark", NavigationService.sanitizeThemeClass("theme-synthwave"));
    }

    @Test
    void unknownOrMissingPreferencesMigrateToDark() {
        assertEquals("theme-dark", NavigationService.sanitizeThemeClass("not-a-real-theme"));
        assertEquals("theme-dark", NavigationService.sanitizeThemeClass(null));
        assertEquals("theme-dark", NavigationService.sanitizeThemeClass(""));
    }

    @Test
    void onlyDarkAndLightAreSelectable() {
        assertEquals(java.util.List.of("Dark", "Light"), NavigationService.getThemeDisplayNames());
    }
}

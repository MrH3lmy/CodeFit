package com.codefit.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every stylesheet the shell loads must exist on the classpath and load in a predictable,
 *  dependency-respecting order: tokens before base, base before everything that uses a control
 *  or screen selector. */
class StylesheetResourcesTest {

    @Test
    void everyStylesheetResourceExists() {
        for (String stylesheet : NavigationService.STYLESHEETS) {
            assertNotNull(getClass().getResource(stylesheet), "Missing stylesheet: " + stylesheet);
        }
    }

    @Test
    void tokensCssLoadsFirst() {
        assertEquals("/css/tokens.css", NavigationService.STYLESHEETS[0]);
    }

    @Test
    void baseCssLoadsRightAfterTokensAndBeforeControls() {
        assertTrue(indexOf("/css/tokens.css") < indexOf("/css/base.css"));
        assertTrue(indexOf("/css/base.css") < indexOf("/css/controls.css"));
    }

    @Test
    void sharedShellAndControlStylesheetsLoadBeforeScreenSpecificOnes() {
        int shellIndex = indexOf("/css/shell.css");
        for (String screenSpecific : new String[] {"/css/review.css", "/css/library.css", "/css/forms.css",
                "/css/progress.css", "/css/today.css"}) {
            assertTrue(shellIndex < indexOf(screenSpecific), screenSpecific + " must load after shell.css");
        }
    }

    private int indexOf(String stylesheet) {
        String[] stylesheets = NavigationService.STYLESHEETS;
        for (int i = 0; i < stylesheets.length; i++) {
            if (stylesheets[i].equals(stylesheet)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Not found: " + stylesheet);
    }
}

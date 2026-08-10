package com.codefit.ui;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTest {

    @Test
    void everyRouteResolvesAnExistingFxmlResourceOnTheClasspath() {
        for (Route route : Route.values()) {
            assertNotNull(Route.class.getResource(route.fxmlPath()),
                    "Missing FXML resource for route " + route + ": " + route.fxmlPath());
        }
    }

    @Test
    void fxmlPathIsDerivedFromTheFxmlResourceName() {
        for (Route route : Route.values()) {
            assertEquals("/fxml/" + route.fxmlResource(), route.fxmlPath());
        }
    }

    @Test
    void everyRouteHasANonBlankTitleAndShortLabel() {
        for (Route route : Route.values()) {
            assertTrue(route.title() != null && !route.title().isBlank(), route + " title must not be blank");
            assertTrue(route.shortLabel() != null && !route.shortLabel().isBlank(), route + " shortLabel must not be blank");
        }
    }

    @Test
    void fxmlResourcesAreUniquePerRoute() {
        Set<String> resources = new HashSet<>();
        for (Route route : Route.values()) {
            assertTrue(resources.add(route.fxmlResource()), "Duplicate fxml mapping for " + route.fxmlResource());
        }
    }

    @Test
    void everyPrimaryNavItemIsReachableByAtLeastOneRoute() {
        Set<Route.NavItem> covered = EnumSet.noneOf(Route.NavItem.class);
        for (Route route : Route.values()) {
            if (route.navItem() != null) {
                covered.add(route.navItem());
            }
        }
        assertEquals(EnumSet.allOf(Route.NavItem.class), covered);
    }

    @Test
    void primaryNavRoutesHighlightThemselves() {
        assertEquals(Route.NavItem.TODAY, Route.TODAY.navItem());
        assertEquals(Route.NavItem.REVIEW, Route.REVIEW.navItem());
        assertEquals(Route.NavItem.LIBRARY, Route.LIBRARY.navItem());
        assertEquals(Route.NavItem.PROBLEMS, Route.PROBLEMS.navItem());
        assertEquals(Route.NavItem.INTERVIEW, Route.INTERVIEW.navItem());
        assertEquals(Route.NavItem.PROGRESS, Route.PROGRESS.navItem());
    }

    @Test
    void interviewMockStaysGroupedUnderInterviewNavigation() {
        assertEquals(Route.NavItem.INTERVIEW, Route.INTERVIEW_MOCK.navItem());
    }

    @Test
    void syllabusIsGroupedUnderTheLibraryNavItemUntilItsOwnMerge() {
        assertEquals(Route.NavItem.LIBRARY, Route.SYLLABUS.navItem());
    }

    @Test
    void addCardIsAGlobalActionWithNoSidebarHighlight() {
        assertNull(Route.ADD_CARD.navItem());
    }

    @Test
    void solvingWorkspaceIsAGlobalActionWithNoSidebarHighlight() {
        assertNull(Route.SOLVING_WORKSPACE.navItem());
    }

    @Test
    void problemDashboardIsAGlobalActionWithNoSidebarHighlight() {
        assertNull(Route.PROBLEM_DASHBOARD.navItem());
    }
}

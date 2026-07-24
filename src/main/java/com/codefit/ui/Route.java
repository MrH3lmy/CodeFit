package com.codefit.ui;

/**
 * Testable mapping from a navigation destination to the FXML it loads, the window title to show,
 * and which primary sidebar item (if any) should be highlighted while that destination is active.
 */
public enum Route {
    TODAY("dashboard.fxml", "Today", "CodeFit - Today", NavItem.TODAY),
    REVIEW("review.fxml", "Review", "CodeFit - Review", NavItem.REVIEW),
    LIBRARY("decks.fxml", "Library", "CodeFit - Library", NavItem.LIBRARY),
    SYLLABUS("syllabus.fxml", "Java Backend Syllabus", "CodeFit - Java Backend Syllabus", NavItem.LIBRARY),
    PROGRESS("stats.fxml", "Progress", "CodeFit - Progress", NavItem.PROGRESS),
    ADD_CARD("add-card.fxml", "New Card", "CodeFit - New Card", null);

    private final String fxmlResource;
    private final String shortLabel;
    private final String title;
    private final NavItem navItem;

    Route(String fxmlResource, String shortLabel, String title, NavItem navItem) {
        this.fxmlResource = fxmlResource;
        this.shortLabel = shortLabel;
        this.title = title;
        this.navItem = navItem;
    }

    public String fxmlResource() {
        return fxmlResource;
    }

    public String fxmlPath() {
        return "/fxml/" + fxmlResource;
    }

    /** Short label suitable for a top bar heading. */
    public String shortLabel() {
        return shortLabel;
    }

    public String title() {
        return title;
    }

    /** The primary sidebar item that should be highlighted for this route, or null for a global action. */
    public NavItem navItem() {
        return navItem;
    }

    /** The four persistent primary navigation destinations shown in the sidebar. */
    public enum NavItem {
        TODAY,
        REVIEW,
        LIBRARY,
        PROGRESS
    }
}

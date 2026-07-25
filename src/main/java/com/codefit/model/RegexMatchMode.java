package com.codefit.model;

/** Whether a submitted regex must consume an entire example string or merely be found within it. */
public enum RegexMatchMode {
    FULL_MATCH("Full match"),
    FIND("Find anywhere");

    private final String label;

    RegexMatchMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

package com.codefit.model;

public enum ValidationMode {
    EXACT("Exact"),
    CASE_INSENSITIVE("Case-insensitive"),
    NORMALIZED_SPACING("Normalize spacing"),
    COMMAND_NORMALIZED("Command normalized");

    private final String label;

    ValidationMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

package com.codefit.model;

public enum ValidationMode {
    EXACT("Exact"),
    CASE_INSENSITIVE("Case-insensitive"),
    NORMALIZED_SPACING("Normalize spacing"),
    COMMAND_NORMALIZED("Command normalized"),
    REGEX_EXAMPLES("Regex examples");

    private final String label;

    ValidationMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

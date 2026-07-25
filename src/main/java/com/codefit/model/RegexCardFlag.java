package com.codefit.model;

/** Regex compilation flags a card author can enable for a {@link CardType#REGEX_PATTERN} card. */
public enum RegexCardFlag {
    CASE_INSENSITIVE("Case-insensitive"),
    MULTILINE("Multiline"),
    DOTALL("Dot matches newline");

    private final String label;

    RegexCardFlag(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

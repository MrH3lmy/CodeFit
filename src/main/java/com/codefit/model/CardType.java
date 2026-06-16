package com.codefit.model;

public enum CardType {
    RECALL("Concept flashcard"),
    COMMAND("Command"),
    LINUX_COMMAND("Linux command"),
    GIT_COMMAND("Git command"),
    SQL_QUERY("SQL query"),
    REGEX_PATTERN("Regex pattern"),
    CODE_OUTPUT("Code output prediction"),
    CONCEPT("Concept flashcard");

    private final String label;

    CardType(String label) {
        this.label = label;
    }

    public boolean isCommandTemplate() {
        return this == COMMAND || this == LINUX_COMMAND || this == GIT_COMMAND;
    }

    @Override
    public String toString() {
        return label;
    }
}

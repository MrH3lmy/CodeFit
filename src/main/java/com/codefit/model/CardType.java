package com.codefit.model;

public enum CardType {
    RECALL("Recall"),
    COMMAND("Command");

    private final String label;

    CardType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

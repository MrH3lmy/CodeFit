package com.codefit.model;

/** The work-reflection workflows {@link com.codefit.service.ReflectionService} can split into
 *  atomic cards (#102). Each maps to its own set of independently-answerable follow-up prompts. */
public enum ReflectionType {
    BUG("Bug I fixed"),
    COMMAND("Command I got wrong"),
    MISSED_CONCEPT("Concept I missed");

    private final String label;

    ReflectionType(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}

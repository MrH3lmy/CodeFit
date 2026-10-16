package com.codefit.evolution.quest;

/** Exact immutable catalog identity; versions are never resolved implicitly. */
public record CatalogRef(String id, int version) implements Comparable<CatalogRef> {
    public CatalogRef {
        id(id);
        if ((id + "/v" + version).length() > 128)
            throw new IllegalArgumentException("Version tag exceeds evidence contract bound");
        if (version < 1) throw new IllegalArgumentException("Positive version required");
    }

    /**
     * Bounded EvaluationReference-compatible tag; Problem natural keys use a separate @ spelling.
     */
    public String tag() {
        return id + "/v" + version;
    }

    public static void id(String s) {
        if (s == null || s.length() > 120 || !s.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*"))
            throw new IllegalArgumentException("Stable bounded identifier required");
    }

    @Override
    public int compareTo(CatalogRef o) {
        int c = id.compareTo(o.id);
        return c == 0 ? Integer.compare(version, o.version) : c;
    }
}

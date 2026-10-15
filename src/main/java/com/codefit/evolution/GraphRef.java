package com.codefit.evolution;

/** Stable graph ID and immutable snapshot version, independent of skill-definition versions. */
public record GraphRef(String graphId, int graphVersion) {
    public GraphRef {
        SkillRef.requireIdentifier(graphId, "Graph ID");
        if (graphVersion < 1) throw new IllegalArgumentException("Graph version must be positive: " + graphVersion);
    }
}

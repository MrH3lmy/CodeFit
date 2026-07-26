package com.codefit.model;

/**
 * A stage in the Junior Training Sheet's blind roadmap. Declaration order is the intended learning
 * order (A &rarr; B &rarr; C1 &rarr; C2 &rarr; D1 &rarr; D2 &rarr; D3); callers that need to sort
 * stages in roadmap order can rely on {@link #ordinal()} rather than alphabetical/string sorting.
 */
public enum RoadmapStage {
    A, B, C1, C2, D1, D2, D3
}

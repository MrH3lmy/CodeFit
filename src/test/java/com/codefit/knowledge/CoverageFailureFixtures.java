package com.codefit.knowledge;

import java.io.IOException;
import java.util.*;

/** Shares real reader rejections with the desktop fail-closed integration test. */
public final class CoverageFailureFixtures {
    private CoverageFailureFixtures() {}

    public static List<PreviewFailure> rejectedSnapshots() throws IOException {
        List<PreviewFailure> failures = new ArrayList<>();
        for (byte[] invalid : KnowledgeCoverageTest.invalidFixtures()) {
            try {
                KnowledgeCoverage.verify(invalid);
                throw new AssertionError("Invalid fixture accepted");
            } catch (PreviewFailure failure) { failures.add(failure); }
        }
        try {
            KnowledgeCoverage.load(null);
            throw new AssertionError("Missing resource accepted");
        } catch (PreviewFailure failure) { failures.add(failure); }
        return List.copyOf(failures);
    }
}

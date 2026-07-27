package com.codefit.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the #149 source-packaging policy while allowing one explicitly approved workbook fixture
 * used to validate the real importer flow. No other workbook or extracted third-party dataset may be
 * committed without updating this allow-list and documenting the approval in the same pull request.
 */
class WorkbookContentPolicyTest {

    private static final String APPROVED_WORKBOOK_PATH =
            "data/import-fixtures/Ahmed-Junior-Training-Sheet-V7.0.xlsx";

    /** Case-insensitive substrings that would indicate workbook authorship/content leaked into
     * committed source or documentation files. The approved binary workbook is not decoded or scanned
     * as text by this test. */
    private static final List<String> DISALLOWED_CONTENT_MARKERS = List.of("mostafa", "saad");

    /** This file itself must define the disallowed markers as literal strings, so it is the one
     * deliberate exemption from the scan it implements. */
    private static final String SELF_PATH_SUFFIX = "WorkbookContentPolicyTest.java";

    @Test
    void onlyTheExplicitlyApprovedWorkbookFixtureMayBeCommitted() throws IOException {
        Path repoRoot = repoRoot();
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            List<String> xlsxFiles = paths
                    .filter(path -> !path.toString().contains(FILE_SEPARATOR + "target" + FILE_SEPARATOR)
                            && !path.toString().contains(FILE_SEPARATOR + ".git" + FILE_SEPARATOR))
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .map(repoRoot::relativize)
                    .map(path -> path.toString().replace(FILE_SEPARATOR, "/"))
                    .sorted()
                    .toList();
            assertEquals(List.of(APPROVED_WORKBOOK_PATH), xlsxFiles,
                    "Only the explicitly approved importer fixture may be committed; found: " + xlsxFiles);
        }
    }

    @Test
    void noSourceFileReferencesTheRealWorkbooksKnownAuthorship() throws IOException {
        Path repoRoot = repoRoot();
        try (Stream<Path> paths = Files.walk(repoRoot.resolve("src"))) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.toString().endsWith(SELF_PATH_SUFFIX))
                    .forEach(this::assertNoDisallowedMarkers);
        }
        try (Stream<Path> paths = Files.walk(repoRoot.resolve("docs"))) {
            paths.filter(Files::isRegularFile).forEach(this::assertNoDisallowedMarkers);
        }
        Path readme = repoRoot.resolve("README.md");
        if (Files.isRegularFile(readme)) {
            assertNoDisallowedMarkers(readme);
        }
    }

    private void assertNoDisallowedMarkers(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException notDecodableAsText) {
            return;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        for (String marker : DISALLOWED_CONTENT_MARKERS) {
            if (normalized.contains(marker)) {
                fail("File " + file + " contains disallowed content marker '" + marker + "'.");
            }
        }
    }

    private static final String FILE_SEPARATOR = java.io.File.separator;

    private Path repoRoot() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }
}

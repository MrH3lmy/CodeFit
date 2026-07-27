package com.codefit.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the #149 policy decision to keep the real Junior Training Sheet (and any other third-party
 * curriculum workbook) out of the public repository entirely: no {@code .xlsx} file is ever committed,
 * and no source file references the real workbook's known authorship. Every importer test fixture
 * (see {@link TrainingSheetFixtures}) is built programmatically from made-up data instead.
 */
class WorkbookContentPolicyTest {

    /** Case-insensitive substrings that would indicate real workbook authorship/content leaked into
     *  a committed source file; kept intentionally generic (author name fragments) rather than
     *  matching the real workbook's exact title, which itself is fine to reference descriptively. */
    private static final List<String> DISALLOWED_CONTENT_MARKERS = List.of("mostafa", "saad");

    /** This file itself must define the disallowed markers as literal strings, so it is the one
     *  deliberate exemption from the scan it implements. */
    private static final String SELF_PATH_SUFFIX = "WorkbookContentPolicyTest.java";

    @Test
    void noXlsxFileIsCommittedAnywhereInTheRepository() throws IOException {
        Path repoRoot = repoRoot();
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            List<Path> xlsxFiles = paths
                    .filter(path -> !path.toString().contains(FILE_SEPARATOR + "target" + FILE_SEPARATOR)
                            && !path.toString().contains(FILE_SEPARATOR + ".git" + FILE_SEPARATOR))
                    .filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".xlsx"))
                    .toList();
            assertTrue(xlsxFiles.isEmpty(), "No .xlsx workbook may be committed to the repository, found: " + xlsxFiles);
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
            return; // binary files (images, etc.) aren't a concern for a text content-marker scan.
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

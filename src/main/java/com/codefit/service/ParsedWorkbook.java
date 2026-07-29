package com.codefit.service;

import java.util.Map;
import java.util.Optional;

/**
 * A workbook's sheets, keyed by their exact (case-sensitive) sheet name as authored in the file, plus
 * a version string detected honestly from the workbook's own cell content (#160) — never from the
 * file name and never hard-coded to a specific author — or {@code null} if none was found.
 */
record ParsedWorkbook(Map<String, ParsedSheet> sheetsByName, String detectedVersion) {

    Optional<ParsedSheet> sheet(String name) {
        return Optional.ofNullable(sheetsByName.get(name));
    }
}

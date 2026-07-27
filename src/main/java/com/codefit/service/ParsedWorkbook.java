package com.codefit.service;

import java.util.Map;
import java.util.Optional;

/** A workbook's sheets, keyed by their exact (case-sensitive) sheet name as authored in the file. */
record ParsedWorkbook(Map<String, ParsedSheet> sheetsByName) {

    Optional<ParsedSheet> sheet(String name) {
        return Optional.ofNullable(sheetsByName.get(name));
    }
}

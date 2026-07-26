package com.codefit.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the many header spellings a Junior Training Sheet-style workbook might use (across roadmap
 * sheets and the {@code Topics} sheet) onto a small set of canonical column keys the importer
 * understands. Matching is case-insensitive and whitespace-trimmed; an unrecognized header is simply
 * not mapped, so extra workbook columns (author comments, formatting notes, etc.) are ignored rather
 * than rejected.
 *
 * <p>The exact header text of the real Junior Training Sheet was not available while building this
 * importer (per this epic's rule against committing or copying the real workbook), so this alias
 * list is a best-effort guess at common spellings. If the real workbook uses a header not listed
 * here, {@link TrainingSheetImportService#validate} will report the affected sheet as missing a
 * recognizable code/title column rather than silently importing nothing.
 */
final class TrainingSheetColumns {

    static final String CODE = "CODE";
    static final String TITLE = "TITLE";
    static final String PLATFORM = "PLATFORM";
    static final String URL = "URL";
    static final String SET_NUMBER = "SET_NUMBER";
    static final String MANDATORY = "MANDATORY";
    static final String LEVEL = "LEVEL";
    static final String TOPIC = "TOPIC";
    static final String QUALITY = "QUALITY";
    static final String RESOURCES = "RESOURCES";
    static final String STATUS = "STATUS";
    static final String ORDER = "ORDER";

    private static final Map<String, String> ALIASES = buildAliases();

    private TrainingSheetColumns() {
    }

    /** Returns the canonical column key for a raw header string, or {@code null} if unrecognized. */
    static String canonicalize(String rawHeader) {
        if (rawHeader == null) {
            return null;
        }
        return ALIASES.get(rawHeader.strip().toLowerCase(Locale.ROOT));
    }

    private static Map<String, String> buildAliases() {
        Map<String, String> aliases = new HashMap<>();
        register(aliases, CODE, "code", "problem code", "#", "no", "no.", "id");
        register(aliases, TITLE, "title", "problem", "name", "problem name");
        register(aliases, PLATFORM, "platform", "site", "source", "judge");
        register(aliases, URL, "link", "url", "problem link");
        register(aliases, SET_NUMBER, "set", "set number", "set #");
        register(aliases, MANDATORY, "mandatory", "required");
        register(aliases, LEVEL, "level", "suggested level", "difficulty");
        register(aliases, TOPIC, "topic", "category", "tag");
        register(aliases, QUALITY, "quality", "rating");
        register(aliases, RESOURCES, "resources", "resource", "editorial", "video", "learning resources");
        register(aliases, STATUS, "status", "progress", "state", "result");
        register(aliases, ORDER, "order", "sequence", "position");
        return aliases;
    }

    private static void register(Map<String, String> aliases, String canonical, String... rawHeaders) {
        for (String rawHeader : rawHeaders) {
            aliases.put(rawHeader, canonical);
        }
    }
}

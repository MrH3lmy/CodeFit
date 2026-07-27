package com.codefit.service;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Maps the many header spellings a Junior Training Sheet-style workbook might use (across roadmap
 * sheets and the {@code Topics} sheet) onto a small set of canonical column keys the importer
 * understands. Matching is case-insensitive, whitespace-normalized (embedded line breaks collapse to
 * a single space before comparison, so a wrapped two-line header matches the same as its single-line
 * spelling), and trimmed; an unrecognized header is simply not mapped, so extra workbook columns
 * (author comments, formatting notes, etc.) are ignored rather than rejected.
 *
 * <p>Two headers are matched structurally instead of by exact alias ({@link #canonicalizeStructural}):
 * a compound "<something> Category" header and a "Category Code" header. The real Junior Training
 * Sheet workbook (#159) qualifies its secondary classification column with an individual curator's
 * name, which this importer must never hard-code or otherwise persist in source (see
 * {@code WorkbookContentPolicyTest}); matching "contains 'category' but isn't exactly 'category'"
 * recognizes that column, and any workbook using the same convention, without naming anyone.
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

    /** A row's number of judge submissions before/at its recorded status (#159). */
    static final String SUBMIT_COUNT = "SUBMIT_COUNT";
    /** Phase timings in minutes, as authored in the workbook (#159); the importer converts to seconds. */
    static final String READING_TIME_MINUTES = "READING_TIME_MINUTES";
    static final String THINKING_TIME_MINUTES = "THINKING_TIME_MINUTES";
    static final String CODING_TIME_MINUTES = "CODING_TIME_MINUTES";
    static final String DEBUG_TIME_MINUTES = "DEBUG_TIME_MINUTES";
    /** The learner's own 1-10 self-rated difficulty, distinct from {@link #LEVEL}'s Easy/Medium/Hard. */
    static final String PERCEIVED_DIFFICULTY = "PERCEIVED_DIFFICULTY";
    /** "By yourself? Yes/No/Hint" — how much assistance the learner used. */
    static final String INDEPENDENCE = "INDEPENDENCE";
    /** "1-2 line comments about your approach" — the learner's own notes, not curated resource links. */
    static final String APPROACH_NOTES = "APPROACH_NOTES";
    /** The workbook's secondary/curated classification column, matched structurally, never by name. */
    static final String CURATED_CATEGORY = "CURATED_CATEGORY";
    /** A short-form code paired with {@link #CURATED_CATEGORY} (e.g. "adhoc, NA"). */
    static final String CATEGORY_CODE = "CATEGORY_CODE";

    private static final Map<String, String> ALIASES = buildAliases();

    private TrainingSheetColumns() {
    }

    /** Returns the canonical column key for a raw header string, or {@code null} if unrecognized. */
    static String canonicalize(String rawHeader) {
        String normalized = normalize(rawHeader);
        if (normalized == null) {
            return null;
        }
        String exact = ALIASES.get(normalized);
        if (exact != null) {
            return exact;
        }
        return canonicalizeStructural(normalized);
    }

    /** Header text normalized for matching: embedded line breaks become spaces, whitespace collapses, case-folded. */
    private static String normalize(String rawHeader) {
        if (rawHeader == null) {
            return null;
        }
        String normalized = rawHeader.replaceAll("[\\r\\n]+", " ").strip().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    /**
     * Recognizes the two headers that can't be listed as literal aliases: a qualified category column
     * (contains "category" but isn't exactly "category") and its paired short-code column. Checked in
     * this order so "category code" (contains both words) resolves to {@link #CATEGORY_CODE} rather
     * than {@link #CURATED_CATEGORY}.
     */
    private static String canonicalizeStructural(String normalized) {
        boolean mentionsCategory = normalized.contains("category");
        if (!mentionsCategory) {
            return null;
        }
        if (normalized.contains("code")) {
            return CATEGORY_CODE;
        }
        if (!normalized.equals("category")) {
            return CURATED_CATEGORY;
        }
        return null;
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
        register(aliases, SUBMIT_COUNT, "submit count", "submission count", "submissions", "attempts");
        register(aliases, READING_TIME_MINUTES, "reading time(m)", "reading time (m)", "reading time");
        register(aliases, THINKING_TIME_MINUTES, "thinking time(m)", "thinking time (m)", "thinking time");
        register(aliases, CODING_TIME_MINUTES, "coding time(m)", "coding time (m)", "coding time");
        register(aliases, DEBUG_TIME_MINUTES, "debug time(m)", "debug time (m)", "debug time", "debugging time(m)");
        register(aliases, PERCEIVED_DIFFICULTY, "problem level /10", "problem level/10", "perceived difficulty");
        register(aliases, INDEPENDENCE, "by yourself?", "by yourself", "solved independently", "independence");
        register(aliases, APPROACH_NOTES, "1-2 line comments about your approach",
                "1-2 line comments about your approach is interesting?", "approach notes", "comments");
        return aliases;
    }

    private static void register(Map<String, String> aliases, String canonical, String... rawHeaders) {
        for (String rawHeader : rawHeaders) {
            aliases.put(rawHeader, canonical);
        }
    }
}

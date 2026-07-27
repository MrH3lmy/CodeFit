package com.codefit.service;

/**
 * Optional source attribution the learner supplies (or leaves blank) when importing a workbook
 * (#149): who it came from, where, and what version. Every field is optional — {@code sourceName}
 * falls back to the workbook's file name when left blank, so attribution is never lost even if the
 * learner skips the fields entirely.
 */
public record ImportSourceMetadata(String sourceName, String sourceUrl, String author, String version) {

    public static ImportSourceMetadata unspecified() {
        return new ImportSourceMetadata(null, null, null, null);
    }
}

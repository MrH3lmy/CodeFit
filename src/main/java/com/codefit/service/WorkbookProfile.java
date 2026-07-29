package com.codefit.service;

/**
 * The workbook's detected profile (#160). A workbook is only presented as a
 * {@code "Junior Training Sheet"} when the analyzer recognized a usable roadmap shape and the
 * workbook itself supplied a version marker. A structurally usable but otherwise unidentified custom
 * workbook is reported honestly as {@code "Generic training workbook"} / {@code "Not detected"}.
 * Neither field is ever derived from the source file's name.
 */
public record WorkbookProfile(String name, String version) {

    public WorkbookProfile {
        String normalizedVersion = version == null || version.isBlank() ? "Not detected" : version.strip();
        String normalizedName = name == null || name.isBlank() ? "Generic training workbook" : name.strip();

        if ("Junior Training Sheet".equals(normalizedName) && "Not detected".equals(normalizedVersion)) {
            normalizedName = "Generic training workbook";
        }

        name = normalizedName;
        version = normalizedVersion;
    }
}

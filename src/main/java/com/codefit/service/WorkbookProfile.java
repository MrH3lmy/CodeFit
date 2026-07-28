package com.codefit.service;

/**
 * The workbook's detected profile (#160): {@code name} describes the structural shape
 * {@link TrainingSheetAnalyzer} recognized ({@code "Junior Training Sheet"} when at least one usable
 * roadmap sheet was found, {@code "Generic training workbook"} otherwise — never a specific author's
 * name), and {@code version} is a version-shaped token found in the workbook's own cell content (see
 * {@link TrainingSheetWorkbookReader}), or {@code "Not detected"} when none was found. Neither field is
 * ever derived from the source file's name.
 */
public record WorkbookProfile(String name, String version) {
}

package com.codefit.service;

/**
 * Thrown when a workbook cannot be read, fails structural validation, or when the transactional
 * import itself fails partway through (in which case the whole import has already been rolled back
 * by {@link TrainingSheetImportService} before this is thrown, so the caller never sees partial data).
 */
public class WorkbookImportException extends RuntimeException {

    public WorkbookImportException(String message) {
        super(message);
    }

    public WorkbookImportException(String message, Throwable cause) {
        super(message, cause);
    }
}

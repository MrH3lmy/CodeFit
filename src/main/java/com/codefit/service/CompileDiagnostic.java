package com.codefit.service;

/**
 * One compiler diagnostic (error or warning), parsed from javac's plain-text output into structured
 * fields so a UI can link a diagnostic directly to the offending editor line/column (#163) instead of
 * just dumping raw compiler text. {@code column} is {@code null} when javac's output didn't include a
 * caret line to derive it from — every diagnostic always has a {@code line}, since javac's
 * {@code file:line:} prefix is always present.
 */
public record CompileDiagnostic(String file, int line, Integer column, boolean error, String message) {
}

package com.codefit.ui;

import java.sql.SQLException;

/**
 * Constant safe feedback; never render exception text that could include private
 * source/diagnostics.
 */
public final class PlayerErrorPresentation {
    private PlayerErrorPresentation() {}

    public static String message(Throwable failure, boolean resultSaved, boolean historical) {
        String prefix = resultSaved ? "Result saved — recommendation pending. " : "";
        if (historical)
            return prefix
                    + "This historical record cannot be replayed with the installed interpreter."
                    + " The saved record is retained; restore its compatible policy or assets"
                    + " before retrying.";
        String detail = null;
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            String text = failure.getMessage() == null ? "" : failure.getMessage();
            if (failure instanceof SQLException sql
                    && (sql.getErrorCode() == 5 || sql.getErrorCode() == 6))
                detail =
                        "The local database is busy. Keep your editor open and retry the same"
                                + " operation when it is available.";
            else if (text.startsWith("Diagnostic paused"))
                detail =
                        "This diagnostic is skipped or paused. Resume the baseline and task in"
                                + " Diagnostics before a new submission. Your draft is retained.";
            else if (text.startsWith("Another Java operation"))
                detail =
                        "Another Java operation is active. Wait for it to finish or cancel it in"
                                + " its workspace, then retry.";
            else if (text.startsWith("Presentation identity conflict"))
                detail =
                        "This presentation already has a familiarity declaration. Resume its"
                                + " existing receipt; later familiarity can be recorded without"
                                + " rewriting history.";
        }
        if (detail != null) return prefix + detail;
        if (resultSaved)
            return prefix
                    + "Refresh when local state is available; the committed evaluation is"
                    + " retained.";
        return "Operation not confirmed. Your editor is retained. Retry the same operation; check"
                + " the local database, exact installed assets and Java 21 availability.";
    }
}

package com.codefit.ui;

import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import java.util.Objects;

/**
 * Static, text-first status panel. Callers own state and actions; this component neither starts
 * work nor interprets learning data. Loading remains readable without an animated spinner.
 */
public final class SystemNotice extends VBox {
    public enum Kind {
        EMPTY("No items", "system-state-info"),
        LOADING("Loading", "system-state-info"),
        ERROR("Action needed", "system-state-error"),
        SUCCESS("Complete", "system-state-success"),
        INFO("Information", "system-state-info");

        private final String label;
        private final String style;

        Kind(String label, String style) {
            this.label = label;
            this.style = style;
        }
    }

    public SystemNotice(Kind kind, String title, String detail) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(title);
        Objects.requireNonNull(detail);
        getStyleClass().addAll("system-state", kind.style);
        Label state = label(kind.label, "system-eyebrow");
        Label heading = label(title, "topbar-title");
        Label body = label(detail, "muted");
        getChildren().addAll(state, heading, body);
        setMinWidth(0);
    }

    private static Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }
}

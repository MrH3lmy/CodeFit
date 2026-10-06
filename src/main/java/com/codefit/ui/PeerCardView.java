package com.codefit.ui;

import com.codefit.peer.protocol.MatchDuration;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * One paired peer, as a self-contained card: identity and connection status up top, one contextual
 * lead action plus a small secondary row, an on-demand "Today's progress" comparison, and the Study
 * Match block. It is a view only - every button reports through {@link Actions} and the owning
 * {@code PeerController} performs the actual service call, so this class contains no backend access
 * and no decisions beyond layout and visibility. Everything it shows is handed in as the pure
 * {@link PeerCardPresentation}/{@link StudyMatchPresentation} view-models.
 *
 * <p>Nodes carry stable ids ({@code peer-*}) so tests look them up by id, never by child index.
 *
 * <p>Layout is deliberately wrap-friendly: button groups are {@link FlowPane}s, long names/fingerprints
 * ellipsise (full text in a tooltip), and nothing has a fixed width beyond the two comparison value
 * columns - so the card reflows from the app's minimum window width up to a large one without a
 * horizontal scrollbar or a truncated button label.
 */
public final class PeerCardView extends VBox {

    /** What a card's buttons do. Called on the JavaFX thread. */
    public interface Actions {
        void connect(PeerCardView card);

        void disconnect(PeerCardView card);

        void compare(PeerCardView card);

        void share(PeerCardView card);

        void startMatch(PeerCardView card, MatchDuration duration);

        void acceptMatch(PeerCardView card);

        void declineMatch(PeerCardView card);

        void cancelMatch(PeerCardView card);

        void refreshMatch(PeerCardView card);

        /** The learner opened/closed the 15/30/60-minute picker; lets the owner keep it open across repaints. */
        void matchPickerChanged(PeerCardView card, boolean open);
    }

    /** Colour of a card's inline feedback line. */
    public enum FeedbackTone {
        INFO, SUCCESS, ERROR
    }

    private final PeerCardPresentation presentation;
    private final StudyMatchPresentation match;
    private final Actions actions;

    private final Label feedbackLabel = new Label();

    private final VBox comparisonSection = new VBox(8);
    private final VBox comparisonBody = new VBox(6);
    private final Label comparisonCutoffLabel = new Label();
    private final Label comparisonNoteLabel = new Label();

    private final Label matchDetailLabel = new Label();
    private final Label matchOfflineLabel = new Label();
    private final VBox matchProgressBody = new VBox(6);
    private final Label matchProgressCutoffLabel = new Label();
    private final Label matchVerdictLabel = new Label();
    private final VBox matchProgressSection = new VBox(8);
    private final Button startMatchButton;
    private final FlowPane durationPicker = new FlowPane(8, 8);

    private boolean matchPickerOpen;

    public PeerCardView(PeerCardPresentation presentation, StudyMatchPresentation match, boolean matchPickerOpen, Actions actions) {
        super(14);
        this.presentation = presentation;
        this.match = match;
        this.actions = actions;
        this.matchPickerOpen = matchPickerOpen && match.canStart();
        getStyleClass().add("peer-card");
        setMaxWidth(Double.MAX_VALUE);

        startMatchButton = button(match.startLabel() == null ? "Start match" : match.startLabel(),
                "peer-match-start-button", "peer-button-secondary");

        getChildren().addAll(buildHeader(), buildStatusDetail(), buildActionRow(), buildFeedback(),
                buildComparisonSection(), buildMatchSection());
    }

    public PeerCardPresentation presentation() {
        return presentation;
    }

    // --- header -----------------------------------------------------------------------------

    private Node buildHeader() {
        Label initial = new Label(presentation.initial());
        initial.getStyleClass().add("peer-avatar-initial");
        StackPane avatar = new StackPane(initial);
        avatar.getStyleClass().add("peer-avatar");
        avatar.setMinSize(40, 40);
        avatar.setPrefSize(40, 40);
        avatar.setMaxSize(40, 40);

        Label name = new Label(presentation.name());
        name.getStyleClass().add("peer-name");
        name.setId("peer-name-label");
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        name.setTooltip(new Tooltip(presentation.name()));

        Label fingerprint = new Label(presentation.shortFingerprint());
        fingerprint.getStyleClass().add("peer-fingerprint");
        fingerprint.setId("peer-fingerprint-label");
        fingerprint.setMinWidth(0);
        fingerprint.setMaxWidth(Double.MAX_VALUE);
        fingerprint.setTooltip(new Tooltip(presentation.fullFingerprint()));
        // A contact with no name at all is already titled by its short fingerprint - don't repeat it underneath.
        show(fingerprint, !presentation.fingerprintAsName());
        if (presentation.fingerprintAsName()) {
            name.setTooltip(new Tooltip(presentation.fullFingerprint()));
        }

        VBox identity = new VBox(2, name, fingerprint);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setMinWidth(0);
        HBox.setHgrow(identity, Priority.ALWAYS);

        HBox pill = statusPill(presentation.statusText(), switch (presentation.connection()) {
            case CONNECTED -> "status-success";
            case CONNECTING -> "status-warning";
            case OFFLINE -> "status-neutral";
        }, "peer-connection-status-label");

        HBox header = new HBox(12, avatar, identity, pill);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("peer-card-header");

        if (presentation.canDisconnect()) {
            MenuItem disconnect = new MenuItem("Disconnect");
            disconnect.setId("peer-disconnect-item");
            disconnect.getStyleClass().add("peer-menu-danger");
            disconnect.setOnAction(event -> actions.disconnect(this));
            MenuButton overflow = new MenuButton("···", null, disconnect);
            overflow.setId("peer-overflow-button");
            overflow.getStyleClass().add("peer-overflow-button");
            overflow.setAccessibleText("More actions for " + presentation.name());
            overflow.setMinWidth(Region.USE_PREF_SIZE);
            header.getChildren().add(overflow);
        }
        return header;
    }

    private Node buildStatusDetail() {
        Label detail = new Label(presentation.statusDetail() == null ? "" : presentation.statusDetail());
        detail.getStyleClass().add("peer-status-detail");
        detail.setId("peer-connection-detail-label");
        detail.setWrapText(true);
        show(detail, presentation.statusDetail() != null);
        return detail;
    }

    // --- actions ----------------------------------------------------------------------------

    private Node buildActionRow() {
        FlowPane row = new FlowPane(8, 8);
        row.getStyleClass().add("peer-actions-row");

        boolean connected = presentation.connection() == PeerCardPresentation.Connection.CONNECTED;
        switch (presentation.primaryAction()) {
            case CONNECT -> row.getChildren().add(connectButton("Connect", false));
            case CONNECTING -> row.getChildren().add(connectButton("Connecting…", true));
            case CONNECT_UNAVAILABLE -> row.getChildren().add(connectButton("Connect", true));
            case NONE -> { /* connected: Compare is the lead action */ }
        }

        Button compare = button("Compare today", "peer-compare-button", connected ? "peer-button-primary" : "peer-button-secondary");
        compare.setOnAction(event -> actions.compare(this));
        compare.setTooltip(new Tooltip("See how your day compares with " + presentation.name() + "'s"));

        Button share = button("Share progress", "peer-share-button", "peer-button-secondary");
        share.setOnAction(event -> actions.share(this));
        share.setTooltip(new Tooltip("Let " + presentation.name() + " see your progress for today"));

        row.getChildren().addAll(compare, share);
        return row;
    }

    private Button connectButton(String text, boolean disabled) {
        Button connect = button(text, "peer-connect-button", "peer-button-primary");
        connect.setDisable(disabled);
        if (!disabled) {
            connect.setOnAction(event -> actions.connect(this));
        }
        return connect;
    }

    private Node buildFeedback() {
        feedbackLabel.setId("peer-feedback-label");
        feedbackLabel.getStyleClass().add("peer-feedback");
        feedbackLabel.setWrapText(true);
        show(feedbackLabel, false);
        return feedbackLabel;
    }

    /** Contextual outcome of the last action on this card (connect failed, progress shared, ...). */
    public void setFeedback(String message, FeedbackTone tone) {
        feedbackLabel.getStyleClass().removeAll("feedback-error", "feedback-success");
        boolean has = message != null && !message.isBlank();
        feedbackLabel.setText(has ? message : "");
        if (has && tone == FeedbackTone.ERROR) {
            feedbackLabel.getStyleClass().add("feedback-error");
        } else if (has && tone == FeedbackTone.SUCCESS) {
            feedbackLabel.getStyleClass().add("feedback-success");
        }
        show(feedbackLabel, has);
    }

    // --- today's comparison -------------------------------------------------------------------

    private Node buildComparisonSection() {
        Label title = new Label("Today's progress");
        title.getStyleClass().add("peer-subsection-title");
        Button hide = button("Hide", "peer-comparison-hide-button", "peer-link-button");
        hide.setOnAction(event -> hideComparison());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer, hide);
        header.setAlignment(Pos.CENTER_LEFT);

        comparisonBody.setId("peer-comparison-body");
        comparisonCutoffLabel.setId("peer-comparison-cutoff-label");
        comparisonCutoffLabel.getStyleClass().add("comparison-footnote");
        comparisonNoteLabel.setId("peer-comparison-note-label");
        comparisonNoteLabel.getStyleClass().add("comparison-footnote");
        comparisonNoteLabel.setWrapText(true);
        show(comparisonCutoffLabel, false);
        show(comparisonNoteLabel, false);

        comparisonSection.getChildren().addAll(header, comparisonBody, comparisonCutoffLabel, comparisonNoteLabel);
        comparisonSection.getStyleClass().add("peer-subsection");
        comparisonSection.setId("peer-comparison-section");
        show(comparisonSection, false);
        return comparisonSection;
    }

    /** Shows a comparison result: a table of rows, or a short message for the empty/unavailable cases. */
    public void showComparison(PeerComparisonPresentation.Rendered rendered, Optional<Instant> cutoff) {
        renderResult(comparisonBody, comparisonCutoffLabel, comparisonNoteLabel, rendered, cutoff);
        show(comparisonSection, true);
    }

    public void showComparisonError(String message) {
        comparisonBody.getChildren().setAll(messageLabel(message));
        show(comparisonCutoffLabel, false);
        show(comparisonNoteLabel, false);
        show(comparisonSection, true);
    }

    public void hideComparison() {
        show(comparisonSection, false);
    }

    // --- study match -------------------------------------------------------------------------

    private Node buildMatchSection() {
        Label title = new Label("Study Match");
        title.getStyleClass().add("peer-subsection-title");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, spacer);
        header.setAlignment(Pos.CENTER_LEFT);
        if (match.stateLabel() != null) {
            header.getChildren().add(statusPill(match.stateLabel(), switch (match.tone()) {
                case SUCCESS -> "status-success";
                case WAITING -> "status-warning";
                case NEUTRAL -> "status-neutral";
            }, "peer-match-state-label"));
        }

        matchDetailLabel.setText(match.detail());
        matchDetailLabel.setId("peer-match-status-label");
        matchDetailLabel.getStyleClass().add("match-detail");
        matchDetailLabel.setWrapText(true);

        matchOfflineLabel.setText(match.offlineNote() == null ? "" : match.offlineNote());
        matchOfflineLabel.setId("peer-match-offline-label");
        matchOfflineLabel.getStyleClass().add("comparison-footnote");
        matchOfflineLabel.setWrapText(true);
        show(matchOfflineLabel, match.offlineNote() != null);

        FlowPane actionRow = new FlowPane(8, 8);
        actionRow.getStyleClass().add("peer-actions-row");

        Button accept = visibleButton("Accept", "peer-match-accept-button", "peer-button-primary", match.canAccept());
        accept.setOnAction(event -> actions.acceptMatch(this));
        Button decline = visibleButton("Decline", "peer-match-decline-button", "peer-button-secondary", match.canDecline());
        decline.setOnAction(event -> actions.declineMatch(this));
        Button cancel = visibleButton("Cancel invitation", "peer-match-cancel-button", "peer-button-secondary", match.canCancel());
        cancel.setOnAction(event -> actions.cancelMatch(this));
        boolean completed = match.state() == StudyMatchPresentation.State.COMPLETED;
        Button refresh = visibleButton(match.refreshLabel() == null ? "Refresh progress" : match.refreshLabel(),
                "peer-match-refresh-button", completed ? "peer-button-secondary" : "peer-button-primary", match.canRefresh());
        refresh.setOnAction(event -> actions.refreshMatch(this));

        startMatchButton.setOnAction(event -> setMatchPickerOpen(true, true));
        show(startMatchButton, match.canStart() && !matchPickerOpen);

        buildDurationPicker();

        actionRow.getChildren().addAll(accept, decline, cancel, refresh, startMatchButton);

        matchProgressCutoffLabel.setId("peer-match-progress-cutoff-label");
        matchProgressCutoffLabel.getStyleClass().add("comparison-footnote");
        matchProgressBody.setId("peer-match-progress-body");
        matchVerdictLabel.setId("peer-match-verdict-label");
        matchVerdictLabel.getStyleClass().add("match-verdict");
        matchVerdictLabel.setWrapText(true);
        show(matchProgressCutoffLabel, false);
        show(matchVerdictLabel, false);
        matchProgressSection.getChildren().addAll(matchVerdictLabel, matchProgressBody, matchProgressCutoffLabel);
        show(matchProgressSection, false);

        VBox section = new VBox(8, header, matchDetailLabel, matchOfflineLabel, actionRow, durationPicker, matchProgressSection);
        section.getStyleClass().add("peer-subsection");
        section.setId("peer-match-section");
        return section;
    }

    private void buildDurationPicker() {
        durationPicker.setId("peer-match-duration-picker");
        durationPicker.getStyleClass().add("peer-actions-row");
        durationPicker.setAlignment(Pos.CENTER_LEFT);
        Label prompt = new Label("How long?");
        prompt.getStyleClass().add("peer-picker-prompt");
        Button fifteen = durationButton("15 min", "peer-match-start-15-button", MatchDuration.FIFTEEN_MINUTES);
        Button thirty = durationButton("30 min", "peer-match-start-30-button", MatchDuration.THIRTY_MINUTES);
        Button sixty = durationButton("60 min", "peer-match-start-60-button", MatchDuration.SIXTY_MINUTES);
        Button cancel = button("Cancel", "peer-match-picker-cancel-button", "peer-link-button");
        cancel.setOnAction(event -> setMatchPickerOpen(false, true));
        durationPicker.getChildren().addAll(prompt, fifteen, thirty, sixty, cancel);
        show(durationPicker, matchPickerOpen);
    }

    private Button durationButton(String text, String id, MatchDuration duration) {
        Button button = button(text, id, "peer-button-secondary");
        button.setOnAction(event -> actions.startMatch(this, duration));
        return button;
    }

    private void setMatchPickerOpen(boolean open, boolean notify) {
        matchPickerOpen = open;
        show(durationPicker, open);
        show(startMatchButton, match.canStart() && !open);
        if (notify) {
            actions.matchPickerChanged(this, open);
        }
    }

    /** Shows the match's own progress comparison, plus the one-line verdict PeerMatchService derived (if any). */
    public void showMatchProgress(PeerComparisonPresentation.Rendered rendered, Optional<Instant> cutoff, Optional<String> verdict) {
        renderResult(matchProgressBody, matchProgressCutoffLabel, null, rendered, cutoff);
        matchVerdictLabel.setText(verdict.orElse(""));
        show(matchVerdictLabel, verdict.isPresent());
        show(matchProgressSection, true);
    }

    public void showMatchProgressError(String message) {
        matchProgressBody.getChildren().setAll(messageLabel(message));
        show(matchProgressCutoffLabel, false);
        show(matchVerdictLabel, false);
        show(matchProgressSection, true);
    }

    /**
     * Refreshes the "08:42 remaining" line of an active match; returns {@code true} once it has
     * reached zero so the owner can repaint the card into its completed state.
     */
    public boolean tick(Instant now) {
        if (match.state() != StudyMatchPresentation.State.ACTIVE || match.endsAt() == null) {
            return false;
        }
        matchDetailLabel.setText(match.detailAt(now));
        return !now.isBefore(match.endsAt());
    }

    // --- shared rendering ---------------------------------------------------------------------

    private void renderResult(VBox body, Label cutoffLabel, Label noteLabel, PeerComparisonPresentation.Rendered rendered,
                              Optional<Instant> cutoff) {
        body.getChildren().clear();
        boolean rows = rendered.kind() == PeerComparisonPresentation.Kind.ROWS;
        if (rows) {
            body.getChildren().add(buildGrid(rendered.rows()));
        } else {
            body.getChildren().add(messageLabel(rendered.message()));
        }
        // "Compared through …" is only truthful when real numbers were compared; a message-only result has no cutoff.
        boolean showCutoff = rendered.kind() != PeerComparisonPresentation.Kind.MESSAGE && cutoff.isPresent();
        if (showCutoff) {
            cutoffLabel.setText("Compared through " + PeerMetricPresentation.formatCutoff(cutoff.get(), ZoneId.systemDefault()));
        }
        show(cutoffLabel, showCutoff);
        if (noteLabel != null) {
            boolean offline = rows && presentation.connection() != PeerCardPresentation.Connection.CONNECTED;
            noteLabel.setText(offline ? PeerNamePresentation.capitalize(presentation.spokenName()) + " is offline. Showing last synced progress." : "");
            show(noteLabel, offline);
        }
    }

    private static Label messageLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("inline-note");
        label.setWrapText(true);
        return label;
    }

    /** "Metric | You | Peer", the one table layout "Compare today" and a match's progress share. */
    private GridPane buildGrid(List<PeerComparisonPresentation.Row> rows) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("comparison-grid");

        ColumnConstraints metricColumn = new ColumnConstraints();
        metricColumn.setHgrow(Priority.ALWAYS);
        metricColumn.setMinWidth(90);
        ColumnConstraints youColumn = new ColumnConstraints(72, 72, 72);
        ColumnConstraints peerColumn = new ColumnConstraints(88, 88, 88);
        grid.getColumnConstraints().addAll(metricColumn, youColumn, peerColumn);

        Label blank = new Label();
        Label youHeader = headerCell("You", "comparison-header-you");
        Label peerHeader = headerCell(presentation.shortName(), "comparison-header-peer");
        peerHeader.setTooltip(new Tooltip(presentation.name()));
        grid.addRow(0, blank, youHeader, peerHeader);

        int index = 1;
        for (PeerComparisonPresentation.Row row : rows) {
            boolean last = index == rows.size();
            Label metric = cell(row.label(), "comparison-metric-cell", last, Pos.CENTER_LEFT);
            metric.setWrapText(true);
            Label you = cell(row.you(), "comparison-value-cell", last, Pos.CENTER_RIGHT);
            Label peer = cell(row.peer(), "comparison-value-cell", last, Pos.CENTER_RIGHT);
            grid.addRow(index++, metric, you, peer);
        }
        return grid;
    }

    private static Label headerCell(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("comparison-header-cell", styleClass);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER_RIGHT);
        label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        return label;
    }

    private static Label cell(String text, String styleClass, boolean last, Pos alignment) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClass, "comparison-cell");
        label.setMaxHeight(Double.MAX_VALUE); // fill the row so every cell's divider sits on the same line
        if (last) {
            label.getStyleClass().add("comparison-cell-last");
        }
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(alignment);
        return label;
    }

    // --- small builders -------------------------------------------------------------------------

    private static HBox statusPill(String text, String toneClass, String labelId) {
        Label dot = new Label("●");
        dot.getStyleClass().add("status-dot");
        Label label = new Label(text);
        label.getStyleClass().add("status-pill-text");
        label.setId(labelId);
        HBox pill = new HBox(6, dot, label);
        pill.getStyleClass().addAll("status-pill", toneClass);
        pill.setAlignment(Pos.CENTER);
        pill.setMinWidth(Region.USE_PREF_SIZE);
        return pill;
    }

    private static Button button(String text, String id, String styleClass) {
        Button button = new Button(text);
        button.setId(id);
        button.getStyleClass().add(styleClass);
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private static Button visibleButton(String text, String id, String styleClass, boolean visible) {
        Button button = button(text, id, styleClass);
        show(button, visible);
        return button;
    }

    /** Visible AND managed together, so a hidden control never reserves space or becomes a click target. */
    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}

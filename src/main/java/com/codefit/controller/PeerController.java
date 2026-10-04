package com.codefit.controller;

import com.codefit.peer.comparison.SnapshotComparisonEngine;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactAddress;
import com.codefit.peer.identity.ContactPermission;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.identity.PermissionGrant;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.identity.IdentityFingerprint;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.transport.ConnectionOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.service.ContactService;
import com.codefit.service.IdentityService;
import com.codefit.service.NetworkingService;
import com.codefit.service.PeerComparisonService;
import com.codefit.service.PeerSyncOutboxService;
import com.codefit.ui.PeerConnectionPresenter;
import com.codefit.ui.PeerDialGate;
import com.codefit.ui.PeerSessionHolder;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The one screen this PR adds: lets two real CodeFit instances on the same LAN pair and establish a
 * real, authenticated P2P connection using only this UI. Every actual networking/identity/contact
 * operation is delegated to the existing, already-reviewed {@link NetworkingService}/
 * {@link ContactService}/{@link IdentityService} - nothing here reimplements any part of pairing,
 * transport, TLS, or certificate pinning. This controller is pure orchestration and presentation.
 *
 * <h2>Why pairing needs two invitations, not one</h2>
 * Each side's transport listener only accepts a handshake from an identity it already has a {@code
 * PENDING} or {@code PAIRED} contact row for ({@code KnownContactLookup}) - "parsing an invitation
 * alone" or "being dialed" never establishes trust by itself. So <strong>both</strong> people must
 * create their own invitation, send it to the other, and have the other register and accept it,
 * before either side's dial can succeed - this screen's copy and the "Paired Peers" panel's own
 * helper text say this explicitly, and a rejection caused by the other side simply not having done
 * that yet ({@code UNKNOWN_IDENTITY}/{@code NOT_PAIRED}) is surfaced with that specific explanation
 * (see {@link PeerConnectionPresenter}), not a generic failure.
 *
 * <h2>Threading</h2>
 * Everything that touches the vault, disk, or a blocking socket operation runs on its own daemon
 * {@link Task} thread via {@link #runPeerAction}; {@code Task}'s {@code setOnSucceeded}/
 * {@code setOnFailed} already run on the JavaFX application thread, so the callbacks passed to it may
 * touch {@code Node}s directly. {@code NetworkingService.connectToContact} is already asynchronous
 * ({@code CompletableFuture} on the transport's own dial pool); its completion callback is wrapped in
 * {@link Platform#runLater} explicitly, since {@code CompletableFuture} gives no such guarantee.
 * Transport connection events arrive on arbitrary background threads via
 * {@link PeerConnectionPresenter#setOnChange} - the subscriber installed in {@link #initialize()}
 * does nothing but schedule {@link #refreshContacts()} with {@code Platform.runLater}; no event ever
 * touches a {@code Node} on its own thread.
 *
 * <h2>Secrets</h2>
 * The vault passphrase is read from its {@code PasswordField} as a {@code char[]} exactly once per
 * action, the field is cleared immediately after, and the array is wiped ({@link Arrays#fill}) in a
 * {@code finally} right after the one backend call that consumes it - never logged, never retained as
 * a field, never held any longer than that one call.
 */
public class PeerController {

    // Package-private (not private): PeerControllerTest, in this same package, reads these directly
    // to assert rendered state without reflection boilerplate - still inaccessible outside this package.
    @FXML Label identityStatusLabel;
    @FXML PasswordField vaultPassphraseField;
    @FXML Button createIdentityButton;
    @FXML Button enableNetworkingButton;
    @FXML Button disableNetworkingButton;
    @FXML Label networkingStatusLabel;

    @FXML Button createInvitationButton;
    @FXML TextArea myInvitationArea;
    @FXML Button copyInvitationButton;

    @FXML TextArea peerInvitationArea;
    @FXML Button parseInvitationButton;
    @FXML Label parsedFingerprintLabel;
    @FXML Button registerAndAcceptButton;

    @FXML Label noContactsLabel;
    @FXML VBox contactsBox;

    @FXML Label statusLabel;

    /**
     * A manual "Connect" click wants fast feedback, not {@link RetryPolicy#standard()}'s
     * minutes-long backoff ceiling (6 attempts up to a 60s cap each - meant for an unattended
     * background reconnect, not someone watching a button). A person who sees "could not connect"
     * quickly can click Connect again once it finishes - see {@link #dialGate} for why a click while
     * one is still in flight cannot start a second, independent attempt. This screen has no Cancel
     * action for an attempt already in flight (see the end-of-PR limitations).
     */
    private static final RetryPolicy MANUAL_CONNECT_RETRY_POLICY = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 0.2);

    private final IdentityService identityService;
    private final NetworkingService networkingService;
    private final ContactService contactService;
    private final PeerConnectionPresenter presenter;
    /** Orchestrates the read-only "compare today's progress" side only - never touches sync
     *  internals, {@code PeerConnection}, or {@code UnlockedIdentity} (see its own javadoc). */
    private final PeerComparisonService comparisonService;
    /** The existing #184 outbox model "Share Today's Progress" approves today's window into -
     *  never a new publication mechanism. */
    private final PeerSyncOutboxService outboxService;

    /**
     * Ensures at most one manual dial per contact is ever in flight from this UI at a time - see
     * {@link PeerDialGate}'s own javadoc. This is UI-owned bookkeeping only, never consulted as
     * connection state: {@code NetworkingService.activeConnection} remains the sole authority on
     * whether a contact is actually connected.
     */
    private final PeerDialGate dialGate;

    /** Staged between a successful {@link #parseInvitation()} and {@link #registerAndAccept()}. */
    SignedInvitation parsedPeerInvitation;

    public PeerController() {
        this(new IdentityService(), PeerSessionHolder.networkingService(), new ContactService(), PeerSessionHolder.presenter(),
                PeerSessionHolder.dialGate(), new PeerComparisonService(), new PeerSyncOutboxService());
    }

    PeerController(IdentityService identityService, NetworkingService networkingService, ContactService contactService,
                    PeerConnectionPresenter presenter, PeerDialGate dialGate) {
        this(identityService, networkingService, contactService, presenter, dialGate,
                new PeerComparisonService(), new PeerSyncOutboxService());
    }

    PeerController(IdentityService identityService, NetworkingService networkingService, ContactService contactService,
                    PeerConnectionPresenter presenter, PeerDialGate dialGate, PeerComparisonService comparisonService,
                    PeerSyncOutboxService outboxService) {
        this.identityService = identityService;
        this.networkingService = networkingService;
        this.contactService = contactService;
        this.presenter = presenter;
        this.dialGate = dialGate;
        this.comparisonService = comparisonService;
        this.outboxService = outboxService;
    }

    @FXML
    public void initialize() {
        presenter.setOnChange(() -> Platform.runLater(this::refreshContacts));
        refreshIdentityStatus();
        refreshNetworkingStatus();
        refreshContacts();
    }

    @FXML
    public void createIdentity() {
        char[] passphrase = takePassphrase();
        if (passphrase == null) {
            return;
        }
        runPeerAction(() -> {
            try {
                return identityService.createIdentity(passphrase, now());
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }, summary -> {
            refreshIdentityStatus();
            setStatus("Identity created.");
        });
    }

    @FXML
    public void enableNetworking() {
        char[] passphrase = takePassphrase();
        if (passphrase == null) {
            return;
        }
        runPeerAction(() -> {
            try {
                networkingService.enableNetworking(passphrase, 0, now());
                return null;
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }, (Void ignored) -> {
            refreshNetworkingStatus();
            setStatus("Networking enabled.");
        });
    }

    @FXML
    public void disableNetworking() {
        runPeerAction(() -> {
            networkingService.disableNetworking();
            return null;
        }, (Void ignored) -> {
            // No UI-owned dial state may outlive networking being disabled - whatever the torn-down
            // dial futures eventually report for themselves, this screen no longer owns any of them.
            dialGate.clear();
            refreshNetworkingStatus();
            refreshContacts();
            setStatus("Networking disabled.");
        });
    }

    @FXML
    public void createInvitation() {
        char[] passphrase = takePassphrase();
        if (passphrase == null) {
            return;
        }
        runPeerAction(() -> {
            try {
                return networkingService.createInvitation(passphrase, Duration.ofDays(1), now());
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }, signed -> {
            myInvitationArea.setText(InvitationCodec.toBase64(signed));
            setStatus("Invitation created - copy it and send it to the other person.");
        });
    }

    @FXML
    public void copyInvitation() {
        String text = myInvitationArea.getText();
        if (text == null || text.isBlank()) {
            setStatus("Create an invitation first.");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        setStatus("Copied to clipboard.");
    }

    @FXML
    public void parseInvitation() {
        String text = peerInvitationArea.getText();
        if (text == null || text.isBlank()) {
            setStatus("Paste the other person's invitation first.");
            return;
        }
        runPeerAction(() -> networkingService.parseInvitationBase64(text), signed -> {
            parsedPeerInvitation = signed;
            parsedFingerprintLabel.setText("Their fingerprint: " + IdentityFingerprint.format(signed.invitation().identityKey().id())
                    + " - verify this out of band with them before accepting.");
            parsedFingerprintLabel.setVisible(true);
            parsedFingerprintLabel.setManaged(true);
            registerAndAcceptButton.setDisable(false);
            setStatus(null);
        }, error -> {
            resetParsedInvitation();
            showError(error);
        });
    }

    @FXML
    public void registerAndAccept() {
        SignedInvitation signed = parsedPeerInvitation;
        if (signed == null) {
            return;
        }
        runPeerAction(() -> {
            Contact pending = networkingService.registerPendingContactFromInvitation(signed, now());
            return contactService.acceptInvitation(pending.id(), now());
        }, contact -> {
            resetParsedInvitation();
            peerInvitationArea.clear();
            refreshContacts();
            setStatus("Paired with " + displayNameFor(contact) + ".");
        });
    }

    private void resetParsedInvitation() {
        parsedPeerInvitation = null;
        parsedFingerprintLabel.setVisible(false);
        parsedFingerprintLabel.setManaged(false);
        registerAndAcceptButton.setDisable(true);
    }

    /**
     * Starts a manual dial for one contact, but only if nothing else from this UI is already dialing
     * it: {@link #dialGate} is acquired first, atomically, as the very first thing this method does -
     * before the known-address check, before the synchronous {@code connectToContact} call, before
     * anything - so two back-to-back clicks (even two already queued on the FX thread before the
     * first click's own {@link #refreshContacts()} call ever disables the button) can never both
     * proceed past this guard. Exactly one of them acquires ownership; the other returns immediately,
     * doing nothing further. Ownership is released in every exit path - the two early returns below,
     * the synchronous-failure catch, and the async {@code whenComplete} (which always runs, on
     * success, failure, or cancellation of the returned future) - so it can never be left hanging.
     */
    private void connectTo(Contact contact) {
        if (!dialGate.tryAcquire(contact.id())) {
            return; // already dialing this contact from this UI - the row already shows "Connecting…"
        }
        List<ContactAddress> addresses = contactService.knownAddresses(contact.id());
        if (addresses.isEmpty()) {
            dialGate.release(contact.id());
            setStatus("No known address for " + displayNameFor(contact) + " yet - ask them to resend their invitation.");
            return;
        }
        PeerAddress address = addresses.get(0).address();
        // connectToContact's own javadoc documents it can throw IllegalStateException synchronously
        // (not paired, or no transport key ever observed for this contact) before ever returning the
        // CompletableFuture - that path needs handling here too, not only the async outcome below.
        try {
            networkingService.connectToContact(contact.id(), address, MANUAL_CONNECT_RETRY_POLICY, new AtomicBoolean(false))
                    .whenComplete((outcome, throwable) -> Platform.runLater(() -> {
                        dialGate.release(contact.id());
                        refreshContacts();
                        if (throwable != null) {
                            setStatus("Connection attempt failed: " + messageOf(throwable));
                        } else if (!outcome.result().authenticated()) {
                            setStatus("Could not connect to " + displayNameFor(contact) + ": " + describeFailure(outcome.result()));
                        } else {
                            setStatus("Connected to " + displayNameFor(contact) + ".");
                        }
                    }));
        } catch (IllegalStateException cannotDial) {
            dialGate.release(contact.id());
            setStatus("Could not connect to " + displayNameFor(contact) + ": " + messageOf(cannotDial));
        }
        refreshContacts();
    }

    private void disconnectFrom(Contact contact) {
        runPeerAction(() -> {
            networkingService.disconnect(contact.identityId());
            return null;
        }, (Void ignored) -> {
            refreshContacts();
            setStatus("Disconnected from " + displayNameFor(contact) + ".");
        });
    }

    private void refreshIdentityStatus() {
        Optional<LocalIdentitySummary> identity = networkingService.currentIdentity();
        if (identity.isPresent()) {
            identityStatusLabel.setText("Identity: " + IdentityFingerprint.format(identity.get().id()));
            createIdentityButton.setDisable(true);
        } else {
            identityStatusLabel.setText("No identity yet - enter a vault passphrase above and create one.");
            createIdentityButton.setDisable(false);
        }
    }

    private void refreshNetworkingStatus() {
        boolean enabled = networkingService.isNetworkingEnabled();
        enableNetworkingButton.setDisable(enabled);
        disableNetworkingButton.setDisable(!enabled);
        createInvitationButton.setDisable(!enabled);
        networkingStatusLabel.setText(enabled
                ? "Networking enabled on port " + networkingService.listeningPort().orElse(-1) + "."
                : "Networking disabled.");
    }

    /** Rebuilds the paired-peer list from scratch: {@code ContactService} is the source of contacts,
     *  {@code NetworkingService.activeConnection} is the ground truth for "connected right now", and
     *  {@link PeerConnectionPresenter} only ever adds detail for why a contact that is NOT currently
     *  connected isn't - exactly the reconciliation {@link PeerConnectionPresenter}'s own javadoc
     *  describes. Safe to call from the JavaFX thread only (mutates {@code Node}s directly) - every
     *  caller either already is the JavaFX thread or got there via {@code Platform.runLater} first. */
    private void refreshContacts() {
        List<Contact> contacts = contactService.listContacts();
        noContactsLabel.setVisible(contacts.isEmpty());
        noContactsLabel.setManaged(contacts.isEmpty());
        contactsBox.getChildren().clear();
        for (Contact contact : contacts) {
            contactsBox.getChildren().add(buildContactRow(contact));
        }
    }

    private Node buildContactRow(Contact contact) {
        boolean connected = networkingService.activeConnection(contact.identityId()).isPresent();
        // dialGate, not any transport event, is authoritative for "connecting" here: a manual dial
        // this UI just started may not yet have produced any ConnectionEvent at all (the attempt is
        // still queued behind the dial pool's own concurrency limit, say), but the row must still
        // reflect that this UI already owns an in-flight request for this contact.
        boolean dialing = !connected && dialGate.isInFlight(contact.id());
        String status = dialing ? "Connecting…" : presenter.displayTextFor(contact.identityId(), connected);

        Label nameLabel = new Label(displayNameFor(contact));
        nameLabel.getStyleClass().add("problem-row-title");
        nameLabel.setWrapText(true);
        Label statusLabel = new Label(status);
        statusLabel.getStyleClass().add("problem-row-subtitle");
        statusLabel.setWrapText(true);
        // Appended after statusLabel (index 1), never replacing it - PeerControllerTest's own
        // rowStatusLabel helper reads index 1 directly and must keep working unchanged.
        Label comparisonResultLabel = new Label();
        comparisonResultLabel.getStyleClass().add("dashboard-card-helper");
        comparisonResultLabel.setWrapText(true);
        comparisonResultLabel.setVisible(false);
        comparisonResultLabel.setManaged(false);
        VBox textColumn = new VBox(2, nameLabel, statusLabel, comparisonResultLabel);
        textColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textColumn, Priority.ALWAYS);

        Button actionButton;
        if (connected) {
            actionButton = new Button("Disconnect");
            actionButton.setOnAction(event -> disconnectFrom(contact));
        } else if (dialing) {
            // Disabled, not merely left clickable-but-ignored: a disabled button is itself the
            // feedback that this contact already has a request in flight, per the review requirement
            // to prefer disabling/replacing the action over silently ignoring a click with no sign.
            actionButton = new Button("Connecting…");
            actionButton.setDisable(true);
        } else {
            actionButton = new Button("Connect");
            actionButton.setOnAction(event -> connectTo(contact));
        }

        // Appended after actionButton (index 1), never before it - PeerControllerTest's own
        // fireConnectButton helper fires index 1 directly and must keep firing the same button.
        Button shareButton = new Button("Share Today's Progress");
        shareButton.setOnAction(event -> shareTodaysProgress(contact));
        Button compareButton = new Button("Compare Today's Progress");
        compareButton.setOnAction(event -> compareTodaysProgress(contact, comparisonResultLabel));

        HBox row = new HBox(10, textColumn, actionButton, shareButton, compareButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    /**
     * Grants {@link SharingScope#DAILY_SUMMARY} (merging into, never replacing, any scopes already
     * granted - {@code ContactService.updatePermissions} itself always replaces the complete grant,
     * mirroring {@code ConsentRevision}) and approves today's real {@link ComparisonWindow} into the
     * existing #184 outbox model. {@code historicalWindowDays} is raised to at least 1 only if it
     * was not already enough: a default/{@code null} grant otherwise anchors history at {@code
     * ContactPermission.updatedAt()}, which would exclude today's own window start whenever this
     * grant happens after local midnight. If a connection to this contact is already live, also
     * triggers {@link NetworkingService#syncOutboxNow} so the peer receives it immediately rather
     * than waiting for a future reconnect - best-effort: a failure here (the connection drops mid-
     * send, say) never fails this action, since the approval itself is already durable and the next
     * automatic connection-established send will simply resend it.
     */
    private void shareTodaysProgress(Contact contact) {
        runPeerAction(() -> {
            Instant now = now();
            Optional<ContactPermission> existing = contactService.permissionsFor(contact.id());
            List<SharingScope> scopes = existing.map(ContactPermission::scopes).orElse(List.of());
            List<SharingScope> mergedScopes;
            if (scopes.contains(SharingScope.DAILY_SUMMARY)) {
                mergedScopes = scopes;
            } else {
                mergedScopes = new ArrayList<>(scopes);
                mergedScopes.add(SharingScope.DAILY_SUMMARY);
            }
            Integer historicalWindowDays = existing.map(ContactPermission::historicalWindowDays).orElse(null);
            if (historicalWindowDays == null || historicalWindowDays < 1) {
                historicalWindowDays = 1;
            }
            Instant expiresAt = existing.map(ContactPermission::expiresAt).orElse(null);
            boolean allowForwarding = existing.map(ContactPermission::allowForwarding).orElse(false);
            contactService.updatePermissions(contact.id(),
                    new PermissionGrant(mergedScopes, historicalWindowDays, expiresAt, allowForwarding), now);

            ZoneId zone = ZoneId.systemDefault();
            ComparisonWindow todayWindow = ComparisonWindow.day(LocalDate.now(zone), zone);
            outboxService.approveProgressSummary(contact.id(), todayWindow, now);

            if (networkingService.activeConnection(contact.identityId()).isPresent()) {
                try {
                    networkingService.syncOutboxNow(contact.id());
                } catch (IOException | RuntimeException alreadyApprovedWillRetryAutomatically) {
                    // Best-effort - see this method's own javadoc.
                }
            }
            return null;
        }, (Void ignored) -> setStatus("Sharing today's progress with " + displayNameFor(contact) + "."));
    }

    /**
     * Reads this device's own real today's progress and the named peer's already-synced/cached one
     * (never manufactured here), delegating every actual decision to {@link PeerComparisonService}/
     * {@code SnapshotComparisonEngine}. Never touches {@code PeerSyncSessionService}, {@code
     * PeerConnection}, or {@code UnlockedIdentity} - this screen only ever calls the one narrow,
     * already-reviewed comparison entry point.
     */
    private void compareTodaysProgress(Contact contact, Label resultLabel) {
        runPeerAction(() -> comparisonService.compareTodayWith(contact.id()), comparison -> {
            resultLabel.setText(describeComparison(comparison));
            resultLabel.setVisible(true);
            resultLabel.setManaged(true);
        }, error -> {
            resultLabel.setText("Comparison failed: " + messageOf(error));
            resultLabel.setVisible(true);
            resultLabel.setManaged(true);
        });
    }

    /**
     * Renders {@code SnapshotComparisonEngine}'s own real result as plain text - never a new scoring
     * or "winner" computation, per this feature's own scope. The comparable/descriptive-only branch
     * lists the engine's own {@code MetricComparison} rows as-is.
     */
    private static String describeComparison(SnapshotComparisonEngine.ProgressComparison comparison) {
        return switch (comparison.state()) {
            case UNAVAILABLE -> switch (comparison.reason()) {
                case RIGHT_NOT_SHARED -> "Not shared by peer.";
                case RIGHT_MISSING -> "Waiting for peer progress to sync.";
                case RIGHT_STALE -> "Peer progress is stale.";
                // A live "today" comparison's two independent captures only align when their elapsed-
                // since-midnight durations are exactly equal (SnapshotComparisonEngine's own alignment
                // rule - see its own test for why this is deliberate, not a bug); in practice this, not
                // the top-level INCOMPATIBLE state below, is the common real shape of "incompatible
                // windows" for two people studying at different moments of their day.
                case CUTOFF_MISMATCH, COMPLETE_PARTIAL_MISMATCH ->
                        "Today's windows are incompatible (" + comparison.reason() + ").";
                default -> "Comparison unavailable (" + comparison.reason() + ").";
            };
            case INCOMPATIBLE -> "Today's windows are incompatible (" + comparison.reason() + ").";
            case COMPARABLE, DESCRIPTIVE_ONLY -> describeMetrics(comparison);
        };
    }

    private static String describeMetrics(SnapshotComparisonEngine.ProgressComparison comparison) {
        StringBuilder text = new StringBuilder("Comparison available");
        if (comparison.state() == SnapshotComparisonEngine.ComparisonState.DESCRIPTIVE_ONLY) {
            text.append(" (descriptive only)");
        }
        text.append(':');
        for (SnapshotComparisonEngine.MetricComparison metric : comparison.metrics()) {
            text.append("\n - ").append(metric.metricId()).append(": ");
            if (metric.left().isPresent() && metric.right().isPresent()) {
                text.append(metric.left().get().value()).append(" vs ").append(metric.right().get().value());
            } else {
                text.append(metric.state()).append(" (").append(metric.reason()).append(')');
            }
        }
        return text.toString();
    }

    private static String displayNameFor(Contact contact) {
        if (contact.alias() != null && !contact.alias().isBlank()) {
            return contact.alias();
        }
        if (contact.displayName() != null && !contact.displayName().isBlank()) {
            return contact.displayName();
        }
        return contact.fingerprint();
    }

    /**
     * {@code Instant.now()} can carry sub-millisecond precision that protocol timestamp validation
     * ({@code WriterEpoch}/{@code ProtocolTime}) rejects outright - {@code NetworkingService}'s own
     * public methods already truncate internally (see its private {@code wireTime}), but
     * {@code IdentityService}/{@code ContactService} do not, so any caller going through them
     * directly must truncate first, exactly like every other direct caller in this codebase already
     * does (e.g. the two-process demo harnesses' own {@code nowMillis()}).
     */
    private static Instant now() {
        return Instant.ofEpochMilli(Instant.now().toEpochMilli());
    }

    private static String describeFailure(ConnectionOutcome outcome) {
        String reason = outcome.failureReason() == null ? "rejected" : outcome.failureReason().name();
        String detail = outcome.detail();
        return detail == null || detail.isBlank() ? reason : reason + " - " + detail;
    }

    private char[] takePassphrase() {
        String text = vaultPassphraseField.getText();
        if (text == null || text.isEmpty()) {
            setStatus("Enter a vault passphrase first.");
            return null;
        }
        char[] passphrase = text.toCharArray();
        vaultPassphraseField.clear();
        return passphrase;
    }

    private void setStatus(String message) {
        boolean hasStatus = message != null && !message.isBlank();
        statusLabel.setText(hasStatus ? message : "");
        statusLabel.setVisible(hasStatus);
        statusLabel.setManaged(hasStatus);
    }

    private void showError(Throwable error) {
        setStatus(messageOf(error));
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /** Runs {@code work} on its own daemon thread, never the JavaFX application thread, and delivers
     *  the result back on the JavaFX thread via {@link Task}'s own guarantee for
     *  {@code setOnSucceeded}/{@code setOnFailed} - callbacks passed here may touch {@code Node}s
     *  directly. On failure, shows the exception's message as the status line. */
    private <T> void runPeerAction(Callable<T> work, Consumer<T> onSuccess) {
        runPeerAction(work, onSuccess, this::showError);
    }

    private <T> void runPeerAction(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> onFailure.accept(task.getException()));
        Thread thread = new Thread(task, "codefit-peer-ui-action");
        thread.setDaemon(true);
        thread.start();
    }
}

package com.codefit.controller;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactAddress;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.identity.IdentityFingerprint;
import com.codefit.peer.transport.ConnectionOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.service.ContactService;
import com.codefit.service.IdentityService;
import com.codefit.service.NetworkingService;
import com.codefit.ui.PeerConnectionPresenter;
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

import java.time.Duration;
import java.time.Instant;
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
     * quickly can just click Connect again; they cannot cancel an attempt already in flight from
     * this screen (see the end-of-PR limitations).
     */
    private static final RetryPolicy MANUAL_CONNECT_RETRY_POLICY = new RetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 0.2);

    private final IdentityService identityService;
    private final NetworkingService networkingService;
    private final ContactService contactService;
    private final PeerConnectionPresenter presenter;

    /** Staged between a successful {@link #parseInvitation()} and {@link #registerAndAccept()}. */
    SignedInvitation parsedPeerInvitation;

    public PeerController() {
        this(new IdentityService(), PeerSessionHolder.networkingService(), new ContactService(), PeerSessionHolder.presenter());
    }

    PeerController(IdentityService identityService, NetworkingService networkingService, ContactService contactService,
                    PeerConnectionPresenter presenter) {
        this.identityService = identityService;
        this.networkingService = networkingService;
        this.contactService = contactService;
        this.presenter = presenter;
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

    private void connectTo(Contact contact) {
        List<ContactAddress> addresses = contactService.knownAddresses(contact.id());
        if (addresses.isEmpty()) {
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
        String status = presenter.displayTextFor(contact.identityId(), connected);

        Label nameLabel = new Label(displayNameFor(contact));
        nameLabel.getStyleClass().add("problem-row-title");
        nameLabel.setWrapText(true);
        Label statusLabel = new Label(status);
        statusLabel.getStyleClass().add("problem-row-subtitle");
        statusLabel.setWrapText(true);
        VBox textColumn = new VBox(2, nameLabel, statusLabel);
        textColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textColumn, Priority.ALWAYS);

        Button actionButton = new Button(connected ? "Disconnect" : "Connect");
        actionButton.setOnAction(event -> {
            if (connected) {
                disconnectFrom(contact);
            } else {
                connectTo(contact);
            }
        });

        HBox row = new HBox(10, textColumn, actionButton);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
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

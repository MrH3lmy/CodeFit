package com.codefit.controller;

import com.codefit.peer.comparison.SnapshotComparisonEngine;
import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactAddress;
import com.codefit.peer.identity.IdentityFingerprint;
import com.codefit.peer.identity.LocalIdentitySummary;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.invitation.SignedInvitation;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.ProgressSummary;
import com.codefit.peer.protocol.SharingScope;
import com.codefit.peer.transport.ConnectionOutcome;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.peer.transport.RetryPolicy;
import com.codefit.service.ContactService;
import com.codefit.service.IdentityService;
import com.codefit.service.NetworkingService;
import com.codefit.service.PeerComparisonService;
import com.codefit.service.PeerMatchService;
import com.codefit.service.PeerSyncOutboxService;
import com.codefit.ui.PeerCardPresentation;
import com.codefit.ui.PeerCardView;
import com.codefit.ui.PeerComparisonPresentation;
import com.codefit.ui.PeerConnectionPresenter;
import com.codefit.ui.PeerDialGate;
import com.codefit.ui.PeerErrorPresentation;
import com.codefit.ui.PeerNamePresentation;
import com.codefit.ui.PeerNetworkPresentation;
import com.codefit.ui.PeerSessionHolder;
import com.codefit.ui.StudyMatchPresentation;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * The Peers screen: lets two real CodeFit instances on the same LAN pair and establish a
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
 * before either side's dial can succeed - the "Add a peer" panel's copy says this explicitly, and a rejection caused by the other side simply not having done
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
    @FXML VBox statusSection;
    @FXML Label identityStatusLabel;
    @FXML Label networkDot;
    @FXML Label networkingStatusLabel;
    @FXML Label networkingDetailLabel;
    @FXML VBox setupBox;
    @FXML Label setupHintLabel;
    @FXML PasswordField vaultPassphraseField;
    @FXML Button createIdentityButton;
    @FXML Button enableNetworkingButton;
    @FXML Button disableNetworkingButton;
    @FXML Label networkingFeedbackLabel;

    @FXML VBox peersSection;
    @FXML Label peersSummaryLabel;
    @FXML Button addPeerButton;
    @FXML VBox addPeerPanel;

    @FXML PasswordField invitationPassphraseField;
    @FXML Button createInvitationButton;
    @FXML Label invitationHintLabel;
    @FXML VBox ownFingerprintBox;
    @FXML Label ownFingerprintLabel;
    @FXML VBox invitationResultBox;
    @FXML TextArea myInvitationArea;
    @FXML Button copyInvitationButton;
    @FXML Label invitationFeedbackLabel;

    @FXML TextArea peerInvitationArea;
    @FXML Button parseInvitationButton;
    @FXML VBox verifyBox;
    @FXML Label parsedFingerprintLabel;
    @FXML Button registerAndAcceptButton;
    @FXML Label pairingFeedbackLabel;

    @FXML VBox emptyPeersBox;
    @FXML Button emptyAddPeerButton;
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
    /** Owns the Study Match lifecycle/persistence and match-window progress comparison - never
     *  touches {@code PeerSyncSessionService}/{@code PeerConnection}/{@code UnlockedIdentity} (see
     *  its own javadoc). */
    private final PeerMatchService matchService;

    /**
     * Ensures at most one manual dial per contact is ever in flight from this UI at a time - see
     * {@link PeerDialGate}'s own javadoc. This is UI-owned bookkeeping only, never consulted as
     * connection state: {@code NetworkingService.activeConnection} remains the sole authority on
     * whether a contact is actually connected.
     */
    private final PeerDialGate dialGate;

    private static final System.Logger LOG = System.getLogger(PeerController.class.getName());

    /** Staged between a successful {@link #parseInvitation()} and {@link #registerAndAccept()}. */
    SignedInvitation parsedPeerInvitation;

    public PeerController() {
        this(new IdentityService(), PeerSessionHolder.networkingService(), new ContactService(), PeerSessionHolder.presenter(),
                PeerSessionHolder.dialGate(), new PeerComparisonService(), new PeerSyncOutboxService(), new PeerMatchService());
    }

    PeerController(IdentityService identityService, NetworkingService networkingService, ContactService contactService,
                    PeerConnectionPresenter presenter, PeerDialGate dialGate) {
        this(identityService, networkingService, contactService, presenter, dialGate,
                new PeerComparisonService(), new PeerSyncOutboxService(), new PeerMatchService());
    }

    PeerController(IdentityService identityService, NetworkingService networkingService, ContactService contactService,
                    PeerConnectionPresenter presenter, PeerDialGate dialGate, PeerComparisonService comparisonService,
                    PeerSyncOutboxService outboxService) {
        this(identityService, networkingService, contactService, presenter, dialGate, comparisonService, outboxService,
                new PeerMatchService());
    }

    PeerController(IdentityService identityService, NetworkingService networkingService, ContactService contactService,
                    PeerConnectionPresenter presenter, PeerDialGate dialGate, PeerComparisonService comparisonService,
                    PeerSyncOutboxService outboxService, PeerMatchService matchService) {
        this.identityService = identityService;
        this.networkingService = networkingService;
        this.contactService = contactService;
        this.presenter = presenter;
        this.dialGate = dialGate;
        this.comparisonService = comparisonService;
        this.outboxService = outboxService;
        this.matchService = matchService;
    }

    // --- per-contact UI state that must survive a card repaint ---------------------------------
    // Every card is rebuilt from scratch on each refresh (connection events, match actions, ...). These
    // maps - touched on the JavaFX thread only - keep what the learner was looking at from vanishing
    // each time: the last action's feedback line, an opened duration picker, and the last comparison.

    private record CardFeedback(String message, PeerCardView.FeedbackTone tone) {
    }

    /** A shown result: either real rows/message ({@code rendered}) or an error line. */
    private record CachedComparison(PeerComparisonPresentation.Rendered rendered, Optional<Instant> cutoff, String error) {
    }

    private record CachedMatchProgress(ObjectId matchId, CachedComparison comparison, Optional<String> verdict) {
    }

    private final Map<Long, CardFeedback> cardFeedback = new HashMap<>();
    private final Set<Long> matchPickersOpen = new HashSet<>();
    private final Map<Long, CachedComparison> comparisonCache = new HashMap<>();
    private final Map<Long, CachedMatchProgress> matchProgressCache = new HashMap<>();
    private final Map<Long, PeerCardView> liveCards = new HashMap<>();
    private Timeline countdown;

    @FXML
    public void initialize() {
        presenter.setOnChange(() -> Platform.runLater(this::refreshContacts));
        refreshStatus();
        copyInvitationButton.setDisable(myInvitationArea.getText() == null || myInvitationArea.getText().isBlank());
        resetParsedInvitation();
        refreshContacts();
        installCountdown();
    }

    /**
     * Ticks the "08:42 remaining" line of any active match once a second, only while this screen is
     * actually attached to a scene. When a match's clock reaches zero the whole list is repainted so
     * the card moves to its completed state (the repaint itself asks {@code PeerMatchService}, which
     * is what completes an expired match - nothing here decides that).
     */
    private void installCountdown() {
        if (countdown != null) {
            return;
        }
        countdown = new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> tickCountdown()));
        countdown.setCycleCount(Animation.INDEFINITE);
        contactsBox.sceneProperty().addListener((observable, previous, scene) -> {
            if (scene == null) {
                countdown.pause();
            } else {
                countdown.play();
            }
        });
        if (contactsBox.getScene() != null) {
            countdown.play();
        }
    }

    private void tickCountdown() {
        Instant now = Instant.now();
        boolean expired = false;
        for (Node card : contactsBox.getChildren()) {
            if (card instanceof PeerCardView view && view.tick(now)) {
                expired = true;
            }
        }
        if (expired) {
            refreshContacts();
        }
    }

    // --- identity & network ----------------------------------------------------------------------

    @FXML
    public void createIdentity() {
        char[] passphrase = takePassphrase(vaultPassphraseField, this::setNetworkingFeedback,
                "Enter a vault passphrase, then click Create identity.");
        if (passphrase == null) {
            return;
        }
        setNetworkingFeedback("Creating identity…");
        runPeerAction(() -> {
            try {
                return identityService.createIdentity(passphrase, now());
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }, summary -> {
            refreshStatus();
            setNetworkingFeedback("Identity created. Enter your passphrase again to go online.", PeerCardView.FeedbackTone.SUCCESS);
            setStatus(null);
        }, error -> setNetworkingFeedback(PeerErrorPresentation.message(error, "Couldn't create your identity. Please try again."),
                PeerCardView.FeedbackTone.ERROR));
    }

    @FXML
    public void enableNetworking() {
        char[] passphrase = takePassphrase(vaultPassphraseField, this::setNetworkingFeedback,
                "Enter your vault passphrase, then click Enable networking.");
        if (passphrase == null) {
            return;
        }
        setNetworkingFeedback("Going online…");
        runPeerAction(() -> {
            try {
                networkingService.enableNetworking(passphrase, 0, now());
                return null;
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }, (Void ignored) -> {
            refreshStatus();
            refreshContacts();
            setNetworkingFeedback(null);
            setStatus(null);
        }, error -> setNetworkingFeedback(PeerErrorPresentation.message(error, "Couldn't turn networking on. Please try again."),
                PeerCardView.FeedbackTone.ERROR));
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
            refreshStatus();
            refreshContacts();
            setNetworkingFeedback(null);
            setStatus(null);
        }, error -> setNetworkingFeedback(PeerErrorPresentation.message(error, "Couldn't turn networking off. Please try again."),
                PeerCardView.FeedbackTone.ERROR));
    }

    /** Repaints the identity + network strip from the same {@code NetworkingService} reads as ever. */
    private void refreshStatus() {
        Optional<LocalIdentitySummary> identity = networkingService.currentIdentity();
        boolean enabled = networkingService.isNetworkingEnabled();
        PeerNetworkPresentation view = PeerNetworkPresentation.of(
                identity.map(summary -> IdentityFingerprint.format(summary.id())).orElse(null),
                enabled, networkingService.listeningPort());

        identityStatusLabel.setText(view.identityText());
        identityStatusLabel.setTooltip(view.identityFull() == null ? null : new Tooltip(view.identityFull()));
        identityStatusLabel.getStyleClass().remove("identity-value-empty");
        if (view.identityFull() == null) {
            identityStatusLabel.getStyleClass().add("identity-value-empty");
        }

        networkingStatusLabel.setText(view.networkTitle());
        networkingDetailLabel.setText(view.networkDetail());
        networkDot.getStyleClass().removeAll("network-dot-online", "network-dot-offline");
        networkDot.getStyleClass().add(view.online() ? "network-dot-online" : "network-dot-offline");

        setupHintLabel.setText(view.setupHint() == null ? "" : view.setupHint());
        show(setupBox, view.stage() != PeerNetworkPresentation.Stage.ONLINE);
        show(createIdentityButton, view.stage() == PeerNetworkPresentation.Stage.NO_IDENTITY);
        show(enableNetworkingButton, view.stage() == PeerNetworkPresentation.Stage.NETWORK_OFF);
        show(disableNetworkingButton, view.stage() == PeerNetworkPresentation.Stage.ONLINE);

        createIdentityButton.setDisable(identity.isPresent());
        enableNetworkingButton.setDisable(enabled);
        disableNetworkingButton.setDisable(!enabled);
        createInvitationButton.setDisable(!enabled);
        invitationPassphraseField.setDisable(!enabled);
        ownFingerprintLabel.setText(view.identityFull() == null ? "" : view.identityFull());
        show(ownFingerprintBox, view.identityFull() != null);
        show(invitationHintLabel, !enabled);
    }

    // --- add a peer --------------------------------------------------------------------------------

    @FXML
    public void toggleAddPeer() {
        boolean open = !addPeerPanel.isVisible();
        show(addPeerPanel, open);
        addPeerButton.setText(open ? "Close" : "Add a peer");
        // The empty state's own "Add your first peer" call to action is redundant while the panel is open.
        show(emptyPeersBox, !open && contactsBox.getChildren().isEmpty());
        if (open) {
            // Scroll position stays put; focus goes to the first thing the learner can act on.
            (createInvitationButton.isDisabled() ? peerInvitationArea : invitationPassphraseField).requestFocus();
        }
    }

    @FXML
    public void createInvitation() {
        char[] passphrase = takePassphrase(invitationPassphraseField, this::setInvitationFeedback,
                "Enter your vault passphrase, then click Create invitation.");
        if (passphrase == null) {
            return;
        }
        setInvitationFeedback("Creating invitation…");
        runPeerAction(() -> {
            try {
                return networkingService.createInvitation(passphrase, Duration.ofDays(1), now());
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }, signed -> {
            myInvitationArea.setText(InvitationCodec.toBase64(signed));
            copyInvitationButton.setDisable(false);
            show(invitationResultBox, true);
            setInvitationFeedback("Invitation ready. Copy it and send it to them.", PeerCardView.FeedbackTone.SUCCESS);
            setStatus(null);
        }, error -> setInvitationFeedback(PeerErrorPresentation.message(error, "Couldn't create an invitation. Please try again."),
                PeerCardView.FeedbackTone.ERROR));
    }

    @FXML
    public void copyInvitation() {
        String text = myInvitationArea.getText();
        if (text == null || text.isBlank()) {
            setInvitationFeedback("Create an invitation first.");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        setInvitationFeedback("Copied. Send it to them over chat or email.", PeerCardView.FeedbackTone.SUCCESS);
        setStatus(null);
    }

    @FXML
    public void parseInvitation() {
        String text = peerInvitationArea.getText();
        if (text == null || text.isBlank()) {
            setPairingFeedback("Paste their invitation first.");
            return;
        }
        setPairingFeedback("Checking invitation…");
        runPeerAction(() -> networkingService.parseInvitationBase64(text), signed -> {
            parsedPeerInvitation = signed;
            // The one place the FULL fingerprint is shown: verifying it out of band is the security step.
            parsedFingerprintLabel.setText(IdentityFingerprint.format(signed.invitation().identityKey().id()));
            show(parsedFingerprintLabel, true);
            show(verifyBox, true);
            registerAndAcceptButton.setDisable(false);
            setPairingFeedback(null);
            setStatus(null);
        }, error -> {
            resetParsedInvitation();
            setPairingFeedback(PeerErrorPresentation.message(error, "Couldn't read that invitation. Paste the whole text they sent you."),
                    PeerCardView.FeedbackTone.ERROR);
        });
    }

    @FXML
    public void registerAndAccept() {
        SignedInvitation signed = parsedPeerInvitation;
        if (signed == null) {
            setPairingFeedback("Check their invitation first.");
            return;
        }
        setPairingFeedback("Pairing…");
        runPeerAction(() -> {
            Contact pending = networkingService.registerPendingContactFromInvitation(signed, now());
            return contactService.acceptInvitation(pending.id(), now());
        }, contact -> {
            resetParsedInvitation();
            peerInvitationArea.clear();
            refreshContacts();
            setPairingFeedback("Paired with " + displayNameFor(contact) + ".", PeerCardView.FeedbackTone.SUCCESS);
            setStatus(null);
        }, error -> setPairingFeedback(PeerErrorPresentation.message(error, "Couldn't pair with that invitation. Please try again."),
                PeerCardView.FeedbackTone.ERROR));
    }

    private void resetParsedInvitation() {
        parsedPeerInvitation = null;
        show(parsedFingerprintLabel, false);
        show(verifyBox, false);
        registerAndAcceptButton.setDisable(true);
    }

    // --- peers ---------------------------------------------------------------------------------------

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
            return; // already dialing this contact from this UI - the card already shows "Connecting…"
        }
        clearCardFeedback(contact.id());
        List<ContactAddress> addresses = contactService.knownAddresses(contact.id());
        if (addresses.isEmpty()) {
            dialGate.release(contact.id());
            setCardFeedback(contact.id(), "No known address for " + displayNameFor(contact)
                    + " yet. Ask them to send their invitation again.", PeerCardView.FeedbackTone.ERROR);
            refreshContacts();
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
                        if (throwable != null) {
                            setCardFeedback(contact.id(), "Couldn't connect to " + displayNameFor(contact)
                                    + ". Something went wrong, so try again.", PeerCardView.FeedbackTone.ERROR);
                        } else if (!outcome.result().authenticated()) {
                            setCardFeedback(contact.id(), "Couldn't connect. " + failureSentence(contact, outcome.result()),
                                    PeerCardView.FeedbackTone.ERROR);
                        }
                        refreshContacts();
                    }));
        } catch (IllegalStateException cannotDial) {
            dialGate.release(contact.id());
            setCardFeedback(contact.id(), "Couldn't connect to " + displayNameFor(contact)
                    + " yet. Ask them to send their invitation again.", PeerCardView.FeedbackTone.ERROR);
        }
        refreshContacts();
    }

    private static String failureSentence(Contact contact, ConnectionOutcome outcome) {
        return PeerNamePresentation.sentence(PeerConnectionPresenter.describeFailure(outcome.failureReason()),
                displayNameFor(contact), PeerNamePresentation.isFingerprintFallback(contact.alias(), contact.displayName()));
    }

    private void disconnectFrom(Contact contact) {
        clearCardFeedback(contact.id());
        runPeerAction(() -> {
            networkingService.disconnect(contact.identityId());
            return null;
        }, (Void ignored) -> refreshContacts(),
                error -> setCardFeedback(contact.id(), "Couldn't disconnect. Please try again.", PeerCardView.FeedbackTone.ERROR));
    }

    /** Rebuilds the peer list from scratch: {@code ContactService} is the source of contacts,
     *  {@code NetworkingService.activeConnection} is the ground truth for "connected right now", and
     *  {@link PeerConnectionPresenter} only ever adds detail for why a contact that is NOT currently
     *  connected isn't - exactly the reconciliation {@link PeerConnectionPresenter}'s own javadoc
     *  describes. Safe to call from the JavaFX thread only (mutates {@code Node}s directly) - every
     *  caller either already is the JavaFX thread or got there via {@code Platform.runLater} first. */
    private void refreshContacts() {
        List<Contact> contacts = contactService.listContacts();
        show(emptyPeersBox, contacts.isEmpty() && !addPeerPanel.isVisible());
        noContactsLabel.setVisible(contacts.isEmpty());
        noContactsLabel.setManaged(contacts.isEmpty());
        contactsBox.getChildren().clear();
        liveCards.clear();
        int connectedCount = 0;
        for (Contact contact : contacts) {
            PeerCardView card = buildContactCard(contact);
            if (card.presentation().connection() == PeerCardPresentation.Connection.CONNECTED) {
                connectedCount++;
            }
            liveCards.put(contact.id(), card);
            contactsBox.getChildren().add(card);
        }
        peersSummaryLabel.setText(contacts.isEmpty() ? "" : contacts.size() + " paired · " + connectedCount + " connected");
    }

    /**
     * One peer card. Every actual decision - whether connected, what a comparison says, what a match's
     * state is - is read from exactly the same backend calls as before, handed to the pure
     * {@link PeerCardPresentation}/{@link StudyMatchPresentation} view-models; {@link PeerCardView}
     * only lays the result out and reports button clicks back through the {@link PeerCardView.Actions}
     * below, each of which makes exactly the service call the old per-peer button made.
     */
    private PeerCardView buildContactCard(Contact contact) {
        boolean networkingEnabled = networkingService.isNetworkingEnabled();
        boolean connected = networkingService.activeConnection(contact.identityId()).isPresent();
        // dialGate, not any transport event, is authoritative for "connecting" here: a manual dial
        // this UI just started may not yet have produced any ConnectionEvent at all (the attempt is
        // still queued behind the dial pool's own concurrency limit, say), but the card must still
        // reflect that this UI already owns an in-flight request for this contact.
        boolean dialing = !connected && dialGate.isInFlight(contact.id());

        PeerCardPresentation card = PeerCardPresentation.of(contact.alias(), contact.displayName(), contact.fingerprint(),
                networkingEnabled, connected, dialing, presenter.isConnectingFor(contact.identityId(), connected),
                presenter.offlineReasonFor(contact.identityId(), connected));
        Optional<StudyMatch> latestMatch = matchService.matchesFor(contact.id()).stream().findFirst();
        StudyMatchPresentation match = StudyMatchPresentation.of(latestMatch, connected, card.spokenName(), now());

        PeerCardView view = new PeerCardView(card, match, matchPickersOpen.contains(contact.id()), new PeerCardView.Actions() {
            @Override
            public void connect(PeerCardView view) {
                connectTo(contact);
            }

            @Override
            public void disconnect(PeerCardView view) {
                disconnectFrom(contact);
            }

            @Override
            public void compare(PeerCardView view) {
                compareTodaysProgress(contact, card.spokenName());
            }

            @Override
            public void share(PeerCardView view) {
                shareTodaysProgress(contact);
            }

            @Override
            public void startMatch(PeerCardView view, MatchDuration duration) {
                matchPickersOpen.remove(contact.id());
                startStudyMatch(contact, duration);
            }

            @Override
            public void acceptMatch(PeerCardView view) {
                latestMatch.ifPresent(m -> respondToMatch(contact, m.matchId(), true));
            }

            @Override
            public void declineMatch(PeerCardView view) {
                latestMatch.ifPresent(m -> respondToMatch(contact, m.matchId(), false));
            }

            @Override
            public void cancelMatch(PeerCardView view) {
                latestMatch.ifPresent(m -> PeerController.this.cancelMatch(contact, m.matchId()));
            }

            @Override
            public void refreshMatch(PeerCardView view) {
                latestMatch.ifPresent(m -> refreshMatchProgress(contact, m.matchId(), card.spokenName()));
            }

            @Override
            public void matchPickerChanged(PeerCardView view, boolean open) {
                if (open) {
                    matchPickersOpen.add(contact.id());
                } else {
                    matchPickersOpen.remove(contact.id());
                }
            }
        });

        CardFeedback feedback = cardFeedback.get(contact.id());
        if (feedback != null) {
            view.setFeedback(feedback.message(), feedback.tone());
        }
        CachedComparison comparison = comparisonCache.get(contact.id());
        if (comparison != null) {
            applyComparison(view, comparison);
        }
        CachedMatchProgress progress = matchProgressCache.get(contact.id());
        if (progress != null && latestMatch.isPresent() && progress.matchId().equals(latestMatch.get().matchId())) {
            if (progress.comparison().error() != null) {
                view.showMatchProgressError(progress.comparison().error());
            } else {
                view.showMatchProgress(progress.comparison().rendered(), progress.comparison().cutoff(), progress.verdict());
            }
        }
        return view;
    }

    private static void applyComparison(PeerCardView view, CachedComparison comparison) {
        if (comparison.error() != null) {
            view.showComparisonError(comparison.error());
        } else {
            view.showComparison(comparison.rendered(), comparison.cutoff());
        }
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
        clearCardFeedback(contact.id());
        runPeerAction(() -> {
            Instant now = now();
            contactService.grantAdditionalScope(contact.id(), SharingScope.DAILY_SUMMARY, 1, now);

            ZoneId zone = ZoneId.systemDefault();
            ComparisonWindow todayWindow = ComparisonWindow.day(LocalDate.now(zone), zone);
            outboxService.approveProgressSummary(contact.id(), todayWindow, now);

            syncNowIfConnected(contact);
            return null;
        }, (Void ignored) -> setCardFeedback(contact.id(), "Sharing today's progress with " + displayNameFor(contact) + ".",
                PeerCardView.FeedbackTone.SUCCESS),
                error -> setCardFeedback(contact.id(), "Couldn't share your progress. Please try again.", PeerCardView.FeedbackTone.ERROR));
    }

    /**
     * Reads this device's own real today's progress and the named peer's already-synced/cached one
     * (never manufactured here), delegating every actual decision to {@link PeerComparisonService}/
     * {@code SnapshotComparisonEngine}. Never touches {@code PeerSyncSessionService}, {@code
     * PeerConnection}, or {@code UnlockedIdentity} - this screen only ever calls the one narrow,
     * already-reviewed comparison entry point. Rendering is delegated to {@link PeerComparisonPresentation}
     * and {@link PeerCardView}; this method's only job is the backend call and caching the result.
     */
    private void compareTodaysProgress(Contact contact, String peerName) {
        runPeerAction(() -> comparisonService.compareTodayWith(contact.id()), comparison -> {
            CachedComparison result = cacheComparison(comparison, "No study activity recorded by either person yet today.", peerName);
            comparisonCache.put(contact.id(), result);
            liveCard(contact.id()).ifPresent(card -> applyComparison(card, result));
        }, error -> {
            CachedComparison result = new CachedComparison(null, Optional.empty(), "Couldn't load the comparison. Please try again.");
            comparisonCache.put(contact.id(), result);
            liveCard(contact.id()).ifPresent(card -> applyComparison(card, result));
        });
    }

    /**
     * Turns one real {@link SnapshotComparisonEngine.ProgressComparison} - from either "Compare today"
     * or a Study Match's own progress - into the pure render model plus the "Compared through …" cutoff
     * derived from the comparison's own real cutoff (never {@code Instant.now()}). Every actual
     * rendering decision lives in {@link PeerComparisonPresentation}.
     */
    private static CachedComparison cacheComparison(SnapshotComparisonEngine.ProgressComparison comparison,
                                                    String noActivityMessage, String peerName) {
        PeerComparisonPresentation.Rendered rendered = PeerComparisonPresentation.present(comparison, noActivityMessage, peerName);
        return new CachedComparison(rendered, comparison.left().snapshotOptional().map(ProgressSummary::cutoff), null);
    }

    private Optional<PeerCardView> liveCard(long contactId) {
        return Optional.ofNullable(liveCards.get(contactId));
    }

    /** The challenger's own action: proposes {@code duration}, granting {@code MATCH_PARTICIPATION} up front. */
    private void startStudyMatch(Contact contact, MatchDuration duration) {
        clearCardFeedback(contact.id());
        runPeerAction(() -> {
            matchService.startMatch(contact.id(), duration, now());
            syncNowIfConnected(contact);
            return null;
        }, (Void ignored) -> refreshContacts(),
                error -> setCardFeedback(contact.id(), "Couldn't send the Study Match invitation. Please try again.",
                        PeerCardView.FeedbackTone.ERROR));
    }

    /** The opponent's own accept/decline action. */
    private void respondToMatch(Contact contact, ObjectId matchId, boolean accept) {
        clearCardFeedback(contact.id());
        runPeerAction(() -> {
            Instant now = now();
            if (accept) {
                matchService.accept(matchId, now);
            } else {
                matchService.decline(matchId, now);
            }
            syncNowIfConnected(contact);
            return null;
        }, (Void ignored) -> refreshContacts(),
                error -> setCardFeedback(contact.id(), "Couldn't " + (accept ? "accept" : "decline") + " the Study Match. Please try again.",
                        PeerCardView.FeedbackTone.ERROR));
    }

    /** The challenger's own withdrawal of a still-{@code PENDING} invitation. */
    private void cancelMatch(Contact contact, ObjectId matchId) {
        clearCardFeedback(contact.id());
        runPeerAction(() -> {
            matchService.cancel(matchId, now());
            syncNowIfConnected(contact);
            return null;
        }, (Void ignored) -> refreshContacts(),
                error -> setCardFeedback(contact.id(), "Couldn't cancel the invitation. Please try again.", PeerCardView.FeedbackTone.ERROR));
    }

    /**
     * "Refresh progress" / "View results": while still {@code ACTIVE}, first captures and approves
     * this device's own real match-window progress (exactly {@link #shareTodaysProgress}'s own
     * pattern, reused for a match window instead of today's calendar day) and syncs it now if already
     * connected; either way, then reads and renders the real comparison plus the one small,
     * documented "who is ahead" verdict {@link PeerMatchService#deriveLeaderVerdict} derives - never a
     * new scoring/winner computation here. Never touches {@code PeerSyncSessionService}/{@code
     * PeerConnection}/{@code UnlockedIdentity}.
     */
    private void refreshMatchProgress(Contact contact, ObjectId matchId, String peerName) {
        runPeerAction(() -> {
            Instant now = now();
            Optional<StudyMatch> current = matchService.find(matchId);
            if (current.isPresent() && current.get().status() == MatchStatus.ACTIVE) {
                matchService.refreshProgress(matchId, now);
                syncNowIfConnected(contact);
            }
            return matchService.compareProgress(matchId, now);
        }, (PeerMatchService.MatchProgressView view) -> {
            CachedComparison comparison = cacheComparison(view.comparison(),
                    "No study activity recorded by either person yet during this match.", peerName);
            CachedMatchProgress progress = new CachedMatchProgress(matchId, comparison,
                    view.verdict().map(verdict -> StudyMatchPresentation.verdictText(verdict, peerName)));
            matchProgressCache.put(contact.id(), progress);
            liveCard(contact.id()).ifPresent(card -> card.showMatchProgress(comparison.rendered(), comparison.cutoff(), progress.verdict()));
        }, error -> {
            CachedComparison comparison = new CachedComparison(null, Optional.empty(), "Couldn't refresh match progress. Please try again.");
            matchProgressCache.put(contact.id(), new CachedMatchProgress(matchId, comparison, Optional.empty()));
            liveCard(contact.id()).ifPresent(card -> card.showMatchProgressError(comparison.error()));
        });
    }

    /** Best-effort immediate sync, shared by every Study Match action - see {@link #shareTodaysProgress}'s own javadoc for why. */
    private void syncNowIfConnected(Contact contact) {
        if (networkingService.activeConnection(contact.identityId()).isPresent()) {
            try {
                networkingService.syncOutboxNow(contact.id());
            } catch (IOException | RuntimeException bestEffort) {
                // Best-effort - the next automatic connection-established send will resend it.
            }
        }
    }

    private static String displayNameFor(Contact contact) {
        return PeerNamePresentation.displayName(contact.alias(), contact.displayName(), contact.fingerprint());
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

    private char[] takePassphrase(PasswordField field, BiConsumer<String, PeerCardView.FeedbackTone> feedback, String missingMessage) {
        String text = field.getText();
        if (text == null || text.isEmpty()) {
            feedback.accept(missingMessage, PeerCardView.FeedbackTone.INFO);
            return null;
        }
        char[] passphrase = text.toCharArray();
        field.clear();
        return passphrase;
    }

    // --- feedback ----------------------------------------------------------------------------------

    private void setNetworkingFeedback(String message) {
        setNetworkingFeedback(message, PeerCardView.FeedbackTone.INFO);
    }

    private void setNetworkingFeedback(String message, PeerCardView.FeedbackTone tone) {
        setInlineFeedback(networkingFeedbackLabel, message, tone);
    }

    private void setInvitationFeedback(String message) {
        setInvitationFeedback(message, PeerCardView.FeedbackTone.INFO);
    }

    private void setInvitationFeedback(String message, PeerCardView.FeedbackTone tone) {
        setInlineFeedback(invitationFeedbackLabel, message, tone);
    }

    private void setPairingFeedback(String message) {
        setPairingFeedback(message, PeerCardView.FeedbackTone.INFO);
    }

    private void setPairingFeedback(String message, PeerCardView.FeedbackTone tone) {
        setInlineFeedback(pairingFeedbackLabel, message, tone);
    }

    private static void setInlineFeedback(Label label, String message, PeerCardView.FeedbackTone tone) {
        boolean hasMessage = message != null && !message.isBlank();
        label.setText(hasMessage ? message : "");
        label.getStyleClass().removeAll("feedback-error", "feedback-success");
        if (hasMessage && tone == PeerCardView.FeedbackTone.ERROR) {
            label.getStyleClass().add("feedback-error");
        } else if (hasMessage && tone == PeerCardView.FeedbackTone.SUCCESS) {
            label.getStyleClass().add("feedback-success");
        }
        show(label, hasMessage);
    }

    /** Remembers (and shows, if the card is on screen) the outcome of the last action on one contact's card. */
    private void setCardFeedback(long contactId, String message, PeerCardView.FeedbackTone tone) {
        cardFeedback.put(contactId, new CardFeedback(message, tone));
        liveCard(contactId).ifPresent(card -> card.setFeedback(message, tone));
    }

    private void clearCardFeedback(long contactId) {
        cardFeedback.remove(contactId);
        liveCard(contactId).ifPresent(card -> card.setFeedback(null, PeerCardView.FeedbackTone.INFO));
    }

    /** A line not tied to any one peer or form (rare now that feedback sits beside the action that caused it). */
    private void setStatus(String message) {
        boolean hasStatus = message != null && !message.isBlank();
        statusLabel.setText(hasStatus ? message : "");
        show(statusLabel, hasStatus);
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    /** Runs {@code work} on its own daemon thread, never the JavaFX application thread, and delivers
     *  the result back on the JavaFX thread via {@link Task}'s own guarantee for
     *  {@code setOnSucceeded}/{@code setOnFailed} - callbacks passed here may touch {@code Node}s
     *  directly. */
    private <T> void runPeerAction(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> onSuccess.accept(task.getValue()));
        task.setOnFailed(event -> {
            // The raw exception is for developers only: it goes to the log, never to the screen.
            LOG.log(System.Logger.Level.WARNING, "Peers screen action failed", task.getException());
            onFailure.accept(task.getException());
        });
        Thread thread = new Thread(task, "codefit-peer-ui-action");
        thread.setDaemon(true);
        thread.start();
    }
}

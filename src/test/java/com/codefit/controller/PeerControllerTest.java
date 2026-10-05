package com.codefit.controller;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactAddressSource;
import com.codefit.peer.identity.IdentityFingerprint;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.invitation.Invitation;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.transport.PeerAddress;
import com.codefit.service.ContactService;
import com.codefit.service.IdentityService;
import com.codefit.service.NetworkingService;
import com.codefit.testsupport.IsolatedDatabaseExtension;
import com.codefit.testsupport.PeerIdentityTestTables;
import com.codefit.ui.PeerConnectionPresenter;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers {@link PeerController}'s orchestration/presentation logic - not JavaFX itself, and not the
 * real two-process transport handshake (that stays covered by the untouched, pre-existing
 * {@code TwoProcessPeerDemoTest}/{@code TwoProcessSyncDemoTest}, plus this PR's own
 * {@code PeerSyncSessionServiceReconnectRaceTest}). Every backend call here goes through the real,
 * unmocked {@link IdentityService}/{@link NetworkingService}/{@link ContactService} against an
 * isolated test database - never the shared local {@code codefit.db}, never a fake networking stack.
 *
 * <p>A full two-real-identity "connects successfully through this screen" scenario is deliberately
 * NOT attempted here: {@code DatabaseConfig} is a single JVM-wide static (the same constraint
 * #182/#184's own tests document), so two independent identity/transport stacks cannot coexist in one
 * test JVM - that is exactly why this codebase's real multi-identity proof
 * ({@code TwoProcessPeerDemoTest}) uses two separate OS processes instead. Building a second such
 * harness here, through this controller, would duplicate that existing proof and make this PR
 * materially larger for no new correctness evidence. What IS covered here, with one real local
 * identity and real (not mocked) transport calls: a deterministic failed-connection path (dialing a
 * real closed port) and every other required state the controller itself is responsible for
 * rendering.
 */
@ExtendWith(IsolatedDatabaseExtension.class)
@ResourceLock(IsolatedDatabaseExtension.DATABASE_RESOURCE)
class PeerControllerTest {

    private static final char[] PASSPHRASE = "peer-controller-test-passphrase".toCharArray();

    @BeforeAll
    static void requireFxToolkit() {
        Assumptions.assumeTrue(com.codefit.ui.FxToolkitSupport.isAvailable(), "JavaFX toolkit unavailable (no display) - skipping");
    }

    private IdentityService identityService;
    private NetworkingService networkingService;
    private ContactService contactService;
    private PeerConnectionPresenter presenter;
    private com.codefit.ui.PeerDialGate dialGate;
    private PeerController controller;

    @BeforeEach
    void setUp() throws Exception {
        PeerIdentityTestTables.resetAll();
        identityService = new IdentityService();
        contactService = new ContactService();
        presenter = new PeerConnectionPresenter();
        dialGate = new com.codefit.ui.PeerDialGate();
        networkingService = new NetworkingService(presenter::onEvent);
        controller = loadController(identityService, networkingService, contactService, presenter, dialGate);
    }

    @AfterEach
    void tearDown() {
        networkingService.close();
    }

    @Test
    void withNoIdentityTheScreenShowsNoIdentityAndTheCreateButtonIsEnabled() throws Exception {
        runOnFxThreadAndWait(() -> {
            assertTrue(controller.identityStatusLabel.getText().contains("No identity"));
            assertFalse(controller.createIdentityButton.isDisabled());
            assertTrue(controller.copyInvitationButton.isDisabled(), "Copy must stay disabled until an invitation exists");
        });
    }

    @Test
    void creatingIdentityWithoutAPassphraseShowsGuidanceBesideTheIdentityControls() throws Exception {
        runOnFxThreadAndWait(controller::createIdentity);

        runOnFxThreadAndWait(() -> {
            assertFalse(controller.createIdentityButton.isDisabled());
            assertTrue(controller.networkingFeedbackLabel.isVisible());
            assertTrue(controller.networkingFeedbackLabel.getText().contains("Create Identity"));
        });
    }

    @Test
    void creatingAnIdentityUpdatesTheStatusAndDisablesTheButton() throws Exception {
        runOnFxThreadAndWait(() -> {
            controller.vaultPassphraseField.setText(new String(PASSPHRASE));
            controller.createIdentity();
        });

        waitUntil(() -> fxRead(() -> controller.createIdentityButton.isDisabled()), disabled -> disabled);

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.createIdentityButton.isDisabled());
            assertTrue(controller.identityStatusLabel.getText().startsWith("Identity:"));
            assertEquals("", controller.vaultPassphraseField.getText(), "the passphrase field must be cleared right after use");
            assertTrue(controller.networkingFeedbackLabel.isVisible());
            assertTrue(controller.networkingFeedbackLabel.getText().contains("Re-enter your vault passphrase"));
        });
    }

    @Test
    void enablingNetworkingWithoutReenteringThePassphraseShowsInlineGuidance() throws Exception {
        runOnFxThreadAndWait(() -> {
            controller.vaultPassphraseField.setText(new String(PASSPHRASE));
            controller.createIdentity();
        });
        waitUntil(() -> fxRead(() -> controller.createIdentityButton.isDisabled()), disabled -> disabled);

        runOnFxThreadAndWait(controller::enableNetworking);

        runOnFxThreadAndWait(() -> {
            assertFalse(networkingService.isNetworkingEnabled());
            assertTrue(controller.networkingFeedbackLabel.isVisible());
            assertTrue(controller.networkingFeedbackLabel.getText().contains("Re-enter your vault passphrase"));
        });
    }

    @Test
    void networkingDisabledByDefaultThenEnabledShowsItsPort() throws Exception {
        runOnFxThreadAndWait(() -> {
            assertTrue(controller.networkingStatusLabel.getText().contains("disabled"));
            assertFalse(controller.enableNetworkingButton.isDisabled());
            assertTrue(controller.disableNetworkingButton.isDisabled());

            controller.vaultPassphraseField.setText(new String(PASSPHRASE));
            controller.createIdentity();
        });
        waitUntil(() -> fxRead(() -> controller.createIdentityButton.isDisabled()), disabled -> disabled);
        // See enableIdentityAndNetworking()'s comment: a fresh writer session's epoch must be
        // strictly newer than createIdentity's own initial epoch, which is wall-clock-second-derived.
        Thread.sleep(1_100);

        runOnFxThreadAndWait(() -> {
            controller.vaultPassphraseField.setText(new String(PASSPHRASE));
            controller.enableNetworking();
        });
        waitUntil(() -> fxRead(() -> networkingService.isNetworkingEnabled()), enabled -> enabled);

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.networkingStatusLabel.getText().contains("enabled on port"));
            assertTrue(controller.networkingFeedbackLabel.isVisible());
            assertTrue(controller.networkingFeedbackLabel.getText().contains("Networking enabled"));
            assertTrue(controller.enableNetworkingButton.isDisabled());
            assertFalse(controller.disableNetworkingButton.isDisabled());
        });

        runOnFxThreadAndWait(controller::disableNetworking);
        waitUntil(() -> fxRead(() -> networkingService.isNetworkingEnabled()), enabled -> !enabled);
        runOnFxThreadAndWait(() -> assertTrue(controller.networkingStatusLabel.getText().contains("disabled")));
    }

    @Test
    void creatingAnInvitationPopulatesTheInvitationArea() throws Exception {
        enableIdentityAndNetworking();

        runOnFxThreadAndWait(() -> {
            controller.vaultPassphraseField.setText(new String(PASSPHRASE));
            controller.createInvitation();
        });
        waitUntil(() -> fxRead(() -> controller.myInvitationArea.getText()), text -> text != null && !text.isBlank());

        runOnFxThreadAndWait(() -> {
            String text = controller.myInvitationArea.getText();
            // Must round-trip as a real, verifiable signed invitation - not just non-empty text.
            assertTrue(InvitationCodec.fromBase64(text).invitation().identityKey() != null);
            assertFalse(controller.copyInvitationButton.isDisabled());
            assertTrue(controller.invitationFeedbackLabel.isVisible());
            assertTrue(controller.invitationFeedbackLabel.getText().contains("Invitation created"));
        });
    }

    @Test
    void creatingInvitationWithoutReenteringThePassphraseShowsLocalGuidance() throws Exception {
        enableIdentityAndNetworking();

        runOnFxThreadAndWait(controller::createInvitation);

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.myInvitationArea.getText().isBlank());
            assertTrue(controller.copyInvitationButton.isDisabled());
            assertTrue(controller.invitationFeedbackLabel.isVisible());
            assertTrue(controller.invitationFeedbackLabel.getText().contains("Re-enter your vault passphrase"));
        });
    }

    @Test
    void parsingAnEmptyInvitationShowsFeedbackInThePairingPanel() throws Exception {
        runOnFxThreadAndWait(controller::parseInvitation);

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.registerAndAcceptButton.isDisabled());
            assertTrue(controller.pairingFeedbackLabel.isVisible());
            assertTrue(controller.pairingFeedbackLabel.getText().contains("Paste the other person's invitation"));
        });
    }

    @Test
    void parsingGarbageShowsAnErrorAndNeverEnablesRegister() throws Exception {
        runOnFxThreadAndWait(() -> {
            controller.peerInvitationArea.setText("not a real invitation");
            controller.parseInvitation();
        });
        waitUntil(() -> fxRead(() -> controller.pairingFeedbackLabel.getText()), text -> text != null && !text.isBlank());

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.registerAndAcceptButton.isDisabled());
            assertFalse(controller.parsedFingerprintLabel.isVisible());
            assertTrue(controller.pairingFeedbackLabel.isVisible());
        });
    }

    @Test
    void parsingAValidInvitationShowsTheFingerprintAndEnablesRegister() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        String invitationText = syntheticInvitationBase64(strangerIdentity, freeLoopbackPort());

        runOnFxThreadAndWait(() -> {
            controller.peerInvitationArea.setText(invitationText);
            controller.parseInvitation();
        });
        waitUntil(() -> fxRead(() -> controller.registerAndAcceptButton.isDisabled()), disabled -> !disabled);

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.parsedFingerprintLabel.isVisible());
            IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
            assertTrue(controller.parsedFingerprintLabel.getText().contains(IdentityFingerprint.format(strangerKey.id())));
            assertTrue(controller.pairingFeedbackLabel.isVisible());
            assertTrue(controller.pairingFeedbackLabel.getText().contains("Invitation parsed"));
        });
    }

    @Test
    void registeringAndAcceptingAddsAVisibleOfflinePairedPeerRow() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        String invitationText = syntheticInvitationBase64(strangerIdentity, freeLoopbackPort());

        runOnFxThreadAndWait(() -> {
            controller.peerInvitationArea.setText(invitationText);
            controller.parseInvitation();
        });
        waitUntil(() -> fxRead(() -> controller.registerAndAcceptButton.isDisabled()), disabled -> !disabled);

        runOnFxThreadAndWait(controller::registerAndAccept);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> {
            assertFalse(controller.noContactsLabel.isVisible());
            Label statusLabel = rowStatusLabel(controller.contactsBox.getChildren().get(0));
            assertEquals("Offline", statusLabel.getText());
            assertTrue(controller.pairingFeedbackLabel.isVisible());
            assertTrue(controller.pairingFeedbackLabel.getText().contains("Paired with"));
        });
    }

    @Test
    void aPairedPeerShowsNetworkingMustBeEnabledInsteadOfOfferingADeadConnectButton() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contactService.acceptInvitation(contact.id(), now());

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> {
            Button action = actionButtonOf(controller.contactsBox.getChildren().get(0));
            assertTrue(action.isDisabled());
            assertEquals("Enable networking", action.getText());
        });
    }

    @Test
    @Timeout(30)
    void connectingToAnUnreachableAddressRendersAFailedConnection() throws Exception {
        enableIdentityAndNetworking();

        int unreachablePort = freeLoopbackPort(); // closed again immediately - nothing listens on it
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        contactService.recordAddressSighting(contact.id(), new PeerAddress("127.0.0.1", unreachablePort),
                ContactAddressSource.MANUAL, now());
        // A real registerPendingContactFromInvitation call also pins the invitation's own transport
        // key (NetworkingService.java:440-441) - connectToContact requires one to exist at all.
        KeyPair strangerTransportKey = KeyPairs.generate();
        contactService.recordObservedTransportBinding(contact.id(), new IdentityKey(KeyPairs.rawPublicKey(strangerTransportKey.getPublic())),
                now().minus(Duration.ofHours(1)), now().plus(Duration.ofDays(90)), now());

        runOnFxThreadAndWait(controller::initialize); // re-render now that a contact exists
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> fireConnectButton(controller.contactsBox.getChildren().get(0)));

        waitUntil(() -> fxRead(() -> controller.statusLabel.getText()),
                text -> text != null && text.startsWith("Could not connect"));

        runOnFxThreadAndWait(() -> {
            Label rowStatus = rowStatusLabel(controller.contactsBox.getChildren().get(0));
            assertTrue(rowStatus.getText().startsWith("Offline"), "a failed dial must render as Offline, not stay stuck on Connecting");
        });
    }

    /**
     * The review-required proof: firing "Connect" twice in immediate succession for the same contact
     * - both clicks dispatched on the FX thread before either one's own {@link #refreshContacts()}
     * repaint has run, the most adversarial ordering a real double-click could produce - must start
     * only ONE in-flight dial, never two. {@link PeerDialGateTest} proves the underlying mutex itself
     * is race-free under genuine concurrency; this proves {@code PeerController} actually uses it as
     * the very first thing {@code connectTo} does, end to end, against a real (if unreachable) dial.
     */
    @Test
    @Timeout(30)
    void twoRapidConnectClicksForTheSameContactStartOnlyOneDial() throws Exception {
        enableIdentityAndNetworking();

        int unreachablePort = freeLoopbackPort();
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        long contactId = contact.id();
        contactService.recordAddressSighting(contactId, new PeerAddress("127.0.0.1", unreachablePort),
                ContactAddressSource.MANUAL, now());
        KeyPair strangerTransportKey = KeyPairs.generate();
        contactService.recordObservedTransportBinding(contactId, new IdentityKey(KeyPairs.rawPublicKey(strangerTransportKey.getPublic())),
                now().minus(Duration.ofHours(1)), now().plus(Duration.ofDays(90)), now());

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        // Both clicks happen back to back inside ONE Platform.runLater block - the second is
        // dispatched before the first click's own repaint (inside connectTo, after launching the
        // dial) has had any chance to run, let alone disable the button on screen.
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            fireConnectButton(row);
            fireConnectButton(row);
        });

        runOnFxThreadAndWait(() -> {
            Label rowStatus = rowStatusLabel(controller.contactsBox.getChildren().get(0));
            assertEquals("Connecting…", rowStatus.getText(), "exactly one dial must be recorded as in flight after both clicks");
            Button actionButton = (Button) ((HBox) controller.contactsBox.getChildren().get(0)).getChildren().get(1);
            assertTrue(actionButton.isDisabled(), "the action button must be disabled, not merely unresponsive, while a dial is in flight");
        });

        waitUntil(() -> fxRead(() -> controller.statusLabel.getText()),
                text -> text != null && text.startsWith("Could not connect"));

        runOnFxThreadAndWait(() -> {
            Label rowStatus = rowStatusLabel(controller.contactsBox.getChildren().get(0));
            assertTrue(rowStatus.getText().startsWith("Offline"), "the in-flight state must be released once the single dial completes");
            Button actionButton = (Button) ((HBox) controller.contactsBox.getChildren().get(0)).getChildren().get(1);
            assertEquals("Connect", actionButton.getText(), "the button must return to Connect, ready for a genuinely new attempt");
            assertFalse(actionButton.isDisabled());
        });
    }

    /**
     * The core threading guarantee: a transport event delivered on a plain background thread (never
     * {@code Platform.runLater}, simulating exactly how the real dial pool/listener accept thread
     * calls {@code NetworkingService}'s connection-event listener) must never throw trying to mutate a
     * {@code Node} directly, and must still - once correctly deferred - eventually repaint the screen.
     */
    @Test
    @Timeout(15)
    void aBackgroundThreadConnectionEventNeverTouchesANodeDirectlyButStillEventuallyRepaints() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        Contact finalContact = contact;

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        AtomicReference<Throwable> offThreadFailure = new AtomicReference<>();
        Thread backgroundThread = new Thread(() -> {
            try {
                // A direct Node mutation from this very thread would throw IllegalStateException
                // ("Not on FX application thread"); proving THIS does not throw is the point.
                presenter.onEvent(new com.codefit.peer.transport.ConnectionEvent(finalContact.identityId(),
                        com.codefit.peer.transport.ConnectionState.UNREACHABLE, null, null, Instant.now()));
            } catch (Throwable t) {
                offThreadFailure.set(t);
            }
        }, "test-background-connection-event");
        backgroundThread.start();
        backgroundThread.join(5_000);

        assertEquals(null, offThreadFailure.get(), "recording a background event must never throw");

        waitUntil(() -> fxRead(() -> rowStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text.contains("could not reach"));
    }

    @Test
    @Timeout(15)
    void startingAndCancellingAStudyMatchImmediatelyRefreshesThePeerRow() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contactService.acceptInvitation(contact.id(), now());

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> start15ButtonOf(controller.contactsBox.getChildren().get(0)).fire());
        waitUntil(() -> fxRead(() -> matchStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.contains("invitation sent"));

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(cancelMatchButtonOf(row).isDisabled());
            assertTrue(cancelMatchButtonOf(row).isVisible());
            assertFalse(start15ButtonOf(row).isVisible());
        });

        runOnFxThreadAndWait(() -> cancelMatchButtonOf(controller.contactsBox.getChildren().get(0)).fire());
        waitUntil(() -> fxRead(() -> matchStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.contains("cancelled"));

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(start15ButtonOf(row).isVisible(), "terminal cancellation should immediately expose a new match action");
            assertFalse(cancelMatchButtonOf(row).isVisible());
        });
    }

    @Test
    @Timeout(15)
    void shareTodaysProgressGrantsDailySummaryAndApprovesTodaysOutboxEntry() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        long contactId = contact.id();

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> shareButtonOf(controller.contactsBox.getChildren().get(0)).fire());

        waitUntil(() -> contactService.permissionsFor(contactId)
                .map(p -> p.scopes().contains(com.codefit.peer.protocol.SharingScope.DAILY_SUMMARY)).orElse(false),
                granted -> granted);
        com.codefit.peer.identity.ContactPermission permission = contactService.permissionsFor(contactId).orElseThrow();
        assertTrue(permission.historicalWindowDays() != null && permission.historicalWindowDays() >= 1,
                "historicalWindowDays must cover today's own window start regardless of what time of day the grant happens");

        List<com.codefit.peer.sync.PublicationOutboxEntry> approved = new com.codefit.service.PeerSyncOutboxService().approvedFor(contactId);
        assertTrue(approved.stream().anyMatch(entry -> entry.messageType() == com.codefit.peer.protocol.MessageType.PROGRESS_SUMMARY),
                "Share Today's Progress must approve today's real progress summary into the existing outbox model");
    }

    @Test
    @Timeout(15)
    void compareTodaysProgressShowsNotSharedWhenThePeerNeverGrantedConsent() throws Exception {
        identityService.createIdentity(PASSPHRASE.clone(), now());
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> compareButtonOf(controller.contactsBox.getChildren().get(0)).fire());

        waitUntil(() -> fxRead(() -> comparisonResultLabel(controller.contactsBox.getChildren().get(0)).isVisible()), visible -> visible);
        runOnFxThreadAndWait(() -> {
            String text = comparisonResultLabel(controller.contactsBox.getChildren().get(0)).getText();
            assertTrue(text.contains("Not shared"), "no consent was ever received from this peer: " + text);
        });
    }

    // --- helpers -------------------------------------------------------------------------------

    private void enableIdentityAndNetworking() throws Exception {
        identityService.createIdentity(PASSPHRASE.clone(), now());
        // A fresh writer session's epoch (WriterEpoch, protocol §10.1) is derived from the wall clock
        // at whole-second granularity and must be strictly newer than createIdentity's own initial
        // epoch - the same precedented wait #182/#184's own two-process demo harnesses use for
        // exactly this reason (see TwoProcessPeerDemo's nowMillis()/Thread.sleep(1_100) comment).
        Thread.sleep(1_100);
        networkingService.enableNetworking(PASSPHRASE.clone(), 0, now());
        runOnFxThreadAndWait(controller::initialize);
    }

    /** Protocol timestamp validation rejects {@code Instant.now()}'s raw sub-millisecond precision. */
    private static Instant now() {
        return Instant.ofEpochMilli(Instant.now().toEpochMilli());
    }

    private static int freeLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static String syntheticInvitationBase64(KeyPair identityKeyPair, int port) {
        IdentityKey identityKey = new IdentityKey(KeyPairs.rawPublicKey(identityKeyPair.getPublic()));
        KeyPair transportKeyPair = KeyPairs.generate();
        IdentityKey transportKey = new IdentityKey(KeyPairs.rawPublicKey(transportKeyPair.getPublic()));
        Instant now = Instant.ofEpochMilli(Instant.now().toEpochMilli());
        byte[] nonce = new byte[Invitation.NONCE_LENGTH];
        Invitation invitation = new Invitation(identityKey, transportKey, now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(90)),
                List.of(new PeerAddress("127.0.0.1", port)), nonce, now, now.plus(Duration.ofDays(1)));
        UnlockedIdentity signer = new UnlockedIdentity(identityKey, identityKeyPair.getPrivate());
        return InvitationCodec.toBase64(InvitationCodec.sign(invitation, signer));
    }

    private static Label rowStatusLabel(Node row) {
        return (Label) ((javafx.scene.layout.VBox) ((HBox) row).getChildren().get(0)).getChildren().get(1);
    }

    /** Appended after nameLabel/statusLabel (index 2) - see {@code PeerController.buildContactRow}. */
    private static Label comparisonResultLabel(Node row) {
        return (Label) ((javafx.scene.layout.VBox) ((HBox) row).getChildren().get(0)).getChildren().get(2);
    }

    private static Button actionButtonOf(Node row) {
        return (Button) ((HBox) row).getChildren().get(1);
    }

    private static void fireConnectButton(Node row) {
        actionButtonOf(row).fire();
    }

    private static Label matchStatusLabel(Node row) {
        return (Label) ((javafx.scene.layout.VBox) ((HBox) row).getChildren().get(0)).getChildren().get(3);
    }

    private static Button start15ButtonOf(Node row) {
        return (Button) ((HBox) row).getChildren().get(4);
    }

    private static Button cancelMatchButtonOf(Node row) {
        return (Button) ((HBox) row).getChildren().get(9);
    }

    /** Appended after the connect/disconnect button (index 2) - see {@code PeerController.buildContactRow}. */
    private static Button shareButtonOf(Node row) {
        return (Button) ((HBox) row).getChildren().get(2);
    }

    /** Appended after the share button (index 3) - see {@code PeerController.buildContactRow}. */
    private static Button compareButtonOf(Node row) {
        return (Button) ((HBox) row).getChildren().get(3);
    }

    private PeerController loadController(IdentityService identityService, NetworkingService networkingService,
                                           ContactService contactService, PeerConnectionPresenter presenter,
                                           com.codefit.ui.PeerDialGate dialGate) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<PeerController> controllerRef = new AtomicReference<>();

        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/peer.fxml"));
                // peer.fxml declares fx:controller (needed for AppShellController's own plain
                // FXMLLoader.load(resource) in production), which conflicts with setController() -
                // a controller factory is the supported way to inject a non-default instance anyway.
                loader.setControllerFactory(type -> new PeerController(identityService, networkingService, contactService, presenter, dialGate));
                Parent root = loader.load();
                if (root == null) {
                    throw new IllegalStateException("FXMLLoader returned a null root");
                }
                controllerRef.set(loader.getController());
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                latch.countDown();
            }
        });

        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out loading peer.fxml");
        }
        if (failure.get() != null) {
            fail("Failed to load peer.fxml", failure.get());
        }
        return controllerRef.get();
    }

    private <T> T fxRead(Supplier<T> read) {
        AtomicReference<T> value = new AtomicReference<>();
        try {
            runOnFxThreadAndWait(() -> value.set(read.get()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return value.get();
    }

    /** Bounded, condition-checked poll (never a blind fixed sleep-then-assert) - the same idiom this
     *  codebase's own transport tests already use (e.g. {@code waitForAcceptedCount}), adapted for
     *  state that must be read back on the JavaFX thread. */
    private <T> void waitUntil(Supplier<T> read, Predicate<T> until) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
        T last = null;
        while (Instant.now().isBefore(deadline)) {
            last = read.get();
            if (until.test(last)) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Timed out waiting for condition; last observed value: " + last);
    }

    private void runOnFxThreadAndWait(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable exception) {
                failure.set(exception);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out running on the JavaFX Application Thread");
        }
        if (failure.get() != null) {
            if (failure.get() instanceof AssertionError assertionError) {
                throw assertionError;
            }
            fail(failure.get());
        }
    }
}

package com.codefit.controller;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactAddressSource;
import com.codefit.peer.identity.IdentityFingerprint;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.invitation.Invitation;
import com.codefit.peer.invitation.InvitationCodec;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;
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
            assertEquals("Not created yet", controller.identityStatusLabel.getText());
            assertTrue(controller.setupBox.isVisible(), "first-time setup must be on offer");
            assertTrue(controller.createIdentityButton.isVisible());
            assertFalse(controller.enableNetworkingButton.isVisible(), "going online makes no sense before an identity exists");
            assertFalse(controller.disableNetworkingButton.isVisible());
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
            assertTrue(controller.networkingFeedbackLabel.getText().contains("Create identity"));
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
            assertFalse(controller.identityStatusLabel.getText().equals("Not created yet"));
            assertTrue(controller.identityStatusLabel.getText().contains("…"), "identity is shown as a short fingerprint");
            assertTrue(controller.enableNetworkingButton.isVisible(), "once an identity exists the next step is going online");
            assertFalse(controller.createIdentityButton.isVisible() && !controller.createIdentityButton.isDisabled());
            assertEquals("", controller.vaultPassphraseField.getText(), "the passphrase field must be cleared right after use");
            assertTrue(controller.networkingFeedbackLabel.isVisible());
            assertTrue(controller.networkingFeedbackLabel.getText().contains("Enter your passphrase again"));
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
            assertTrue(controller.networkingFeedbackLabel.getText().contains("Enter your vault passphrase"));
        });
    }

    @Test
    void networkingDisabledByDefaultThenEnabledShowsItsPort() throws Exception {
        runOnFxThreadAndWait(() -> {
            assertEquals("Not set up", controller.networkingStatusLabel.getText());
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
        // Wait on what the screen shows (set after the background task completes), not on service state,
        // which flips earlier - asserting rendered state right after the service flag was a race.
        waitUntil(() -> fxRead(() -> controller.networkingStatusLabel.getText()), text -> "Online".equals(text));

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.networkingDetailLabel.getText().startsWith("Listening on port"));
            assertTrue(controller.disableNetworkingButton.isVisible(), "the online state offers turning networking off");
            assertFalse(controller.disableNetworkingButton.isDisabled());
            assertFalse(controller.setupBox.isVisible(), "setup controls get out of the way once online");
            assertFalse(controller.enableNetworkingButton.isVisible());
            assertEquals("", controller.vaultPassphraseField.getText());
        });

        runOnFxThreadAndWait(controller::disableNetworking);
        waitUntil(() -> fxRead(() -> controller.networkingStatusLabel.getText()), text -> "Offline".equals(text));
        runOnFxThreadAndWait(() -> {
            assertFalse(networkingService.isNetworkingEnabled());
            assertTrue(controller.setupBox.isVisible());
            assertTrue(controller.enableNetworkingButton.isVisible());
        });
    }

    @Test
    void creatingAnInvitationPopulatesTheInvitationArea() throws Exception {
        enableIdentityAndNetworking();

        runOnFxThreadAndWait(() -> {
            controller.invitationPassphraseField.setText(new String(PASSPHRASE));
            controller.createInvitation();
        });
        waitUntil(() -> fxRead(() -> controller.myInvitationArea.getText()), text -> text != null && !text.isBlank());

        runOnFxThreadAndWait(() -> {
            String text = controller.myInvitationArea.getText();
            // Must round-trip as a real, verifiable signed invitation - not just non-empty text.
            assertTrue(InvitationCodec.fromBase64(text).invitation().identityKey() != null);
            assertFalse(controller.copyInvitationButton.isDisabled());
            assertTrue(controller.invitationResultBox.isVisible(), "the invitation text appears once there is one");
            assertEquals("", controller.invitationPassphraseField.getText(), "the passphrase field must be cleared right after use");
            assertTrue(controller.invitationFeedbackLabel.isVisible());
            assertTrue(controller.invitationFeedbackLabel.getText().contains("Invitation ready"));
            assertTrue(controller.ownFingerprintBox.isVisible(), "my full fingerprint must be on screen for the other person to compare");
            assertFalse(controller.ownFingerprintLabel.getText().contains("…"), "the fingerprint used for verification is never abbreviated");
        });
    }

    @Test
    void creatingInvitationWithoutReenteringThePassphraseShowsLocalGuidance() throws Exception {
        enableIdentityAndNetworking();

        runOnFxThreadAndWait(controller::createInvitation);

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.myInvitationArea.getText().isBlank());
            assertTrue(controller.copyInvitationButton.isDisabled());
            assertFalse(controller.invitationResultBox.isVisible());
            assertTrue(controller.invitationFeedbackLabel.isVisible());
            assertTrue(controller.invitationFeedbackLabel.getText().contains("vault passphrase"));
        });
    }

    @Test
    void parsingAnEmptyInvitationShowsFeedbackInThePairingPanel() throws Exception {
        runOnFxThreadAndWait(controller::parseInvitation);

        runOnFxThreadAndWait(() -> {
            assertTrue(controller.registerAndAcceptButton.isDisabled());
            assertTrue(controller.pairingFeedbackLabel.isVisible());
            assertTrue(controller.pairingFeedbackLabel.getText().contains("Paste their invitation"));
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
            assertFalse(controller.verifyBox.isVisible());
            assertTrue(controller.pairingFeedbackLabel.isVisible());
            assertTrue(controller.pairingFeedbackLabel.getText().contains("invitation"));
            assertFalse(controller.pairingFeedbackLabel.getText().contains("MALFORMED"), "no internal enum name may reach the screen");
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
            assertTrue(controller.verifyBox.isVisible(), "the verification step must appear before anything can be accepted");
            assertTrue(controller.parsedFingerprintLabel.isVisible());
            IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
            assertEquals(IdentityFingerprint.format(strangerKey.id()), controller.parsedFingerprintLabel.getText(),
                    "the FULL fingerprint must be shown for out-of-band verification");
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
            assertEquals("Connect", action.getText());
            assertTrue(connectionDetailLabel(controller.contactsBox.getChildren().get(0)).getText().contains("Turn on networking"));
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

        waitUntil(() -> fxRead(() -> cardFeedbackLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.startsWith("Couldn't connect"));

        runOnFxThreadAndWait(() -> {
            Node card = controller.contactsBox.getChildren().get(0);
            assertTrue(rowStatusLabel(card).getText().startsWith("Offline"), "a failed dial must render as Offline, not stay stuck on Connecting");
            assertTrue(cardFeedbackLabel(card).isVisible(), "the failure is explained on the peer's own card, beside the Connect button");
            assertFalse(cardFeedbackLabel(card).getText().matches(".*[A-Z]{3,}_[A-Z_]+.*"), "no raw enum name may reach the screen: "
                    + cardFeedbackLabel(card).getText());
            assertFalse(controller.statusLabel.isVisible(), "a connection failure no longer shows as a far-away page-level message");
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
            Button actionButton = actionButtonOf(controller.contactsBox.getChildren().get(0));
            assertTrue(actionButton.isDisabled(), "the action button must be disabled, not merely unresponsive, while a dial is in flight");
        });

        waitUntil(() -> fxRead(() -> cardFeedbackLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.startsWith("Couldn't connect"));

        runOnFxThreadAndWait(() -> {
            Label rowStatus = rowStatusLabel(controller.contactsBox.getChildren().get(0));
            assertTrue(rowStatus.getText().startsWith("Offline"), "the in-flight state must be released once the single dial completes");
            Button actionButton = actionButtonOf(controller.contactsBox.getChildren().get(0));
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
        // Networking must be on: with it off the card's detail line is a fixed "turn on networking" hint.
        enableIdentityAndNetworking();
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

        waitUntil(() -> fxRead(() -> connectionDetailLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text.contains("reach"));
        runOnFxThreadAndWait(() -> assertEquals("Offline", rowStatusLabel(controller.contactsBox.getChildren().get(0)).getText(),
                "the pill stays short; the reason lives in the detail line"));
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

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(shown(start15ButtonOf(row)), "durations are only offered after \"Start match\" is clicked");
            startMatchButtonOf(row).fire();
            assertTrue(shown(start15ButtonOf(row)));
            assertTrue(shown(start30ButtonOf(row)));
            assertTrue(shown(start60ButtonOf(row)));
            assertFalse(startMatchButtonOf(row).isVisible());
            start15ButtonOf(row).fire();
        });
        waitUntil(() -> fxRead(() -> matchStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.contains("Waiting for your peer to accept"));

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(cancelMatchButtonOf(row).isDisabled());
            assertTrue(cancelMatchButtonOf(row).isVisible());
            assertFalse(shown(start15ButtonOf(row)));
            assertFalse(startMatchButtonOf(row).isVisible());
        });

        runOnFxThreadAndWait(() -> cancelMatchButtonOf(controller.contactsBox.getChildren().get(0)).fire());
        waitUntil(() -> fxRead(() -> matchStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.contains("You cancelled"));

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(startMatchButtonOf(row).isVisible(), "terminal cancellation should immediately expose a new match action");
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

        waitUntil(() -> fxRead(() -> comparisonSectionOf(controller.contactsBox.getChildren().get(0)).isVisible()), visible -> visible);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            String text = comparisonMessageText(row);
            assertTrue(text.contains("hasn't shared"), "no consent was ever received from this peer, and the message must be plain product copy, "
                    + "never an engine reason name: " + text);
            assertFalse(comparisonCutoffLabelOf(row).isVisible(), "no cutoff was ever actually compared, so no 'Compared through' line should show");
        });
    }

    @Test
    @Timeout(15)
    void comparisonSectionAndStudyMatchSectionCoexistAndTheComparisonSurvivesARepaint() throws Exception {
        identityService.createIdentity(PASSPHRASE.clone(), now());
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(comparisonSectionOf(row).isVisible(), "Today's progress starts hidden until Compare today has actually run");
            assertTrue(matchSectionOf(row).isVisible(), "Study Match is always present - it is itself the 'start a match' entry point");
        });

        runOnFxThreadAndWait(() -> compareButtonOf(controller.contactsBox.getChildren().get(0)).fire());
        waitUntil(() -> fxRead(() -> comparisonSectionOf(controller.contactsBox.getChildren().get(0)).isVisible()), visible -> visible);

        // Starting a match repaints the whole card list. The comparison the learner is looking at must
        // survive that repaint (it used to vanish), alongside the new match state.
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            startMatchButtonOf(row).fire();
            start15ButtonOf(row).fire();
        });
        waitUntil(() -> fxRead(() -> matchStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.contains("Waiting for"));

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(comparisonSectionOf(row).isVisible(), "the comparison must still be showing after the card was repainted");
            assertTrue(comparisonMessageText(row).contains("hasn't shared"));
            assertTrue(matchSectionOf(row).isVisible());
            assertTrue(cancelMatchButtonOf(row).isVisible());
        });

        runOnFxThreadAndWait(() -> hideComparisonButtonOf(controller.contactsBox.getChildren().get(0)).fire());
        runOnFxThreadAndWait(() -> assertFalse(comparisonSectionOf(controller.contactsBox.getChildren().get(0)).isVisible()));
    }

    @Test
    @Timeout(15)
    void theAddPeerPanelStaysCollapsedUntilAskedForAndTheEmptyStateOffersTheSameEntryPoint() throws Exception {
        runOnFxThreadAndWait(() -> {
            assertFalse(controller.addPeerPanel.isVisible());
            assertTrue(controller.emptyPeersBox.isVisible(), "no peers yet: the empty state is shown");
            assertEquals("Add a peer", controller.addPeerButton.getText());

            controller.emptyAddPeerButton.fire();
            assertTrue(controller.addPeerPanel.isVisible());
            assertFalse(controller.emptyPeersBox.isVisible(), "its call to action is redundant while the panel is open");
            assertEquals("Close", controller.addPeerButton.getText());
            assertTrue(controller.createInvitationButton.isDisabled(), "no invitation can be created before networking is on");
            assertTrue(controller.invitationHintLabel.isVisible(), "and the screen says why");
            assertFalse(controller.parseInvitationButton.isDisabled(), "adding THEIR invitation never needed networking");

            controller.addPeerButton.fire();
            assertFalse(controller.addPeerPanel.isVisible());
            assertTrue(controller.emptyPeersBox.isVisible());
        });
    }

    @Test
    @Timeout(15)
    void chosenMatchDurationIsExactlyWhatGetsStarted() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        long contactId = contact.id();

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            startMatchButtonOf(row).fire();
            start30ButtonOf(row).fire();
        });
        waitUntil(() -> fxRead(() -> matchStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.startsWith("30 min"));

        assertEquals(MatchDuration.THIRTY_MINUTES, new com.codefit.service.PeerMatchService().matchesFor(contactId).get(0).duration());
    }

    @Test
    @Timeout(15)
    void anOpenDurationPickerSurvivesARepaintAndCanBeDismissed() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contactService.acceptInvitation(contact.id(), now());

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        runOnFxThreadAndWait(() -> startMatchButtonOf(controller.contactsBox.getChildren().get(0)).fire());
        // A connection event (or anything else) repaints every card; the picker must not snap shut.
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(shown(start15ButtonOf(row)));
            ((Button) row.lookup("#peer-match-picker-cancel-button")).fire();
            assertFalse(shown(start15ButtonOf(row)));
            assertTrue(startMatchButtonOf(row).isVisible());
        });
    }

    @Test
    @Timeout(15)
    void acceptingAndDecliningAnIncomingMatchCallsTheMatchService() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        long contactId = contact.id();
        com.codefit.repository.MatchRepository matchRepository = new com.codefit.repository.MatchRepository();
        com.codefit.service.PeerMatchService matchService = new com.codefit.service.PeerMatchService();

        ObjectId declined = randomMatchId(11);
        try (var connection = com.codefit.config.DatabaseConfig.getConnection()) {
            matchRepository.applyReceivedInvitation(connection, contactId, declined, MatchDuration.FIFTEEN_MINUTES, now());
        }
        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(matchStatusLabel(row).getText().contains("invited you to a 15-minute Study Match"));
            declineMatchButtonOf(row).fire();
        });
        waitUntil(() -> matchService.find(declined).map(m -> m.status()).orElse(null),
                status -> status == com.codefit.peer.match.MatchStatus.DECLINED);

        ObjectId accepted = randomMatchId(12);
        try (var connection = com.codefit.config.DatabaseConfig.getConnection()) {
            matchRepository.applyReceivedInvitation(connection, contactId, accepted, MatchDuration.THIRTY_MINUTES, now().plusMillis(5));
        }
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> acceptMatchButtonOf(controller.contactsBox.getChildren().get(0)).fire());
        waitUntil(() -> matchService.find(accepted).map(m -> m.status()).orElse(null),
                status -> status == com.codefit.peer.match.MatchStatus.ACTIVE);
        waitUntil(() -> fxRead(() -> matchStatusLabel(controller.contactsBox.getChildren().get(0)).getText()),
                text -> text != null && text.contains("remaining"));
        runOnFxThreadAndWait(() -> assertTrue(refreshMatchButtonOf(controller.contactsBox.getChildren().get(0)).isVisible()));
    }

    @Test
    @Timeout(20)
    void refreshingAnActiveMatchShowsItsProgressOnTheCardAndKeepsItAcrossARepaint() throws Exception {
        identityService.createIdentity(PASSPHRASE.clone(), now());
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        long contactId = contact.id();
        com.codefit.repository.MatchRepository matchRepository = new com.codefit.repository.MatchRepository();
        ObjectId matchId = randomMatchId(21);
        matchRepository.createChallengerInvitation(contactId, matchId, MatchDuration.FIFTEEN_MINUTES, now());
        matchRepository.recordLocalResponse(matchId, true, now(), now());

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(matchProgressBodyOf(row).getParent().isVisible(), "no progress is shown before Refresh progress is used");
            refreshMatchButtonOf(row).fire();
        });
        waitUntil(() -> fxRead(() -> matchProgressBodyOf(controller.contactsBox.getChildren().get(0)).getParent().isVisible()), v -> v);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(((javafx.scene.layout.VBox) matchProgressBodyOf(row)).getChildren().isEmpty());
            controller.initialize(); // repaint
        });
        runOnFxThreadAndWait(() -> assertTrue(matchProgressBodyOf(controller.contactsBox.getChildren().get(0)).getParent().isVisible(),
                "the progress being looked at must survive a repaint of the same match"));
    }

    @Test
    @Timeout(15)
    void correctStudyMatchActionsAreShownForEachMatchState() throws Exception {
        KeyPair strangerIdentity = KeyPairs.generate();
        IdentityKey strangerKey = new IdentityKey(KeyPairs.rawPublicKey(strangerIdentity.getPublic()));
        Contact contact = contactService.registerPendingContact(strangerKey, "", now());
        contact = contactService.acceptInvitation(contact.id(), now());
        long contactId = contact.id();

        com.codefit.repository.MatchRepository matchRepository = new com.codefit.repository.MatchRepository();

        runOnFxThreadAndWait(controller::initialize);
        waitUntil(() -> fxRead(() -> controller.contactsBox.getChildren().size()), size -> size == 1);

        // No match yet: only the Start entry point.
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(startMatchButtonOf(row).isVisible());
            assertEquals("Start match", startMatchButtonOf(row).getText());
            assertFalse(cancelMatchButtonOf(row).isVisible());
            assertFalse(acceptMatchButtonOf(row).isVisible());
            assertFalse(declineMatchButtonOf(row).isVisible());
            assertFalse(refreshMatchButtonOf(row).isVisible());
            assertTrue(matchStatusLabel(row).getText().startsWith("Challenge"));
        });

        // PENDING, challenger role (this device started it): only Cancel.
        ObjectId challengerMatchId = randomMatchId(1);
        matchRepository.createChallengerInvitation(contactId, challengerMatchId, MatchDuration.FIFTEEN_MINUTES, now());
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(startMatchButtonOf(row).isVisible());
            assertTrue(cancelMatchButtonOf(row).isVisible());
            assertFalse(acceptMatchButtonOf(row).isVisible());
            assertFalse(refreshMatchButtonOf(row).isVisible());
            assertEquals("Waiting", matchStateLabel(row).getText());
            assertTrue(matchStatusLabel(row).getText().contains("Waiting for your peer to accept"));
        });

        // ACTIVE: only Refresh progress, with a countdown.
        matchRepository.recordLocalResponse(challengerMatchId, true, now(), now());
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(startMatchButtonOf(row).isVisible());
            assertFalse(cancelMatchButtonOf(row).isVisible());
            assertTrue(refreshMatchButtonOf(row).isVisible());
            assertEquals("Refresh progress", refreshMatchButtonOf(row).getText());
            assertEquals("Active", matchStateLabel(row).getText());
            assertTrue(matchStatusLabel(row).getText().contains("remaining"));
            assertTrue(row.lookup("#peer-match-offline-label").isVisible(), "this peer is not connected, so the card says the numbers are last-synced");
        });

        // COMPLETED: Refresh progress becomes View results, and starting a new one is offered again.
        matchRepository.completeIfPastEndsAt(challengerMatchId, now().plus(Duration.ofMinutes(20)));
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(refreshMatchButtonOf(row).isVisible());
            assertEquals("View results", refreshMatchButtonOf(row).getText());
            assertTrue(startMatchButtonOf(row).isVisible(), "a terminal match still offers starting a new one");
            assertEquals("Start a new match", startMatchButtonOf(row).getText());
            assertEquals("Completed", matchStateLabel(row).getText());
        });

        // PENDING, opponent role (a peer invited this device): only Accept / Decline.
        ObjectId opponentMatchId = randomMatchId(2);
        try (var connection = com.codefit.config.DatabaseConfig.getConnection()) {
            matchRepository.applyReceivedInvitation(connection, contactId, opponentMatchId, MatchDuration.THIRTY_MINUTES, now());
        }
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertFalse(startMatchButtonOf(row).isVisible());
            assertTrue(acceptMatchButtonOf(row).isVisible());
            assertTrue(declineMatchButtonOf(row).isVisible());
            assertFalse(cancelMatchButtonOf(row).isVisible());
            assertTrue(matchStatusLabel(row).getText().contains("invited you to a 30-minute Study Match"));
        });

        // DECLINED: terminal, a new match is offered.
        matchRepository.recordLocalResponse(opponentMatchId, false, null, now());
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(startMatchButtonOf(row).isVisible());
            assertFalse(acceptMatchButtonOf(row).isVisible());
            assertFalse(declineMatchButtonOf(row).isVisible());
            assertEquals("Declined", matchStateLabel(row).getText());
        });

        // CANCELLED: terminal, a new match is offered.
        ObjectId cancelledMatchId = randomMatchId(3);
        matchRepository.createChallengerInvitation(contactId, cancelledMatchId, MatchDuration.FIFTEEN_MINUTES, now());
        matchRepository.recordLocalCancellation(cancelledMatchId, now());
        runOnFxThreadAndWait(controller::initialize);
        runOnFxThreadAndWait(() -> {
            Node row = controller.contactsBox.getChildren().get(0);
            assertTrue(startMatchButtonOf(row).isVisible());
            assertFalse(cancelMatchButtonOf(row).isVisible());
            assertEquals("Cancelled", matchStateLabel(row).getText());
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

    private static ObjectId randomMatchId(int seed) {
        byte[] bytes = new byte[ObjectId.LENGTH];
        bytes[0] = (byte) seed;
        bytes[ObjectId.LENGTH - 1] = (byte) (seed >>> 8);
        return new ObjectId(bytes);
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

    // Every interactive/readable node in a peer card carries a stable id (see
    // PeerController.buildContactRow) - looked up by id rather than by fixed child-index, which is
    // far more robust to the card's own section-based layout than the previous single flat HBox row.

    private static Label rowStatusLabel(Node row) {
        return (Label) row.lookup("#peer-connection-status-label");
    }

    private static Button actionButtonOf(Node row) {
        return (Button) row.lookup("#peer-connect-button");
    }

    private static void fireConnectButton(Node row) {
        actionButtonOf(row).fire();
    }

    private static Label matchStatusLabel(Node row) {
        return (Label) row.lookup("#peer-match-status-label");
    }

    /** True only if the node AND every ancestor up to the card is visible - a hidden picker hides its buttons too. */
    private static boolean shown(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static Button startMatchButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-start-button");
    }

    private static Button start30ButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-start-30-button");
    }

    private static Button start60ButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-start-60-button");
    }

    private static Label matchStateLabel(Node row) {
        return (Label) row.lookup("#peer-match-state-label");
    }

    private static Node matchProgressBodyOf(Node row) {
        return row.lookup("#peer-match-progress-body");
    }

    private static Label cardFeedbackLabel(Node row) {
        return (Label) row.lookup("#peer-feedback-label");
    }

    private static Label connectionDetailLabel(Node row) {
        return (Label) row.lookup("#peer-connection-detail-label");
    }

    private static Button hideComparisonButtonOf(Node row) {
        return (Button) row.lookup("#peer-comparison-hide-button");
    }

    private static Button start15ButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-start-15-button");
    }

    private static Button acceptMatchButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-accept-button");
    }

    private static Button declineMatchButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-decline-button");
    }

    private static Button cancelMatchButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-cancel-button");
    }

    private static Button refreshMatchButtonOf(Node row) {
        return (Button) row.lookup("#peer-match-refresh-button");
    }

    private static Button shareButtonOf(Node row) {
        return (Button) row.lookup("#peer-share-button");
    }

    private static Button compareButtonOf(Node row) {
        return (Button) row.lookup("#peer-compare-button");
    }

    private static Node comparisonSectionOf(Node row) {
        return row.lookup("#peer-comparison-section");
    }

    private static Node comparisonBodyOf(Node row) {
        return row.lookup("#peer-comparison-body");
    }

    private static Label comparisonCutoffLabelOf(Node row) {
        return (Label) row.lookup("#peer-comparison-cutoff-label");
    }

    /** The comparison body's own rendered message, for the MESSAGE/EMPTY cases (a single {@code
     *  Label} child) - not applicable to the ROWS case, which renders a {@code GridPane} instead. */
    private static String comparisonMessageText(Node row) {
        javafx.scene.layout.VBox body = (javafx.scene.layout.VBox) comparisonBodyOf(row);
        return ((Label) body.getChildren().get(0)).getText();
    }

    private static Node matchSectionOf(Node row) {
        return row.lookup("#peer-match-section");
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

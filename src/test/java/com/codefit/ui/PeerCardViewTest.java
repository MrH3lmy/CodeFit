package com.codefit.ui;

import com.codefit.peer.match.MatchRole;
import com.codefit.peer.match.MatchStatus;
import com.codefit.peer.match.StudyMatch;
import com.codefit.peer.protocol.MatchDuration;
import com.codefit.peer.protocol.ObjectId;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers {@link PeerCardView} on its own, without any backend: the card is a pure function of its
 * view-models, so every state the Peers screen can be in (including ones that need a live second
 * device, like a connected peer) is constructed directly. Nodes are found by stable id, never by child
 * index, and nothing asserts a pixel value. Skipped when no display is available, like every other
 * test that needs the JavaFX toolkit.
 */
class PeerCardViewTest {
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final String FP = "d1bb 752a 4f3e 99aa 00bb 11cc 22dd 33ee 44ff 55aa 66bb 77cc 88dd 99ee dacf b664";

    @BeforeAll
    static void requireFxToolkit() {
        Assumptions.assumeTrue(FxToolkitSupport.isAvailable(), "JavaFX toolkit unavailable (no display) - skipping");
    }

    /** Records every callback so a test can assert exactly which one a click reached. */
    private static final class Recorder implements PeerCardView.Actions {
        final List<String> calls = new ArrayList<>();

        public void connect(PeerCardView card) { calls.add("connect"); }
        public void disconnect(PeerCardView card) { calls.add("disconnect"); }
        public void compare(PeerCardView card) { calls.add("compare"); }
        public void share(PeerCardView card) { calls.add("share"); }
        public void startMatch(PeerCardView card, MatchDuration duration) { calls.add("start:" + duration.minutes()); }
        public void acceptMatch(PeerCardView card) { calls.add("accept"); }
        public void declineMatch(PeerCardView card) { calls.add("decline"); }
        public void cancelMatch(PeerCardView card) { calls.add("cancel"); }
        public void refreshMatch(PeerCardView card) { calls.add("refresh"); }
        public void matchPickerChanged(PeerCardView card, boolean open) { calls.add("picker:" + open); }
    }

    private static StudyMatch match(MatchRole role, MatchStatus status, MatchDuration duration, Instant endsAt) {
        boolean timed = status == MatchStatus.ACTIVE || status == MatchStatus.COMPLETED;
        return new StudyMatch(new ObjectId(new byte[ObjectId.LENGTH]), 1L, role, duration, status, NOW, timed ? NOW : null,
                timed ? endsAt.minus(Duration.ofMinutes(duration.minutes())) : null, timed ? endsAt : null, NOW);
    }

    private static PeerCardView card(String alias, boolean networking, boolean connected, boolean dialing, Optional<StudyMatch> match,
                                     boolean pickerOpen, Recorder recorder) {
        PeerCardPresentation presentation = PeerCardPresentation.of(alias, "", FP, networking, connected, dialing, false, Optional.empty());
        return new PeerCardView(presentation, StudyMatchPresentation.of(match, connected, presentation.spokenName(), NOW), pickerOpen, recorder);
    }

    private static PeerCardView card(boolean connected, Optional<StudyMatch> match) {
        return card("Ahmed Hassan", true, connected, false, match, false, new Recorder());
    }

    // --- connection state & action hierarchy -----------------------------------------------------

    @Test
    void aConnectedPeerLeadsWithCompareHasNoConnectButtonAndKeepsDisconnectInTheOverflowMenu() throws Exception {
        Recorder recorder = new Recorder();
        fx(() -> {
            PeerCardView card = card("Ahmed Hassan", true, true, false, Optional.empty(), false, recorder);
            assertEquals("Connected", label(card, "peer-connection-status-label").getText());
            assertNull(card.lookup("#peer-connect-button"), "Connect is meaningless while connected");
            assertTrue(button(card, "peer-compare-button").getStyleClass().contains("peer-button-primary"), "Compare leads while connected");
            assertTrue(button(card, "peer-share-button").getStyleClass().contains("peer-button-secondary"));

            MenuButton overflow = (MenuButton) card.lookup("#peer-overflow-button");
            assertNotNull(overflow, "Disconnect must remain reachable");
            assertEquals(List.of("Disconnect"), overflow.getItems().stream().map(MenuItem::getText).toList());
            overflow.getItems().get(0).fire();
            assertEquals(List.of("disconnect"), recorder.calls);
        });
    }

    @Test
    void anOfflinePeerLeadsWithConnectAndHasNoOverflowMenu() throws Exception {
        Recorder recorder = new Recorder();
        fx(() -> {
            PeerCardView card = card("Ahmed Hassan", true, false, false, Optional.empty(), false, recorder);
            assertEquals("Offline", label(card, "peer-connection-status-label").getText());
            Button connect = button(card, "peer-connect-button");
            assertEquals("Connect", connect.getText());
            assertTrue(connect.getStyleClass().contains("peer-button-primary"));
            assertFalse(connect.isDisabled());
            assertTrue(button(card, "peer-compare-button").getStyleClass().contains("peer-button-secondary"),
                    "while offline Connect is the lead action, not Compare");
            assertNull(card.lookup("#peer-overflow-button"), "nothing to put in the menu while offline");
            connect.fire();
            assertEquals(List.of("connect"), recorder.calls);
        });
    }

    @Test
    void aDialInFlightDisablesTheActionAndAnOffNetworkDisablesConnectWithAReason() throws Exception {
        fx(() -> {
            PeerCardView dialing = card("Ahmed", true, false, true, Optional.empty(), false, new Recorder());
            assertEquals("Connecting…", label(dialing, "peer-connection-status-label").getText());
            assertEquals("Connecting…", button(dialing, "peer-connect-button").getText());
            assertTrue(button(dialing, "peer-connect-button").isDisabled());

            PeerCardView noNetwork = card("Ahmed", false, false, false, Optional.empty(), false, new Recorder());
            assertTrue(button(noNetwork, "peer-connect-button").isDisabled());
            assertTrue(label(noNetwork, "peer-connection-detail-label").isVisible());
            assertTrue(label(noNetwork, "peer-connection-detail-label").getText().contains("networking"));
        });
    }

    @Test
    void shareAndCompareReachTheirOwnCallbacksAndNothingElse() throws Exception {
        Recorder recorder = new Recorder();
        fx(() -> {
            PeerCardView card = card("Ahmed", true, true, false, Optional.empty(), false, recorder);
            button(card, "peer-share-button").fire();
            button(card, "peer-compare-button").fire();
            assertEquals(List.of("share", "compare"), recorder.calls);
        });
    }

    // --- today's comparison -------------------------------------------------------------------

    @Test
    void theComparisonIsHiddenUntilUsedThenShowsRowsWithTheCutoffAndOfflineNote() throws Exception {
        fx(() -> {
            PeerCardView card = card(false, Optional.empty());
            assertFalse(card.lookup("#peer-comparison-section").isVisible(), "hidden before Compare today has been used");

            card.showComparison(rows(), Optional.of(Instant.parse("2026-10-06T17:37:00Z")));
            assertTrue(card.lookup("#peer-comparison-section").isVisible());
            assertTrue(label(card, "peer-comparison-cutoff-label").isVisible());
            assertTrue(label(card, "peer-comparison-cutoff-label").getText().startsWith("Compared through"));
            assertTrue(label(card, "peer-comparison-note-label").isVisible(), "an offline peer's numbers are labelled as last synced");
            assertTrue(label(card, "peer-comparison-note-label").getText().contains("offline"));
            assertTrue(allLabelTexts(card.lookup("#peer-comparison-body")).containsAll(List.of("Problems solved", "4", "3", "You", "Ahmed")),
                    "human labels and both sides' values, with the peer's first name as its column");

            button(card, "peer-comparison-hide-button").fire();
            assertFalse(card.lookup("#peer-comparison-section").isVisible());
        });
    }

    @Test
    void aConnectedPeersComparisonHasNoOfflineNote() throws Exception {
        fx(() -> {
            PeerCardView card = card(true, Optional.empty());
            card.showComparison(rows(), Optional.of(Instant.parse("2026-10-06T17:37:00Z")));
            assertFalse(label(card, "peer-comparison-note-label").isVisible());
        });
    }

    @Test
    void anEmptyOrUnavailableComparisonIsOneShortMessageAndNeverClaimsACutoff() throws Exception {
        fx(() -> {
            PeerCardView card = card(true, Optional.empty());
            card.showComparison(new PeerComparisonPresentation.Rendered(PeerComparisonPresentation.Kind.EMPTY, List.of(),
                    "No study activity recorded by either person yet today."), Optional.of(Instant.parse("2026-10-06T17:37:00Z")));
            assertEquals(List.of("No study activity recorded by either person yet today."),
                    allLabelTexts(card.lookup("#peer-comparison-body")));

            card.showComparison(new PeerComparisonPresentation.Rendered(PeerComparisonPresentation.Kind.MESSAGE, List.of(),
                    "Waiting for Ahmed's progress…"), Optional.of(Instant.parse("2026-10-06T17:37:00Z")));
            assertEquals(List.of("Waiting for Ahmed's progress…"), allLabelTexts(card.lookup("#peer-comparison-body")));
            assertFalse(label(card, "peer-comparison-cutoff-label").isVisible(), "nothing was compared, so no 'Compared through' line");

            card.showComparisonError("Couldn't load the comparison. Please try again.");
            assertEquals(List.of("Couldn't load the comparison. Please try again."), allLabelTexts(card.lookup("#peer-comparison-body")));
        });
    }

    // --- study match states -------------------------------------------------------------------

    @Test
    void noMatchOffersStartAndOnlyRevealsDurationsOnDemand() throws Exception {
        Recorder recorder = new Recorder();
        fx(() -> {
            PeerCardView card = card("Ahmed", true, true, false, Optional.empty(), false, recorder);
            assertEquals("Challenge Ahmed to a focused study session.", label(card, "peer-match-status-label").getText());
            assertNull(card.lookup("#peer-match-state-label"), "no match, no state badge");
            assertTrue(shown(button(card, "peer-match-start-button")));
            assertFalse(shown(button(card, "peer-match-start-15-button")), "durations stay out of the way until asked for");

            button(card, "peer-match-start-button").fire();
            assertFalse(shown(button(card, "peer-match-start-button")));
            assertTrue(shown(button(card, "peer-match-start-15-button")));
            assertTrue(shown(button(card, "peer-match-start-30-button")));
            assertTrue(shown(button(card, "peer-match-start-60-button")));

            button(card, "peer-match-picker-cancel-button").fire();
            assertTrue(shown(button(card, "peer-match-start-button")));
            assertFalse(shown(button(card, "peer-match-start-15-button")));

            button(card, "peer-match-start-button").fire();
            button(card, "peer-match-start-60-button").fire();
            assertEquals(List.of("picker:true", "picker:false", "picker:true", "start:60"), recorder.calls,
                    "the duration clicked is exactly the duration handed to the owner");
        });
    }

    @Test
    void aPickerThatWasOpenBeforeARepaintIsStillOpen() throws Exception {
        fx(() -> {
            PeerCardView card = card("Ahmed", true, true, false, Optional.empty(), true, new Recorder());
            assertTrue(shown(button(card, "peer-match-start-30-button")));
            assertFalse(shown(button(card, "peer-match-start-button")));
        });
    }

    @Test
    void aPendingOutgoingInvitationShowsWaitingAndOnlyCancel() throws Exception {
        Recorder recorder = new Recorder();
        fx(() -> {
            PeerCardView card = card("Ahmed", true, true, false,
                    Optional.of(match(MatchRole.CHALLENGER, MatchStatus.PENDING, MatchDuration.FIFTEEN_MINUTES, null)), false, recorder);
            assertEquals("Waiting", label(card, "peer-match-state-label").getText());
            assertEquals("15 min · Waiting for Ahmed to accept…", label(card, "peer-match-status-label").getText());
            assertOnlyMatchActions(card, "peer-match-cancel-button");
            button(card, "peer-match-cancel-button").fire();
            assertEquals(List.of("cancel"), recorder.calls);
        });
    }

    @Test
    void anIncomingInvitationShowsAcceptAndDecline() throws Exception {
        Recorder recorder = new Recorder();
        fx(() -> {
            PeerCardView card = card("Ahmed", true, true, false,
                    Optional.of(match(MatchRole.OPPONENT, MatchStatus.PENDING, MatchDuration.THIRTY_MINUTES, null)), false, recorder);
            assertEquals("Ahmed invited you to a 30-minute Study Match.", label(card, "peer-match-status-label").getText());
            assertOnlyMatchActions(card, "peer-match-accept-button", "peer-match-decline-button");
            assertTrue(button(card, "peer-match-accept-button").getStyleClass().contains("peer-button-primary"));
            button(card, "peer-match-accept-button").fire();
            button(card, "peer-match-decline-button").fire();
            assertEquals(List.of("accept", "decline"), recorder.calls);
        });
    }

    @Test
    void anActiveMatchShowsItsCountdownRefreshAndAnOfflineNoteAndTicksDown() throws Exception {
        Recorder recorder = new Recorder();
        fx(() -> {
            Instant ends = Instant.now().plusSeconds(522);
            PeerCardPresentation presentation = PeerCardPresentation.of("Ahmed", "", FP, true, false, false, false, Optional.empty());
            StudyMatch active = match(MatchRole.CHALLENGER, MatchStatus.ACTIVE, MatchDuration.FIFTEEN_MINUTES, ends);
            PeerCardView card = new PeerCardView(presentation,
                    StudyMatchPresentation.of(Optional.of(active), false, "Ahmed", Instant.now()), false, recorder);
            assertEquals("Active", label(card, "peer-match-state-label").getText());
            assertTrue(label(card, "peer-match-status-label").getText().contains("remaining"));
            assertTrue(label(card, "peer-match-offline-label").isVisible());
            assertOnlyMatchActions(card, "peer-match-refresh-button");
            assertEquals("Refresh progress", button(card, "peer-match-refresh-button").getText());

            assertFalse(card.tick(ends.minusSeconds(60)));
            assertTrue(label(card, "peer-match-status-label").getText().startsWith("01:00 remaining"));
            assertTrue(card.tick(ends), "reaching zero tells the owner to repaint into the completed state");

            button(card, "peer-match-refresh-button").fire();
            assertEquals(List.of("refresh"), recorder.calls);
        });
    }

    @Test
    void aCompletedMatchOffersViewResultsAndANewMatchAndShowsItsVerdict() throws Exception {
        fx(() -> {
            PeerCardView card = card(true, Optional.of(match(MatchRole.CHALLENGER, MatchStatus.COMPLETED, MatchDuration.SIXTY_MINUTES, NOW)));
            assertEquals("Completed", label(card, "peer-match-state-label").getText());
            assertEquals("View results", button(card, "peer-match-refresh-button").getText());
            assertOnlyMatchActions(card, "peer-match-refresh-button", "peer-match-start-button");
            assertEquals("Start a new match", button(card, "peer-match-start-button").getText());

            assertFalse(card.lookup("#peer-match-progress-body").getParent().isVisible(), "no results until asked for");
            card.showMatchProgress(rows(), Optional.of(Instant.parse("2026-10-06T17:37:00Z")), Optional.of("You're ahead."));
            assertTrue(card.lookup("#peer-match-progress-body").getParent().isVisible());
            assertEquals("You're ahead.", label(card, "peer-match-verdict-label").getText());
            assertTrue(label(card, "peer-match-progress-cutoff-label").isVisible());

            card.showMatchProgress(rows(), Optional.empty(), Optional.empty());
            assertFalse(label(card, "peer-match-verdict-label").isVisible(), "no verdict derived, none claimed");
        });
    }

    @Test
    void declinedAndCancelledMatchesOfferANewMatchOnly() throws Exception {
        fx(() -> {
            PeerCardView declined = card(true, Optional.of(match(MatchRole.CHALLENGER, MatchStatus.DECLINED, MatchDuration.FIFTEEN_MINUTES, null)));
            assertEquals("Declined", label(declined, "peer-match-state-label").getText());
            assertOnlyMatchActions(declined, "peer-match-start-button");
            PeerCardView cancelled = card(true, Optional.of(match(MatchRole.CHALLENGER, MatchStatus.CANCELLED, MatchDuration.FIFTEEN_MINUTES, null)));
            assertEquals("Cancelled", label(cancelled, "peer-match-state-label").getText());
            assertOnlyMatchActions(cancelled, "peer-match-start-button");
        });
    }

    @Test
    void cardFeedbackIsShownBesideTheActionAndClearsAgain() throws Exception {
        fx(() -> {
            PeerCardView card = card(false, Optional.empty());
            assertFalse(label(card, "peer-feedback-label").isVisible());
            card.setFeedback("Couldn't connect.", PeerCardView.FeedbackTone.ERROR);
            assertTrue(label(card, "peer-feedback-label").isVisible());
            assertTrue(label(card, "peer-feedback-label").getStyleClass().contains("feedback-error"));
            card.setFeedback("Sharing today's progress.", PeerCardView.FeedbackTone.SUCCESS);
            assertFalse(label(card, "peer-feedback-label").getStyleClass().contains("feedback-error"));
            assertTrue(label(card, "peer-feedback-label").getStyleClass().contains("feedback-success"));
            card.setFeedback(null, PeerCardView.FeedbackTone.INFO);
            assertFalse(label(card, "peer-feedback-label").isVisible());
        });
    }

    // --- structure: long text, narrow widths ---------------------------------------------------------

    @Test
    void aVeryLongNameAndLongMessagesNeverWidenTheCardPastItsContainer() throws Exception {
        fx(() -> {
            String longName = "Mohamed Abdelrahman El-Sayed Ibrahim the Third of Alexandria and Beyond";
            PeerCardView card = card(longName, true, false, false,
                    Optional.of(match(MatchRole.CHALLENGER, MatchStatus.PENDING, MatchDuration.SIXTY_MINUTES, null)), false, new Recorder());
            card.setFeedback("Couldn't connect. The other device couldn't be reached. Check that it's online and on the same network.",
                    PeerCardView.FeedbackTone.ERROR);
            card.showComparison(rows(), Optional.of(Instant.parse("2026-10-06T17:37:00Z")));

            // The app's narrowest window leaves roughly 540px for the page; 340 is a deliberate stress well below that.
            for (double width : new double[]{480, 340}) {
                assertFitsWithinWidth(card, width, longName);
            }
        });
    }

    private static void assertFitsWithinWidth(PeerCardView card, double width, String longName) {
        {
            StackPane host = new StackPane();
            host.getChildren().add(card);
            Scene scene = new Scene(host, width, 1600);
            host.getStyleClass().add("theme-dark");
            for (String sheet : List.of("/css/tokens.css", "/css/base.css", "/css/controls.css", "/css/peer.css")) {
                scene.getStylesheets().add(PeerCardViewTest.class.getResource(sheet).toExternalForm());
            }
            host.applyCss();
            host.layout();

            assertTrue(card.getWidth() <= width + 0.5, "card wider than its container: " + card.getWidth());
            assertEquals(longName, label(card, "peer-name-label").getText(), "the full name is kept (for the tooltip), only its display ellipsises");
            assertTrue(label(card, "peer-name-label").getMinWidth() == 0 || label(card, "peer-name-label").getWidth() < width);
            List<String> overflowing = new ArrayList<>();
            collectHorizontalOverflow(card, card, overflowing);
            assertTrue(overflowing.isEmpty(), "nodes extending past the card's right edge: " + overflowing);
            for (Button button : allButtons(card)) {
                if (shown(button)) {
                    assertTrue(button.getWidth() >= button.prefWidth(-1) - 0.5,
                            "button '" + button.getText() + "' was squeezed below its natural width and would truncate");
                }
            }
        }
    }

    // --- helpers -------------------------------------------------------------------------------------

    private static PeerComparisonPresentation.Rendered rows() {
        return new PeerComparisonPresentation.Rendered(PeerComparisonPresentation.Kind.ROWS, List.of(
                new PeerComparisonPresentation.Row("Problems solved", "4", "3"),
                new PeerComparisonPresentation.Row("Reviews", "20", "14"),
                new PeerComparisonPresentation.Row("Review accuracy", "85%", "79%")), null);
    }

    private static final List<String> MATCH_ACTION_IDS = List.of("peer-match-start-button", "peer-match-accept-button",
            "peer-match-decline-button", "peer-match-cancel-button", "peer-match-refresh-button");

    /** Exactly these top-level match actions are shown - and no other. */
    private static void assertOnlyMatchActions(PeerCardView card, String... expectedIds) {
        List<String> expected = List.of(expectedIds);
        for (String id : MATCH_ACTION_IDS) {
            assertEquals(expected.contains(id), shown(button(card, id)), id);
        }
    }

    private static Button button(Node root, String id) {
        Node node = root.lookup("#" + id);
        assertNotNull(node, "no node with id " + id);
        return (Button) node;
    }

    private static Label label(Node root, String id) {
        Node node = root.lookup("#" + id);
        assertNotNull(node, "no node with id " + id);
        return (Label) node;
    }

    /** True only if the node and every ancestor is visible - a hidden container hides its buttons too. */
    private static boolean shown(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (!current.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> allLabelTexts(Node root) {
        List<String> texts = new ArrayList<>();
        collect(root, Label.class, label -> {
            if (!label.getText().isBlank()) {
                texts.add(label.getText());
            }
        });
        return texts;
    }

    private static List<Button> allButtons(Node root) {
        List<Button> buttons = new ArrayList<>();
        collect(root, Button.class, buttons::add);
        return buttons;
    }

    private static <T extends Node> void collect(Node node, Class<T> type, java.util.function.Consumer<T> sink) {
        if (type.isInstance(node)) {
            sink.accept(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, sink);
            }
        }
    }

    private static void collectHorizontalOverflow(Node card, Node node, List<String> out) {
        if (!shown(node)) {
            return;
        }
        Bounds inCard = card.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        if (inCard != null && inCard.getMaxX() > card.getBoundsInLocal().getMaxX() + 1) {
            out.add(node.getClass().getSimpleName() + "#" + node.getId() + " maxX=" + inCard.getMaxX());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectHorizontalOverflow(card, child, out);
            }
        }
    }

    private static void fx(Runnable body) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(15, TimeUnit.SECONDS)) {
            fail("Timed out on the JavaFX thread");
        }
        if (failure.get() instanceof AssertionError error) {
            throw error;
        }
        if (failure.get() != null) {
            fail(failure.get());
        }
    }
}

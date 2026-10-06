package com.codefit.ui;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.transport.ConnectionEvent;
import com.codefit.peer.transport.ConnectionFailureReason;
import com.codefit.peer.transport.ConnectionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure unit coverage of {@link PeerConnectionPresenter} - no JavaFX toolkit, no database, no sockets.
 * This is purely the string-mapping/event-cache logic: ground truth ({@code activeConnectionPresent})
 * always wins over a cached event, and events are never applied out of order.
 */
class PeerConnectionPresenterTest {

    private static IdentityId randomIdentityId() {
        byte[] bytes = new byte[32];
        new java.util.Random().nextBytes(bytes);
        return new IdentityId(bytes);
    }

    @Test
    void withNoEventEverRecordedAnUnconnectedContactIsOffline() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        assertEquals("Offline", presenter.displayTextFor(randomIdentityId(), false));
    }

    @Test
    void groundTruthAlwaysWinsEvenOverACachedRejection() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.REJECTED, ConnectionFailureReason.WRONG_PIN, "nope", Instant.now()));

        assertEquals("Connected", presenter.displayTextFor(contact, true),
                "activeConnectionPresent=true must win outright over a stale cached rejection");
    }

    @Test
    void aConnectingEventIsShownWhileNotYetConnected() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(ConnectionEventFixtures.of(contact, ConnectionState.CONNECTING));

        assertEquals("Connecting…", presenter.displayTextFor(contact, false));
    }

    @Test
    void unknownIdentityRejectionExplainsTheOtherSideHasNotImportedTheInvitation() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.REJECTED, ConnectionFailureReason.UNKNOWN_IDENTITY,
                "Not a known contact", Instant.now()));

        String text = presenter.displayTextFor(contact, false);
        assertEquals("Offline (the other device has not imported your invitation yet)", text);
    }

    @Test
    void notPairedRejectionExplainsTheOtherSideHasNotAcceptedYet() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.REJECTED, ConnectionFailureReason.NOT_PAIRED,
                "Contact is pending", Instant.now()));

        String text = presenter.displayTextFor(contact, false);
        assertEquals("Offline (the other device has not accepted your invitation yet (or has blocked/removed you))", text);
    }

    @Test
    void unreachableIsDistinguishedFromRejected() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(ConnectionEventFixtures.of(contact, ConnectionState.UNREACHABLE));

        assertEquals("Offline (could not reach the other device)", presenter.displayTextFor(contact, false));
    }

    @Test
    void aStaleOutOfOrderEventNeverOverwritesANewerOne() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        Instant later = Instant.now();
        Instant earlier = later.minus(5, ChronoUnit.SECONDS);

        // The newer event (CONNECTING) arrives first; the stale, OLDER-timestamped event (a rejection
        // from an earlier attempt, delivered late by a different background thread) must not clobber it.
        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.CONNECTING, null, null, later));
        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.REJECTED, ConnectionFailureReason.WRONG_PIN, "late", earlier));

        assertEquals("Connecting…", presenter.displayTextFor(contact, false),
                "a stale, earlier-timestamped event must never overwrite the newer cached one");
    }

    @Test
    void aNewerEventCorrectlyReplacesAnOlderOne() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        Instant earlier = Instant.now().minus(5, ChronoUnit.SECONDS);
        Instant later = Instant.now();

        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.CONNECTING, null, null, earlier));
        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.UNREACHABLE, null, null, later));

        assertEquals("Offline (could not reach the other device)", presenter.displayTextFor(contact, false));
    }

    @Test
    void anEventWithNoAttributableIdentityIsIgnored() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        presenter.onEvent(new ConnectionEvent(null, ConnectionState.CONNECTING, null, null, Instant.now()));
        // No exception, and nothing was recorded for any real contact.
        assertEquals("Offline", presenter.displayTextFor(randomIdentityId(), false));
    }

    @Test
    void onChangeFiresAfterEveryRecordedEventUntilUnsubscribed() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        presenter.setOnChange(fired::incrementAndGet);

        presenter.onEvent(ConnectionEventFixtures.of(randomIdentityId(), ConnectionState.CONNECTING));
        assertEquals(1, fired.get());

        presenter.onEvent(ConnectionEventFixtures.of(randomIdentityId(), ConnectionState.CONNECTED));
        assertEquals(2, fired.get());

        presenter.setOnChange(null);
        presenter.onEvent(ConnectionEventFixtures.of(randomIdentityId(), ConnectionState.CONNECTED));
        assertEquals(2, fired.get(), "no subscriber - no call");
    }

    @Test
    void anIgnoredEventWithNoAttributableIdentityNeverFiresOnChange() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();
        presenter.setOnChange(fired::incrementAndGet);

        presenter.onEvent(new ConnectionEvent(null, ConnectionState.CONNECTING, null, null, Instant.now()));

        assertEquals(0, fired.get());
    }

    @Test
    void clearForgetsEveryCachedEvent() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(ConnectionEventFixtures.of(contact, ConnectionState.CONNECTING));
        presenter.clear();

        assertEquals("Offline", presenter.displayTextFor(contact, false));
    }

    /** {@code ConnectionEvent.of} is package-private; this mirrors its exact construction for tests
     *  outside {@code com.codefit.peer.transport}. */
    private static final class ConnectionEventFixtures {
        static ConnectionEvent of(IdentityId remoteIdentityId, ConnectionState state) {
            return new ConnectionEvent(remoteIdentityId, state, null, null, Instant.now());
        }
    }

    @Test
    void offlineReasonIsExposedSeparatelyFromTheShortStatus() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(new ConnectionEvent(contact, ConnectionState.UNREACHABLE, null, null, Instant.now()));

        assertEquals(java.util.Optional.of("could not reach the other device"), presenter.offlineReasonFor(contact, false));
        assertEquals("Offline (could not reach the other device)", presenter.displayTextFor(contact, false),
                "the existing combined text is unchanged");
        assertEquals(java.util.Optional.empty(), presenter.offlineReasonFor(contact, true), "ground truth: connected means nothing to explain");
        assertEquals(java.util.Optional.empty(), presenter.offlineReasonFor(randomIdentityId(), false));
    }

    @Test
    void connectingStateComesFromTheLatestEventAndNeverOverridesAnActiveConnection() {
        PeerConnectionPresenter presenter = new PeerConnectionPresenter();
        IdentityId contact = randomIdentityId();
        presenter.onEvent(ConnectionEventFixtures.of(contact, ConnectionState.AUTHENTICATING));

        org.junit.jupiter.api.Assertions.assertTrue(presenter.isConnectingFor(contact, false));
        org.junit.jupiter.api.Assertions.assertFalse(presenter.isConnectingFor(contact, true));
        org.junit.jupiter.api.Assertions.assertFalse(presenter.isConnectingFor(randomIdentityId(), false));
    }

    @Test
    void failureReasonsAreDescribedInPlainLanguageNeverByEnumName() {
        for (ConnectionFailureReason reason : ConnectionFailureReason.values()) {
            String text = PeerConnectionPresenter.describeFailure(reason);
            org.junit.jupiter.api.Assertions.assertFalse(text.isBlank());
            org.junit.jupiter.api.Assertions.assertFalse(text.matches(".*[A-Z]{3,}_[A-Z_]+.*"), reason + " leaked as: " + text);
        }
        org.junit.jupiter.api.Assertions.assertTrue(PeerConnectionPresenter.describeFailure(ConnectionFailureReason.CONNECTION_REFUSED).contains("couldn't be reached"));
        org.junit.jupiter.api.Assertions.assertTrue(PeerConnectionPresenter.describeFailure(null).length() > 0);
    }
}

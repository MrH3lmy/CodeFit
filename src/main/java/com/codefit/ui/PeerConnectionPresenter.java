package com.codefit.ui;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.transport.ConnectionEvent;
import com.codefit.peer.transport.ConnectionFailureReason;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Presentation-only cache of the latest transport-level {@link ConnectionEvent} per contact identity.
 * This is deliberately <strong>not</strong> a second connection-state machine: #182's
 * {@code PeerNetworkService} (observed here only through the {@code ConnectionEventListener}
 * {@code NetworkingService} can now be constructed with) is the sole authority on connection state.
 * This class only remembers the most recent event it was told about, for exactly one purpose - adding
 * a useful reason when the ground truth says "not connected". {@link #displayTextFor} always takes
 * that ground truth as a required parameter (read fresh from
 * {@code NetworkingService.activeConnection(contactId)} by the caller) and lets it win outright over
 * whatever is cached here; nothing in this class is ever consulted <em>instead of</em> it.
 *
 * <p>Events can arrive out of order - they are fired from different background threads (the dial pool,
 * the listener's own accept/handshake pool) with no ordering guarantee between them. A late, stale
 * event must never overwrite a newer one's detail, so each identity's cached event is replaced only by
 * a same-or-later one, compared by {@link ConnectionEvent#at()}. Pure bookkeeping and string mapping
 * only - no JavaFX import anywhere in this file, so it is fully unit-testable without the toolkit.
 */
public final class PeerConnectionPresenter {
    private final Map<IdentityId, ConnectionEvent> lastEventByContact = new ConcurrentHashMap<>();
    private volatile Runnable onChange;

    /**
     * Records one transport connection event, then - if a controller has subscribed via
     * {@link #setOnChange} - tells it something changed. Safe to call from any thread (the dial
     * pool, the listener's accept thread, a handshake worker) - this method itself touches no UI
     * control. The {@code onChange} callback runs on that same background thread too: it carries no
     * event data of its own (the controller re-reads this presenter, and the real
     * {@code NetworkingService.activeConnection} ground truth, when it actually repaints) and its own
     * job is only to get back onto the JavaFX thread (via {@code Platform.runLater}) before touching
     * any {@code Node} - this class never does that itself. A {@code null} {@code remoteIdentityId}
     * (an inbound socket still mid-handshake, before authentication has learned who it is) is not
     * attributable to any contact and is ignored.
     */
    public void onEvent(ConnectionEvent event) {
        if (event.remoteIdentityId() == null) {
            return;
        }
        lastEventByContact.merge(event.remoteIdentityId(), event,
                (existing, incoming) -> incoming.at().isBefore(existing.at()) ? existing : incoming);
        Runnable listener = onChange;
        if (listener != null) {
            listener.run();
        }
    }

    /**
     * Subscribes (or, with {@code null}, unsubscribes) to a "something changed" notification after
     * every recorded event. At most one subscriber slot exists here - exactly one Peer screen can be
     * open. {@code PeerController} sets this in its own {@code initialize()}, but ordinary sidebar
     * navigation away from the Peers screen does <strong>not</strong> clear it: {@code
     * NavigationService}/{@code AppShellController} have no per-route "leaving this screen" lifecycle
     * hook today (unlike, say, {@code ProblemSolvingWorkspaceController}'s {@code canNavigateAway},
     * which only fires for navigation routed through {@code BaseController#navigate}, a path {@code
     * PeerController} does not use), and adding one is out of scope here. In practice this is a
     * harmless, bounded replacement rather than a leak: the very next time the Peers screen is shown,
     * a freshly-constructed {@code PeerController} calls {@code initialize()} again, which overwrites
     * this single slot with its own callback - so at most one navigated-away controller's callback can
     * ever be stale, and only until that next visit. A stale callback firing in that window just
     * re-reads this presenter and the real connection ground truth and repaints a scene graph that is
     * no longer attached to the stage - wasted work, not a correctness or memory-safety problem (the
     * stale controller itself is still eligible for GC once this slot is overwritten).
     */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    public Optional<ConnectionEvent> lastEventFor(IdentityId contactId) {
        return Optional.ofNullable(lastEventByContact.get(contactId));
    }

    /** Discards everything remembered - for a clean slate on networking disable/shutdown. */
    public void clear() {
        lastEventByContact.clear();
        onChange = null;
    }

    /**
     * The short status string to show for one contact.
     *
     * @param activeConnectionPresent the ground truth, read fresh from
     *                                {@code NetworkingService.activeConnection(contactId)} by the
     *                                caller - always wins over any cached event: a stale "Rejected"
     *                                left over from, say, an earlier failed attempt must never be
     *                                shown once a connection has actually since succeeded.
     */
    public String displayTextFor(IdentityId contactId, boolean activeConnectionPresent) {
        if (activeConnectionPresent) {
            return "Connected";
        }
        return lastEventFor(contactId).map(PeerConnectionPresenter::describe).orElse("Offline");
    }

    /**
     * Whether the most recent recorded event says an attempt is underway. Like {@link #displayTextFor},
     * the caller's own {@code activeConnectionPresent} ground truth always wins over it.
     */
    public boolean isConnectingFor(IdentityId contactId, boolean activeConnectionPresent) {
        return !activeConnectionPresent && lastEventFor(contactId)
                .map(event -> switch (event.state()) {
                    case QUEUED, CONNECTING, AUTHENTICATING, RETRY_WAITING -> true;
                    default -> false;
                }).orElse(false);
    }

    /**
     * Why a contact that is not connected is offline, when the most recent event says so - exactly the
     * parenthetical {@link #displayTextFor} appends, as its own value so the UI can show the status
     * ("Offline") and the reason separately. Empty when connected or when there is nothing to explain.
     */
    public Optional<String> offlineReasonFor(IdentityId contactId, boolean activeConnectionPresent) {
        if (activeConnectionPresent) {
            return Optional.empty();
        }
        return lastEventFor(contactId).flatMap(PeerConnectionPresenter::reasonOf);
    }

    private static String describe(ConnectionEvent event) {
        return switch (event.state()) {
            case CONNECTED -> "Connected";
            case QUEUED, CONNECTING, AUTHENTICATING, RETRY_WAITING -> "Connecting…";
            case CLOSED, CANCELLED -> "Offline";
            case UNREACHABLE, INCOMPATIBLE_VERSION, REJECTED -> "Offline (" + reasonOf(event).orElseThrow() + ")";
        };
    }

    private static Optional<String> reasonOf(ConnectionEvent event) {
        return switch (event.state()) {
            case UNREACHABLE -> Optional.of("could not reach the other device");
            case INCOMPATIBLE_VERSION -> Optional.of("incompatible protocol version");
            case REJECTED -> Optional.of(describeRejection(event.reason()));
            default -> Optional.empty();
        };
    }

    /**
     * Plain-language reason for a failed connection attempt's own outcome (as opposed to the cached
     * event above) - never the enum constant's name.
     */
    public static String describeFailure(ConnectionFailureReason reason) {
        if (reason == null) {
            return "the connection was refused";
        }
        return switch (reason) {
            case CONNECT_TIMEOUT, CONNECTION_REFUSED, NETWORK_UNREACHABLE, HANDSHAKE_TIMEOUT ->
                    "the other device couldn't be reached. Check that it's online and on the same network";
            case UNKNOWN_IDENTITY, NOT_PAIRED, WRONG_PIN, STALE_BINDING, BINDING_KEY_MISMATCH, IDENTITY_MISMATCH,
                 BINDING_NOT_YET_VALID, BINDING_EXPIRED, ROLLOVER_REFUSED, CONNECTION_LIMIT_REACHED ->
                    describeRejection(reason);
            case UNSUPPORTED_VERSION -> "their CodeFit version is incompatible";
            case NETWORKING_DISABLED, LISTENER_NOT_RUNNING -> "networking is turned off";
            case NO_KNOWN_ADDRESS -> "there is no known address for them yet";
            case CANCELLED -> "the attempt was cancelled";
            default -> "the connection was refused";
        };
    }

    /**
     * Distinguishes the one rejection reason this PR's symmetric-pairing requirement calls out
     * explicitly: a connection failing only because the other device has not yet imported and
     * accepted <em>this</em> device's own invitation. Without this, that failure would otherwise
     * surface as an unexplained, generic rejection.
     */
    private static String describeRejection(ConnectionFailureReason reason) {
        if (reason == null) {
            return "rejected";
        }
        return switch (reason) {
            case UNKNOWN_IDENTITY -> "the other device has not imported your invitation yet";
            case NOT_PAIRED -> "the other device has not accepted your invitation yet (or has blocked/removed you)";
            case WRONG_PIN, STALE_BINDING, BINDING_KEY_MISMATCH -> "unexpected identity/key - re-check the invitation";
            case IDENTITY_MISMATCH -> "identity mismatch";
            case BINDING_NOT_YET_VALID -> "the other device's key is not valid yet";
            case BINDING_EXPIRED -> "the other device's key has expired";
            case ROLLOVER_REFUSED -> "key rollover was refused";
            case CONNECTION_LIMIT_REACHED -> "the other device is at its connection limit";
            default -> "rejected";
        };
    }
}

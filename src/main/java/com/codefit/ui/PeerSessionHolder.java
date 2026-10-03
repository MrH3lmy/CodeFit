package com.codefit.ui;

import com.codefit.service.NetworkingService;

/**
 * The single shared owner of this application's one {@link NetworkingService} instance, for exactly
 * one reason: {@link com.codefit.controller.PeerController} is recreated on every navigation (the
 * same shell/content-host swap every other screen goes through), but the running listener socket,
 * live transport key, and open peer connections {@code NetworkingService} owns must survive that -
 * they are application-lifetime state, not screen-lifetime state. This is the same "one shared owner,
 * closed from {@code CodeFitApplication#stop()}, and nowhere else" shape
 * {@code com.codefit.service.CompileOutcomeRegistry}/{@code BackgroundImportExecutor} already use for
 * the identical problem elsewhere in this codebase - not a new mechanism.
 *
 * <p>This is deliberately narrow: it owns exactly the one {@code NetworkingService}, the one
 * {@link PeerConnectionPresenter}, and the one {@link PeerDialGate} the Peer screen needs across
 * recreation, nothing else. It is not a service locator - no other service is reachable through it,
 * and nothing here decides application behavior; {@code PeerController} still does all the
 * orchestration.
 */
public final class PeerSessionHolder {

    private static final Object LOCK = new Object();
    private static NetworkingService networkingService;
    private static PeerConnectionPresenter presenter;
    private static PeerDialGate dialGate;

    private PeerSessionHolder() {
    }

    /** The one application-lifetime {@link NetworkingService}, created on first use. */
    public static NetworkingService networkingService() {
        synchronized (LOCK) {
            if (networkingService == null) {
                networkingService = new NetworkingService(presenter()::onEvent);
            }
            return networkingService;
        }
    }

    /** The one application-lifetime {@link PeerConnectionPresenter}, created on first use. */
    public static PeerConnectionPresenter presenter() {
        synchronized (LOCK) {
            if (presenter == null) {
                presenter = new PeerConnectionPresenter();
            }
            return presenter;
        }
    }

    /** The one application-lifetime {@link PeerDialGate}, created on first use. Lives here, not as a
     *  plain {@code PeerController} field, for the same reason {@code networkingService} does: a
     *  manual dial started by one controller instance must still be recognized as in flight by
     *  whatever controller instance is current if the Peer screen is left and reopened before that
     *  dial finishes. */
    public static PeerDialGate dialGate() {
        synchronized (LOCK) {
            if (dialGate == null) {
                dialGate = new PeerDialGate();
            }
            return dialGate;
        }
    }

    /** Closes the networking service (tearing down the listener, dial pool, and every open
     *  connection), forgets every UI-owned in-flight dial, and forgets all three held objects.
     *  Called from {@code CodeFitApplication#stop()} on normal application exit; also safe to call
     *  from a test teardown. Safe to call repeatedly. */
    public static void shutdown() {
        synchronized (LOCK) {
            if (networkingService != null) {
                networkingService.close();
                networkingService = null;
            }
            if (presenter != null) {
                presenter.clear();
                presenter = null;
            }
            if (dialGate != null) {
                dialGate.clear();
                dialGate = null;
            }
        }
    }
}

package com.codefit.ui;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, per contact id, whether this UI currently owns an unfinished manual "Connect" request -
 * nothing more. This is deliberately not a connection-state machine and knows nothing about
 * {@code ConnectionState}/authentication/transport: {@code NetworkingService.activeConnection} stays
 * the sole authority on whether a contact is actually connected. This class exists only to answer one
 * question - "has {@code PeerController} already started a manual dial for this contact that hasn't
 * finished yet" - so a second click cannot start a second, independent dial attempt racing the first.
 *
 * <p>{@link #tryAcquire} is the only thing that may ever create ownership, and it is atomic
 * (backed by {@code ConcurrentHashMap}'s own key-set, whose {@code add} is a single atomic
 * operation): if two callers race to acquire the same contact id, exactly one gets {@code true}, the
 * other {@code false} - a "add" racing another "add" for the same element can never both win, so this
 * holds even under concurrent calls, not only sequential ones on the JavaFX thread.
 */
public final class PeerDialGate {
    private final Set<Long> inFlightContactIds = ConcurrentHashMap.newKeySet();

    /** @return {@code true} if this call acquired ownership (no dial was already in flight for this
     *          contact); {@code false} if one already was, in which case the caller must not start another. */
    public boolean tryAcquire(long contactId) {
        return inFlightContactIds.add(contactId);
    }

    /** Releases ownership. Safe to call even if nothing was held (e.g. after Disable Networking already cleared it). */
    public void release(long contactId) {
        inFlightContactIds.remove(contactId);
    }

    public boolean isInFlight(long contactId) {
        return inFlightContactIds.contains(contactId);
    }

    /** Forgets every held contact id - used when networking is disabled or the application exits, so
     *  no UI-owned dial state is ever left hanging past either of those. */
    public void clear() {
        inFlightContactIds.clear();
    }
}

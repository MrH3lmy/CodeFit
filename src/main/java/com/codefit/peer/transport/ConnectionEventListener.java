package com.codefit.peer.transport;

/** Receives connection lifecycle events. Called on a networking thread, never the JavaFX thread. */
@FunctionalInterface
public interface ConnectionEventListener {
    void onConnectionEvent(ConnectionEvent event);
}

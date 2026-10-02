package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityId;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One fully authenticated connection to a specific paired contact: the live socket, plus enough
 * bookkeeping to close it exactly once and report when it was established. #183/#184 will use this to
 * exchange {@code PROGRESS_SUMMARY}/{@code PREPARATION_SNAPSHOT}/{@code CONSENT_REVISION} frames; #182
 * itself only needs to establish, hold, and cleanly tear down the authenticated channel.
 */
public final class PeerConnection implements AutoCloseable {
    private final SSLSocket socket;
    private final IdentityId remoteIdentityId;
    private final Instant connectedAt;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Runnable onClose;

    PeerConnection(SSLSocket socket, IdentityId remoteIdentityId, Instant connectedAt) {
        this(socket, remoteIdentityId, connectedAt, () -> { });
    }

    PeerConnection(SSLSocket socket, IdentityId remoteIdentityId, Instant connectedAt, Runnable onClose) {
        this.socket = socket;
        this.remoteIdentityId = remoteIdentityId;
        this.connectedAt = connectedAt;
        this.onClose = onClose;
    }

    public IdentityId remoteIdentityId() {
        return remoteIdentityId;
    }

    /** The peer's socket address as seen on this connection (the interface address it actually came through or went to). */
    public java.net.SocketAddress remoteAddress() {
        return socket.getRemoteSocketAddress();
    }

    public Instant connectedAt() {
        return connectedAt;
    }

    public boolean isOpen() {
        return !closed.get() && !socket.isClosed();
    }

    /** Sends one bounded protocol v1 frame over this connection's TLS stream. */
    public void send(com.codefit.peer.protocol.SignedEnvelope envelope) throws IOException {
        if (!isOpen()) {
            throw new IOException("Connection to " + remoteIdentityId + " is closed.");
        }
        HandshakeIo.writeEnvelopeFrame(socket.getOutputStream(), envelope);
    }

    /** Blocks for the next bounded protocol v1 frame from the peer. */
    public com.codefit.peer.protocol.SignedEnvelope receive() throws IOException {
        return HandshakeIo.readEnvelopeFrame(socket.getInputStream());
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort: the socket is being discarded either way.
            } finally {
                onClose.run();
            }
        }
    }
}

package com.codefit.peer.transport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The inbound side of #182's transport: an off-by-default TCP+TLS listener. Every thread it starts is a
 * daemon thread (ADR-0001 §5: "blocking sockets on daemon threads, closed from Application.stop()"), and
 * {@link #close()} tears every one of them down deterministically — the accept loop thread, the bounded
 * handshake pool, and the listening socket itself — so nothing survives past disable/exit.
 *
 * <p>Bounded by design: a fixed-size handshake thread pool with a bounded queue means a burst of
 * connection attempts either gets a handshake slot or is turned away immediately
 * ({@link ConnectionFailureReason#CONNECTION_LIMIT_REACHED}), and a {@link Semaphore} separately caps how
 * many <em>authenticated</em> connections may be held open at once, independent of how many handshake
 * attempts are in flight.
 */
final class PeerListener implements AutoCloseable {
    private static final int HANDSHAKE_SOCKET_TIMEOUT_MILLIS = 15_000;

    private final SSLServerSocket serverSocket;
    private final PeerSession.Context context;
    private final ThreadPoolExecutor handshakeExecutor;
    private final Semaphore authenticatedConnectionSlots;
    private final Thread acceptThread;
    private final Consumer<PeerConnection> onAuthenticated;
    private final ConnectionEventListener onEvent;
    private volatile boolean closing;

    PeerListener(TransportIdentity localTransport, PeerSession.Context context, int port, int backlog,
                 int maxConcurrentHandshakes, int maxQueuedHandshakes, int maxAuthenticatedConnections,
                 Consumer<PeerConnection> onAuthenticated, ConnectionEventListener onEvent) throws IOException {
        this.context = context;
        this.onAuthenticated = onAuthenticated;
        this.onEvent = onEvent;
        SSLContext tls = TlsContexts.forListener(localTransport);
        this.serverSocket = (SSLServerSocket) tls.getServerSocketFactory()
                .createServerSocket(port, backlog, InetAddress.getLoopbackAddress());
        TlsContexts.hardenServerSocket(serverSocket);

        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory daemonFactory = runnable -> {
            Thread thread = new Thread(runnable, "codefit-peer-handshake-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.handshakeExecutor = new ThreadPoolExecutor(maxConcurrentHandshakes, maxConcurrentHandshakes,
                60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(maxQueuedHandshakes), daemonFactory,
                new ThreadPoolExecutor.AbortPolicy());
        this.authenticatedConnectionSlots = new Semaphore(maxAuthenticatedConnections);

        this.acceptThread = new Thread(this::acceptLoop, "codefit-peer-listener-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int localPort() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closing) {
            SSLSocket accepted;
            try {
                accepted = (SSLSocket) serverSocket.accept();
            } catch (IOException e) {
                if (closing) {
                    return;
                }
                continue;
            }
            try {
                handshakeExecutor.execute(() -> handle(accepted));
            } catch (RejectedExecutionException fullQueue) {
                closeQuietly(accepted);
                fireEvent(null, ConnectionFailureReason.CONNECTION_LIMIT_REACHED, "Handshake queue is full.");
            }
        }
    }

    private void handle(SSLSocket socket) {
        // The connection-count bound is enforced by reserving a slot BEFORE the handshake even starts,
        // not after: PeerSession.accept() already writes and sends the local IDENTITY_BINDING reply as
        // soon as the caller authenticates, so checking capacity only afterward would let the caller
        // believe it is connected (it already received a valid signed reply) even though the listener
        // was about to refuse it — a real race a slow capacity check would lose.
        if (!authenticatedConnectionSlots.tryAcquire()) {
            closeQuietly(socket);
            fireEvent(null, ConnectionFailureReason.CONNECTION_LIMIT_REACHED, "Maximum authenticated connections reached.");
            return;
        }
        boolean handedOff = false;
        try {
            socket.setSoTimeout(HANDSHAKE_SOCKET_TIMEOUT_MILLIS);
            ConnectionOutcome result = PeerSession.accept(socket, context, Instant.now());
            if (!result.authenticated()) {
                closeQuietly(socket);
                fireEvent(result.remoteIdentityId(), result.failureReason(), result.detail());
                return;
            }
            socket.setSoTimeout(0);
            PeerConnection connection = new PeerConnection(socket, result.remoteIdentityId(), Instant.now(),
                    authenticatedConnectionSlots::release);
            handedOff = true;
            fireConnected(result.remoteIdentityId());
            onAuthenticated.accept(connection);
        } catch (TransportProtocolException e) {
            closeQuietly(socket);
            fireEvent(null, e.reason(), e.getMessage());
        } catch (IOException e) {
            closeQuietly(socket);
            fireEvent(null, ConnectionFailureReason.TLS_HANDSHAKE_FAILED, e.getMessage());
        } finally {
            if (!handedOff) {
                authenticatedConnectionSlots.release();
            }
        }
    }

    private void fireConnected(com.codefit.peer.protocol.IdentityId remote) {
        if (onEvent != null) {
            onEvent.onConnectionEvent(ConnectionEvent.of(remote, ConnectionState.CONNECTED));
        }
    }

    private void fireEvent(com.codefit.peer.protocol.IdentityId remote, ConnectionFailureReason reason, String detail) {
        if (onEvent != null) {
            ConnectionState state = reason == ConnectionFailureReason.UNSUPPORTED_VERSION
                    ? ConnectionState.INCOMPATIBLE_VERSION : ConnectionState.REJECTED;
            onEvent.onConnectionEvent(ConnectionEvent.failed(remote, state, reason, detail));
        }
    }

    private static void closeQuietly(SSLSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // discarding regardless
        }
    }

    @Override
    public void close() {
        closing = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // already shutting down
        }
        try {
            acceptThread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        handshakeExecutor.shutdownNow();
        try {
            handshakeExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.codefit.peer.transport;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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
 *
 * <p><strong>Where it listens</strong> is an explicit {@link ListenerBindAddress}, never an accident of a
 * default: the wildcard (every interface) for real LAN peers, one specific interface address, or
 * loopback-only for same-machine use. Being reachable is not being trusted - every inbound socket still
 * has to complete mutual TLS and the identity-signed {@code IDENTITY_BINDING} proof before it is anything
 * but a bounded, short-lived pre-authentication handshake.
 *
 * <p><strong>Key rollover.</strong> The server socket is a plain TCP socket that never changes; TLS is
 * layered on each accepted connection from the <em>current</em> {@link Generation} (the key pair, its
 * certificate and the matching {@link PeerSession.Context}, all snapshotted together at accept time). So
 * {@link #rekey} swaps the key presented to every <em>subsequent</em> connection atomically, on the same
 * port, with no window in which the certificate and the {@code IDENTITY_BINDING} sent on that socket
 * could disagree.
 */
final class PeerListener implements AutoCloseable {
    private static final int HANDSHAKE_SOCKET_TIMEOUT_MILLIS = 15_000;

    /** Everything that must change together when the local transport key changes. */
    private record Generation(SSLContext tls, PeerSession.Context context) {
    }

    private final ServerSocket serverSocket;
    private volatile Generation generation;
    private final ThreadPoolExecutor handshakeExecutor;
    private final Semaphore authenticatedConnectionSlots;
    private final Thread acceptThread;
    private final Consumer<PeerConnection> onAuthenticated;
    private final ConnectionEventListener onEvent;
    private final VerifiedBindingListener onVerifiedBinding;
    private volatile boolean closing;

    /** Test convenience: loopback-only, no verified-binding listener. Production code always passes a bind address. */
    PeerListener(TransportIdentity localTransport, PeerSession.Context context, int port, int backlog,
                 int maxConcurrentHandshakes, int maxQueuedHandshakes, int maxAuthenticatedConnections,
                 Consumer<PeerConnection> onAuthenticated, ConnectionEventListener onEvent) throws IOException {
        this(localTransport, context, ListenerBindAddress.loopbackOnly(), port, backlog, maxConcurrentHandshakes,
                maxQueuedHandshakes, maxAuthenticatedConnections, onAuthenticated, onEvent, (id, binding) -> { });
    }

    PeerListener(TransportIdentity localTransport, PeerSession.Context context, ListenerBindAddress bindAddress,
                 int port, int backlog, int maxConcurrentHandshakes, int maxQueuedHandshakes,
                 int maxAuthenticatedConnections, Consumer<PeerConnection> onAuthenticated,
                 ConnectionEventListener onEvent, VerifiedBindingListener onVerifiedBinding) throws IOException {
        this.generation = new Generation(TlsContexts.forListener(localTransport), context);
        this.onAuthenticated = onAuthenticated;
        this.onEvent = onEvent;
        this.onVerifiedBinding = onVerifiedBinding;
        this.serverSocket = new ServerSocket();
        try {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(bindAddress.toSocketAddress(port), backlog);
        } catch (IOException | RuntimeException e) {
            serverSocket.close();
            throw e;
        }

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

    /**
     * Atomically switches the key presented to every connection accepted from now on. Handshakes already
     * in flight finish under the generation they started with, and already-authenticated connections are
     * untouched. The listening port and socket do not change.
     */
    void rekey(TransportIdentity newTransport, PeerSession.Context newContext) {
        this.generation = new Generation(TlsContexts.forListener(newTransport), newContext);
    }

    private void acceptLoop() {
        while (!closing) {
            Socket accepted;
            try {
                accepted = serverSocket.accept();
            } catch (IOException e) {
                if (closing) {
                    return;
                }
                continue;
            }
            Generation snapshot = generation;
            try {
                handshakeExecutor.execute(() -> handle(accepted, snapshot));
            } catch (RejectedExecutionException fullQueue) {
                closeQuietly(accepted);
                fireEvent(null, ConnectionFailureReason.CONNECTION_LIMIT_REACHED, "Handshake queue is full.");
            }
        }
    }

    private void handle(Socket raw, Generation snapshot) {
        // The connection-count bound is enforced by reserving a slot BEFORE the handshake even starts,
        // not after: PeerSession.accept() already writes and sends the local IDENTITY_BINDING reply as
        // soon as the caller authenticates, so checking capacity only afterward would let the caller
        // believe it is connected (it already received a valid signed reply) even though the listener
        // was about to refuse it - a real race a slow capacity check would lose.
        if (!authenticatedConnectionSlots.tryAcquire()) {
            closeQuietly(raw);
            fireEvent(null, ConnectionFailureReason.CONNECTION_LIMIT_REACHED, "Maximum authenticated connections reached.");
            return;
        }
        boolean handedOff = false;
        SSLSocket socket = null;
        try {
            socket = wrapAsTlsServer(raw, snapshot.tls());
            socket.setSoTimeout(HANDSHAKE_SOCKET_TIMEOUT_MILLIS);
            ConnectionOutcome result = PeerSession.accept(socket, snapshot.context(), Instant.now());
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
            try {
                onVerifiedBinding.onVerifiedBinding(result.remoteIdentityId(), result.remoteBinding());
            } catch (RuntimeException persistenceFailure) {
                fireEvent(result.remoteIdentityId(), ConnectionFailureReason.BINDING_NOT_PERSISTED,
                        String.valueOf(persistenceFailure.getMessage()));
            }
            onAuthenticated.accept(connection);
        } catch (TransportProtocolException e) {
            closeQuietly(socket != null ? socket : raw);
            fireEvent(null, e.reason(), e.getMessage());
        } catch (IOException e) {
            closeQuietly(socket != null ? socket : raw);
            fireEvent(null, ConnectionFailureReason.TLS_HANDSHAKE_FAILED, e.getMessage());
        } finally {
            if (!handedOff) {
                authenticatedConnectionSlots.release();
            }
        }
    }

    /** Layers TLS 1.3 (client auth required) over an already-accepted TCP socket, using one key generation's context. */
    private static SSLSocket wrapAsTlsServer(Socket raw, SSLContext tls) throws IOException {
        SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket(raw, null, raw.getPort(), true);
        socket.setUseClientMode(false);
        TlsContexts.hardenSocket(socket, true);
        return socket;
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

    private static void closeQuietly(Socket socket) {
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

package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single public entry point into #182's transport (every other class in this package is an
 * implementation detail). <strong>Off by default</strong>: nothing here opens a socket until
 * {@link #enable} is called, and {@link #disable}/{@link #close} tear down every socket and thread this
 * instance ever started — none of it survives past disable or application exit (ADR-0001 §5, #182:
 * "networking disabled by default, off the JavaFX thread, cancellable, and fully stopped on disable/exit").
 * All I/O here runs on daemon threads this class owns, never the caller's thread and never the JavaFX
 * application thread.
 */
public final class PeerNetworkService implements AutoCloseable {
    private static final int MAX_CONCURRENT_HANDSHAKES = 8;
    private static final int MAX_QUEUED_HANDSHAKES = 16;
    private static final int MAX_AUTHENTICATED_INBOUND_CONNECTIONS = 32;
    private static final int MAX_CONCURRENT_OUTBOUND_DIALS = 4;
    private static final int LISTENER_BACKLOG = 50;

    private final KnownContactLookup contactLookup;
    private final ConnectionEventListener eventListener;
    private final LocalBindingEnvelopeCache bindingCache = new LocalBindingEnvelopeCache();
    private final PeerSession.ReplayStates replayStates = new PeerSession.ReplayStates();
    private final Map<IdentityId, PeerConnection> connections = new ConcurrentHashMap<>();
    private final Semaphore outboundSlots = new Semaphore(MAX_CONCURRENT_OUTBOUND_DIALS);
    private final AtomicInteger dialThreadCounter = new AtomicInteger();

    private volatile PeerListener listener;
    private volatile ExecutorService dialExecutor;
    private volatile UnlockedIdentity localIdentity;
    private volatile TransportIdentity localTransport;
    private volatile long writerEpoch;

    public PeerNetworkService(KnownContactLookup contactLookup, ConnectionEventListener eventListener) {
        this.contactLookup = contactLookup;
        this.eventListener = eventListener;
    }

    public synchronized boolean isEnabled() {
        return listener != null;
    }

    public synchronized Optional<Integer> listeningPort() {
        return listener == null ? Optional.empty() : Optional.of(listener.localPort());
    }

    public synchronized Optional<IdentityBinding> currentBinding() {
        return localTransport == null ? Optional.empty() : Optional.of(localTransport.toBinding());
    }

    public synchronized Optional<IdentityKey> currentTransportPublicKey() {
        return localTransport == null ? Optional.empty() : Optional.of(localTransport.publicKey());
    }

    /** Idempotent: calling this while already enabled with the same material is a harmless no-op. */
    public synchronized void enable(UnlockedIdentity identity, TransportKeyMaterial transportMaterial, long writerEpoch,
                                     int listenPort) throws IOException {
        if (listener != null) {
            return;
        }
        this.localIdentity = identity;
        this.localTransport = TransportIdentity.from(transportMaterial);
        this.writerEpoch = writerEpoch;
        PeerSession.Context context = new PeerSession.Context(localIdentity, localTransport, writerEpoch,
                bindingCache, replayStates, contactLookup);
        this.listener = new PeerListener(localTransport, context, listenPort, LISTENER_BACKLOG,
                MAX_CONCURRENT_HANDSHAKES, MAX_QUEUED_HANDSHAKES, MAX_AUTHENTICATED_INBOUND_CONNECTIONS,
                this::trackInboundConnection, eventListener);
        ThreadFactory daemonFactory = runnable -> {
            Thread thread = new Thread(runnable, "codefit-peer-dial-" + dialThreadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.dialExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_OUTBOUND_DIALS, daemonFactory);
    }

    /** Stops the listener, cancels dialing, closes every open connection, and forgets key material. Safe to call repeatedly. */
    public synchronized void disable() {
        if (listener != null) {
            listener.close();
            listener = null;
        }
        if (dialExecutor != null) {
            dialExecutor.shutdownNow();
            try {
                dialExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dialExecutor = null;
        }
        for (PeerConnection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
        localIdentity = null;
        localTransport = null;
    }

    @Override
    public void close() {
        disable();
    }

    public Optional<PeerConnection> activeConnection(IdentityId contactId) {
        return Optional.ofNullable(connections.get(contactId));
    }

    /**
     * Dials {@code address} for {@code contactId}, pinned to {@code expectedTransportKey}, retrying with
     * {@code policy} until it authenticates, exhausts its attempt cap, hits a non-retryable rejection, or
     * {@code cancelToken} is flipped. Runs entirely on this service's own daemon dial pool — never the
     * caller's thread. The returned future completes exceptionally only for programmer errors (networking
     * disabled, no free dial slot); an ordinary network/protocol failure is reported through
     * {@link DialOutcome#result()} on the completed future's value instead.
     */
    public CompletableFuture<DialOutcome> connect(IdentityId contactId, PeerAddress address,
                                                              IdentityKey expectedTransportKey, RetryPolicy policy,
                                                              AtomicBoolean cancelToken) {
        ExecutorService executor;
        UnlockedIdentity identity;
        TransportIdentity transport;
        long epoch;
        synchronized (this) {
            if (listener == null || dialExecutor == null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Networking is disabled."));
            }
            executor = dialExecutor;
            identity = localIdentity;
            transport = localTransport;
            epoch = writerEpoch;
        }
        if (!outboundSlots.tryAcquire()) {
            DialOutcome limitReached = new DialOutcome(
                    ConnectionOutcome.fail(ConnectionFailureReason.CONNECTION_LIMIT_REACHED, "No free outbound dial slot."), null);
            return CompletableFuture.completedFuture(limitReached);
        }
        PeerSession.Context context = new PeerSession.Context(identity, transport, epoch, bindingCache, replayStates, contactLookup);
        return CompletableFuture.supplyAsync(
                () -> PeerDialer.dialWithRetry(transport, context, address, contactId, expectedTransportKey, policy, cancelToken, e -> notifyEvent(e)),
                executor
        ).whenComplete((outcome, throwable) -> {
            outboundSlots.release();
            if (throwable == null && outcome.connection() != null) {
                trackOutboundConnection(contactId, outcome.connection());
            }
        });
    }

    /** Closes and forgets a tracked connection, if one is open for this contact. */
    public void disconnect(IdentityId contactId) {
        PeerConnection connection = connections.remove(contactId);
        if (connection != null) {
            connection.close();
        }
    }

    private void trackInboundConnection(PeerConnection connection) {
        connections.merge(connection.remoteIdentityId(), connection, (oldConn, newConn) -> {
            oldConn.close();
            return newConn;
        });
    }

    private void trackOutboundConnection(IdentityId contactId, PeerConnection connection) {
        connections.merge(contactId, connection, (oldConn, newConn) -> {
            oldConn.close();
            return newConn;
        });
    }

    private void notifyEvent(ConnectionEvent event) {
        if (eventListener != null) {
            eventListener.onConnectionEvent(event);
        }
    }
}

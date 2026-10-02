package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

import java.io.IOException;
import java.time.Instant;
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
    private final VerifiedBindingListener bindingListener;
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
    private volatile ListenerBindAddress bindAddress;

    public PeerNetworkService(KnownContactLookup contactLookup, ConnectionEventListener eventListener) {
        this(contactLookup, eventListener, (identity, binding) -> { });
    }

    /**
     * @param bindingListener told the verified remote {@code IDENTITY_BINDING} of every connection that
     *                        fully authenticated, inbound or outbound, before the connection is handed out
     */
    public PeerNetworkService(KnownContactLookup contactLookup, ConnectionEventListener eventListener,
                              VerifiedBindingListener bindingListener) {
        this.contactLookup = contactLookup;
        this.eventListener = eventListener;
        this.bindingListener = bindingListener;
    }

    public synchronized boolean isEnabled() {
        return listener != null;
    }

    public synchronized Optional<Integer> listeningPort() {
        return listener == null ? Optional.empty() : Optional.of(listener.localPort());
    }

    /** Where the listener is bound, or empty while disabled. */
    public synchronized Optional<ListenerBindAddress> boundAddress() {
        return listener == null ? Optional.empty() : Optional.of(bindAddress);
    }

    public synchronized Optional<IdentityBinding> currentBinding() {
        return localTransport == null ? Optional.empty() : Optional.of(localTransport.toBinding());
    }

    public synchronized Optional<IdentityKey> currentTransportPublicKey() {
        return localTransport == null ? Optional.empty() : Optional.of(localTransport.publicKey());
    }

    /** Enables on every local interface ({@link ListenerBindAddress#wildcard()}). */
    public synchronized void enable(UnlockedIdentity identity, TransportKeyMaterial transportMaterial, long writerEpoch,
                                     int listenPort) throws IOException {
        enable(identity, transportMaterial, writerEpoch, listenPort, ListenerBindAddress.wildcard());
    }

    /**
     * Idempotent: calling this while already enabled is a harmless no-op (it neither rebinds nor swaps the
     * key; use {@link #rekey} for the latter).
     */
    public synchronized void enable(UnlockedIdentity identity, TransportKeyMaterial transportMaterial, long writerEpoch,
                                     int listenPort, ListenerBindAddress bind) throws IOException {
        if (listener != null) {
            return;
        }
        this.localIdentity = identity;
        this.localTransport = TransportIdentity.from(transportMaterial);
        this.writerEpoch = writerEpoch;
        this.bindAddress = bind;
        PeerSession.Context context = new PeerSession.Context(localIdentity, localTransport, writerEpoch,
                bindingCache, replayStates, contactLookup);
        try {
            this.listener = new PeerListener(localTransport, context, bind, listenPort, LISTENER_BACKLOG,
                    MAX_CONCURRENT_HANDSHAKES, MAX_QUEUED_HANDSHAKES, MAX_AUTHENTICATED_INBOUND_CONNECTIONS,
                    this::trackInboundConnection, eventListener, bindingListener);
        } catch (IOException | RuntimeException bindFailed) {
            // Not enabled after all (e.g. the port is taken): do not keep the unlocked identity or key in memory.
            this.localIdentity = null;
            this.localTransport = null;
            this.bindAddress = null;
            throw bindFailed;
        }
        ThreadFactory daemonFactory = runnable -> {
            Thread thread = new Thread(runnable, "codefit-peer-dial-" + dialThreadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.dialExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_OUTBOUND_DIALS, daemonFactory);
    }

    /**
     * Switches the live transport key to {@code newMaterial}: every connection accepted from now on is
     * presented the new certificate and answered with a binding for the new key, on the same port, and every
     * later dial uses it too. Connections already authenticated stay open. A no-op when {@code newMaterial}
     * is the key already live.
     *
     * @return {@code true} when the live key actually changed
     * @throws IllegalStateException networking is not enabled
     */
    public synchronized boolean rekey(TransportKeyMaterial newMaterial) {
        if (listener == null) {
            throw new IllegalStateException("Networking is disabled.");
        }
        TransportIdentity next = TransportIdentity.from(newMaterial);
        if (next.publicKey().equals(localTransport.publicKey())) {
            return false;
        }
        PeerSession.Context context = new PeerSession.Context(localIdentity, next, writerEpoch,
                bindingCache, replayStates, contactLookup);
        listener.rekey(next, context);
        this.localTransport = next;
        return true;
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
        return connectInternal(contactId, address, expectedTransportKey, null, policy, cancelToken);
    }

    /**
     * Like {@link #connect(IdentityId, PeerAddress, IdentityKey, RetryPolicy, AtomicBoolean)} for a contact
     * whose pinned transport key may since have been rotated. The pinned dial goes first, exactly as
     * before. Only if the peer's certificate is refused by the pin ({@link ConnectionFailureReason#WRONG_PIN})
     * is a single <em>rollover</em> attempt made: the peer must then prove, before this side discloses
     * anything, an identity-signed binding for the key it presented that is valid now and strictly newer
     * than {@code pinned}. A peer that cannot is refused and the pin is untouched. On success the verified
     * binding reaches the {@link VerifiedBindingListener} like any other authenticated connection, which is
     * what moves the pin forward.
     */
    public CompletableFuture<DialOutcome> connect(IdentityId contactId, PeerAddress address, PinnedBinding pinned,
                                                  RetryPolicy policy, AtomicBoolean cancelToken) {
        return connectInternal(contactId, address, pinned.transportKey(), pinned, policy, cancelToken);
    }

    private CompletableFuture<DialOutcome> connectInternal(IdentityId contactId, PeerAddress address,
                                                           IdentityKey expectedTransportKey, PinnedBinding rolloverPin,
                                                           RetryPolicy policy, AtomicBoolean cancelToken) {
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
        return CompletableFuture.supplyAsync(() -> {
            DialOutcome outcome = PeerDialer.dialWithRetry(transport, context, address, contactId, expectedTransportKey,
                    policy, cancelToken, e -> notifyEvent(e));
            if (rolloverPin != null && !outcome.result().authenticated()
                    && outcome.result().failureReason() == ConnectionFailureReason.WRONG_PIN && !cancelToken.get()) {
                notifyEvent(ConnectionEvent.of(contactId, ConnectionState.CONNECTING));
                outcome = PeerDialer.rolloverOnce(transport, context, address, contactId, rolloverPin, Instant.now());
                if (!outcome.result().authenticated()) {
                    notifyEvent(ConnectionEvent.failed(contactId, ConnectionState.REJECTED,
                            outcome.result().failureReason(), outcome.result().detail()));
                }
            }
            return outcome;
        }, executor).whenComplete((outcome, throwable) -> {
            outboundSlots.release();
            if (throwable == null && outcome.connection() != null) {
                trackOutboundConnection(contactId, outcome.connection());
                try {
                    bindingListener.onVerifiedBinding(contactId, outcome.result().remoteBinding());
                } catch (RuntimeException persistenceFailure) {
                    notifyEvent(ConnectionEvent.failed(contactId, ConnectionState.CONNECTED,
                            ConnectionFailureReason.BINDING_NOT_PERSISTED, String.valueOf(persistenceFailure.getMessage())));
                }
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

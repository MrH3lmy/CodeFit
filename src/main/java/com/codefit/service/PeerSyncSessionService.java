package com.codefit.service;

import com.codefit.peer.identity.Contact;
import com.codefit.peer.identity.ContactNotFoundException;
import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.ProtocolException;
import com.codefit.peer.protocol.SignedEnvelope;
import com.codefit.peer.sync.SyncOutcome;
import com.codefit.peer.transport.PeerConnection;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Drives #184's actual peer-to-peer exchange over one authenticated {@link PeerConnection} (#182).
 * A connection is persistent - "one TLS connection carries a sequence of frames" for as long as it
 * stays open (protocol-v1.md §3) - so this is not a one-shot request/response: {@link
 * #startReceiving} begins an independent background loop that keeps validating and persisting
 * whatever the peer sends for the entire life of the connection, while {@link #sendOutboxTo} is a
 * separate, independently-triggered pass that pushes this device's own currently-eligible envelopes
 * (called right after connecting, and again whenever local state that could affect what is eligible
 * changes - new evidence, a new approval, a grant change). Neither side of this exchange is tied to
 * the other: there is no "inventory" or "acknowledgment" message type (#180 defines none, by design -
 * see the PR description's architecture notes), so correctness rests entirely on idempotent,
 * side-effect-free resend plus the receiver's own durable replay state, never on a negotiated
 * handshake.
 *
 * <p>Every blocking call here (the receive loop's {@code connection.receive()}, this class's own
 * sends) runs on a dedicated daemon thread this class owns, never the caller's thread and never the
 * JavaFX application thread - the same discipline {@code PeerNetworkService} already established.
 */
public class PeerSyncSessionService implements AutoCloseable {

    private final PeerSyncIngestService ingestService;
    private final PeerSyncOutboxService outboxService;
    private final ContactService contactService;
    private final ExecutorService receiveLoopExecutor;
    /**
     * One dedicated, bounded (single) background thread this class owns for everything a {@code
     * ConnectionEstablishedListener} callback must never do on its own calling thread - see {@link
     * #establishConnection}. Deliberately a single thread, not a general-purpose or unbounded pool:
     * ordering among establishment tasks is never relied upon for correctness (each re-checks
     * staleness for itself), this just keeps one connection's worth of dispatch work from needing its
     * own throwaway thread per establishment.
     */
    private final ExecutorService establishmentExecutor;
    private final Map<IdentityId, Registration> activeReceiveLoops = new ConcurrentHashMap<>();

    /** Which {@link PeerConnection} a registered receive loop is actually reading from, so a stale
     *  loop can never be confused with - or clobber the registration of - a newer one. */
    private record Registration(PeerConnection connection, Future<?> loop) {
    }

    public PeerSyncSessionService() {
        this(new PeerSyncIngestService(), new PeerSyncOutboxService(), new ContactService());
    }

    PeerSyncSessionService(PeerSyncIngestService ingestService, PeerSyncOutboxService outboxService, ContactService contactService) {
        this.ingestService = ingestService;
        this.outboxService = outboxService;
        this.contactService = contactService;
        ThreadFactory daemonFactory = runnable -> {
            Thread thread = new Thread(runnable, "codefit-peer-sync-receive");
            thread.setDaemon(true);
            return thread;
        };
        this.receiveLoopExecutor = Executors.newCachedThreadPool(daemonFactory);
        ThreadFactory establishFactory = runnable -> {
            Thread thread = new Thread(runnable, "codefit-peer-sync-establish");
            thread.setDaemon(true);
            return thread;
        };
        this.establishmentExecutor = Executors.newSingleThreadExecutor(establishFactory);
    }

    /**
     * The lightweight-callback-to-off-thread-work seam {@code ConnectionEstablishedListener}'s own
     * contract requires: queues {@code sendOutbox} followed by starting {@code connection}'s receive
     * loop onto this class's own dedicated {@link #establishmentExecutor} thread - never the
     * transport's own establishment thread (the dial pool or the handshake pool), which must return
     * immediately. Neither step runs at all once {@code stillCurrent} reports {@code false}: checked
     * once before {@code sendOutbox} runs (this connection may already have been superseded by a
     * reconnect that raced this very dispatch before the task even started) and again before the
     * receive loop starts (superseded <em>while</em> {@code sendOutbox} was running) - a stale task
     * that loses either check does nothing further, in particular never calling {@link #startReceiving}
     * for a connection {@code PeerNetworkService} no longer considers current, which would otherwise
     * risk tearing down a newer, genuinely-current connection's own just-registered receive loop (see
     * {@code startReceiving}'s own javadoc for exactly that clobber risk). Ordering between
     * {@code sendOutbox} and starting the receive loop is preserved exactly as before - see {@code
     * NetworkingService#onConnectionEstablished}'s own javadoc for why that specific order avoids a
     * real SQLite write race between this device's own send and receive.
     *
     * @param myIdentityIdSupplier resolved lazily, inside this task, never on the caller's thread -
     *                             reading it is itself not guaranteed instant (today, a plain field
     *                             read, but the contract here never assumes that of a caller)
     * @param sendOutbox           already closed over everything it needs (the connection, the live
     *                             identity, the writer epoch); any exception it throws is caught here
     *                             so one failed send can never kill this executor's one thread for
     *                             every later connection
     * @param onOutcome            forwarded as-is to {@link #startReceiving}; observation only
     */
    public void establishConnection(PeerConnection connection, BooleanSupplier stillCurrent,
                                     Supplier<Optional<IdentityId>> myIdentityIdSupplier, Runnable sendOutbox,
                                     BiConsumer<SignedEnvelope, SyncOutcome> onOutcome) {
        try {
            establishmentExecutor.submit(() -> {
                try {
                    if (!stillCurrent.getAsBoolean()) {
                        return; // superseded before this task even started running
                    }
                    Optional<IdentityId> myIdentityId = myIdentityIdSupplier.get();
                    if (myIdentityId.isEmpty()) {
                        return; // should not happen once networking is enabled, but never worth risking the connection over
                    }
                    sendOutbox.run();
                    if (!stillCurrent.getAsBoolean()) {
                        return; // superseded while sending; must not start a stale receive loop over a now-replaced connection
                    }
                    startReceiving(connection, myIdentityId.get(), onOutcome);
                } catch (RuntimeException unexpected) {
                    // Never let a sync-layer failure propagate out of this dedicated executor, or be
                    // mistaken for a reason to distrust an already-authenticated transport connection.
                }
            });
        } catch (RejectedExecutionException alreadyClosing) {
            // This session is already being closed (disableNetworking() raced this establishment);
            // the connection itself is already being torn down too, so there is nothing left to do.
        }
    }

    /** Convenience overload with a no-op outcome listener. */
    public void establishConnection(PeerConnection connection, BooleanSupplier stillCurrent,
                                     Supplier<Optional<IdentityId>> myIdentityIdSupplier, Runnable sendOutbox) {
        establishConnection(connection, stillCurrent, myIdentityIdSupplier, sendOutbox, (envelope, outcome) -> { });
    }

    /**
     * Starts a background loop that validates and persists every envelope this connection's peer
     * sends, until the connection closes or a connection-fatal rejection occurs (see
     * {@link SyncOutcome#connectionRecoverable()}). Idempotent only for the exact same, still-live
     * {@code connection} object - a second call with it is a no-op. A call for a <em>different</em>
     * {@code PeerConnection} belonging to the same {@code remoteIdentityId} (a reconnect) always gets
     * its own fresh loop, even if an earlier registration for that identity has not yet noticed its
     * own connection died and removed itself: that stale registration's connection is proactively
     * closed (forcing its loop to exit on its own time, never blocking this call on it) and replaced,
     * rather than being silently skipped because the identity key was already "taken" in the map. See
     * {@code PeerSyncSessionServiceReconnectRaceTest} for the exact race this guards against.
     *
     * @param onOutcome called, off the caller's thread, after each received frame is classified - for
     *                  logging/UI/test observation only; never required for correctness
     */
    public void startReceiving(PeerConnection connection, IdentityId myIdentityId, BiConsumer<SignedEnvelope, SyncOutcome> onOutcome) {
        activeReceiveLoops.compute(connection.remoteIdentityId(), (id, existing) -> {
            if (existing != null && existing.connection() == connection) {
                return existing;
            }
            if (existing != null) {
                existing.connection().close();
            }
            Future<?> loop = receiveLoopExecutor.submit(() -> receiveLoop(connection, myIdentityId, onOutcome));
            return new Registration(connection, loop);
        });
    }

    public void startReceiving(PeerConnection connection, IdentityId myIdentityId) {
        startReceiving(connection, myIdentityId, (envelope, outcome) -> { });
    }

    private void receiveLoop(PeerConnection connection, IdentityId myIdentityId, BiConsumer<SignedEnvelope, SyncOutcome> onOutcome) {
        try {
            ingestService.pruneExpiredReplayState(Instant.now());
            while (connection.isOpen()) {
                SignedEnvelope envelope;
                try {
                    envelope = connection.receive();
                } catch (IOException endOfConnection) {
                    return;
                } catch (ProtocolException rejected) {
                    SyncOutcome outcome = SyncOutcome.from(rejected.reason());
                    onOutcome.accept(null, outcome);
                    if (!outcome.connectionRecoverable()) {
                        connection.close();
                        return;
                    }
                    continue;
                }
                SyncOutcome outcome = ingestService.ingest(connection.remoteIdentityId(), myIdentityId, envelope, Instant.now());
                onOutcome.accept(envelope, outcome);
            }
        } finally {
            // Only remove the registration if it is still THIS connection's - a newer registration
            // (already installed by a reconnect while this loop was still winding down) must survive.
            activeReceiveLoops.computeIfPresent(connection.remoteIdentityId(),
                    (id, registration) -> registration.connection() == connection ? null : registration);
        }
    }

    /**
     * One send pass: computes the current bounded eligible set for this connection's peer and writes
     * each envelope in order. {@code last_synced_*} is updated only if every envelope in this pass
     * was written without an {@link IOException} - a partial failure (the connection drops partway
     * through) marks nothing as synced, so the next pass (after reconnecting) safely resends the
     * whole eligible set from the top; resend is always harmless (see class javadoc). This is a
     * materially stronger bar than "placed on one socket write", but it is still not proof the peer
     * durably persisted anything - that proof does not exist in this protocol, by design (see the PR
     * description's privacy/limitations notes).
     *
     * <p>Computing the eligible batch (which signs new control envelopes and can capture a fresh
     * snapshot - real SQLite writes) and marking entries synced afterward (another write) both run
     * under {@link PeerLocalWriteLock#MONITOR} - see that lock's own javadoc for the full, named
     * boundary this is part of. The actual {@link PeerConnection#send} socket writes in between
     * deliberately run <em>outside</em> the lock: holding a lock across blocking socket I/O would let
     * one slow or stalled peer connection stall every other connection's own sync writes for no
     * correctness reason - nothing about the race this lock closes requires the socket writes
     * themselves to be serialized, only the local database writes around them.
     *
     * @throws ContactNotFoundException the connection's authenticated peer is not a locally known contact
     */
    public SendResult sendOutboxTo(PeerConnection connection, UnlockedIdentity identity, long writerEpoch, Instant now) throws IOException {
        List<PeerSyncOutboxService.Batched> batch;
        synchronized (PeerLocalWriteLock.MONITOR) {
            Contact contact = contactService.findByIdentityId(connection.remoteIdentityId())
                    .orElseThrow(() -> new ContactNotFoundException("No contact for authenticated peer " + connection.remoteIdentityId()));
            batch = outboxService.eligibleEnvelopesFor(contact.id(), identity, writerEpoch, now);
        }

        List<PeerSyncOutboxService.Batched> sent = new ArrayList<>();
        for (PeerSyncOutboxService.Batched item : batch) {
            connection.send(item.envelope());
            sent.add(item);
        }
        synchronized (PeerLocalWriteLock.MONITOR) {
            for (PeerSyncOutboxService.Batched item : sent) {
                item.outboxEntryId().ifPresent(id -> outboxService.markSynced(id, item.envelope().header().revision(), now));
            }
        }
        return new SendResult(sent.size(), batch.size());
    }

    public record SendResult(int sentCount, int eligibleCount) {
    }

    /** Stops every background receive loop and the establishment-dispatch thread, waiting briefly
     *  for each to notice its connection is closed / finish its current task. */
    @Override
    public void close() {
        establishmentExecutor.shutdownNow();
        receiveLoopExecutor.shutdownNow();
        try {
            establishmentExecutor.awaitTermination(5, TimeUnit.SECONDS);
            receiveLoopExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

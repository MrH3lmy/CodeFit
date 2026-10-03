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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

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
    private final Map<IdentityId, Future<?>> activeReceiveLoops = new ConcurrentHashMap<>();

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
    }

    /**
     * Starts (idempotently - a second call for an already-receiving peer is a no-op) a background
     * loop that validates and persists every envelope this connection's peer sends, until the
     * connection closes or a connection-fatal rejection occurs (see {@link SyncOutcome#connectionRecoverable()}).
     *
     * @param onOutcome called, off the caller's thread, after each received frame is classified - for
     *                  logging/UI/test observation only; never required for correctness
     */
    public void startReceiving(PeerConnection connection, IdentityId myIdentityId, BiConsumer<SignedEnvelope, SyncOutcome> onOutcome) {
        activeReceiveLoops.computeIfAbsent(connection.remoteIdentityId(),
                id -> receiveLoopExecutor.submit(() -> receiveLoop(connection, myIdentityId, onOutcome)));
    }

    public void startReceiving(PeerConnection connection, IdentityId myIdentityId) {
        startReceiving(connection, myIdentityId, (envelope, outcome) -> { });
    }

    private void receiveLoop(PeerConnection connection, IdentityId myIdentityId, BiConsumer<SignedEnvelope, SyncOutcome> onOutcome) {
        try {
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
            activeReceiveLoops.remove(connection.remoteIdentityId());
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
     * @throws ContactNotFoundException the connection's authenticated peer is not a locally known contact
     */
    public SendResult sendOutboxTo(PeerConnection connection, UnlockedIdentity identity, long writerEpoch, Instant now) throws IOException {
        Contact contact = contactService.findByIdentityId(connection.remoteIdentityId())
                .orElseThrow(() -> new ContactNotFoundException("No contact for authenticated peer " + connection.remoteIdentityId()));
        List<PeerSyncOutboxService.Batched> batch = outboxService.eligibleEnvelopesFor(contact.id(), identity, writerEpoch, now);

        List<PeerSyncOutboxService.Batched> sent = new ArrayList<>();
        for (PeerSyncOutboxService.Batched item : batch) {
            connection.send(item.envelope());
            sent.add(item);
        }
        for (PeerSyncOutboxService.Batched item : sent) {
            item.outboxEntryId().ifPresent(id -> outboxService.markSynced(id, item.envelope().header().revision(), now));
        }
        return new SendResult(sent.size(), batch.size());
    }

    public record SendResult(int sentCount, int eligibleCount) {
    }

    /** Stops every background receive loop, waiting briefly for each to notice its connection is closed. */
    @Override
    public void close() {
        receiveLoopExecutor.shutdownNow();
        try {
            receiveLoopExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

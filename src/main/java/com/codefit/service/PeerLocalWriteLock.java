package com.codefit.service;

/**
 * The deliberate serialization boundary for one named category of local SQLite writes: every write
 * that is part of this device's own <strong>automatic peer-connection background lifecycle</strong> -
 * the work {@code NetworkingService}'s automatic send/receive orchestration (PR A) performs off the
 * caller's thread for every established connection, plus the two pieces of already-existing #181/#182
 * code that complete on that same connection-establishment path. Before PR A, nothing in this
 * codebase ran a second concurrent writer against the local database file from a background thread
 * while a connection was being established or synced; making that routine is exactly what exposed the
 * races this lock closes, and exactly why its boundary is scoped to that lifecycle rather than to
 * "every database write in the application" (ordinary user-driven writes - editing a flashcard,
 * recording a review - never run concurrently with this background machinery and are deliberately
 * left unguarded by this lock).
 *
 * <p>This is a considered choice of option A over B (a narrower lock inside the sync/database
 * transaction layer alone): the writers that actually contend are NOT confined to that layer - one of
 * them ({@code ContactService#recordAuthenticatedTransportBinding}) is #182 transport-binding code with
 * no sync-layer knowledge at all, reached from the connection-establishment completion path, not from
 * sync. A lock owned only inside the sync/database layer could never see that writer. A single, named,
 * cross-cutting monitor for this specific lifecycle is therefore the principled boundary, not
 * "mechanically lock everything": every call site below is listed here precisely so the boundary stays
 * an explicit, auditable set rather than "whichever two writers happened to race in whichever test."
 *
 * <p>The exact, current membership of that set - every production call path that can run as part of
 * automatic peer-connection background work, and so must hold this monitor around its own write(s):
 * <ul>
 *   <li>{@link PeerSyncIngestService#ingest} - the automatic receive loop's own per-envelope write;</li>
 *   <li>{@link PeerSyncIngestService#pruneExpiredReplayState} - the same receive loop's one-time
 *       maintenance write, run on the same background thread before its first {@code receive()};</li>
 *   <li>{@link PeerSyncSessionService#sendOutboxTo}'s write portions (computing/signing the eligible
 *       batch, and marking entries synced afterward) - the automatic one-shot outbox send performed by
 *       {@code NetworkingService}'s own connection-establishment dispatch. The actual blocking
 *       {@code PeerConnection#send} socket I/O deliberately happens <em>outside</em> this lock (see
 *       that method's own javadoc) - holding a lock across socket I/O would let one slow peer stall
 *       every other connection's own writes for no correctness reason;</li>
 *   <li>{@link ContactService#recordAddressSighting} - #182's own address-cache write, reached from
 *       {@code NetworkingService#connectToContact}'s completion, which can now run genuinely
 *       concurrently with that same connection's new automatic receive loop (the race originally found
 *       via {@code TwoProcessSyncDemoTest}'s revocation/tombstone-purge assertion);</li>
 *   <li>{@link ContactService#recordAuthenticatedTransportBinding} - #182's own transport-key-pinning
 *       write. On the <em>outbound</em> completion path this runs on the same dial-pool thread
 *       immediately after {@code ConnectionEstablishedListener} fires - and once that listener's own
 *       work is dispatched asynchronously (so the listener itself stays lightweight, never blocking
 *       I/O), this write is no longer naturally sequenced after the dispatched send/receive work the
 *       way it used to be when that work ran inline on the same thread. Without this lock, that is a
 *       second, equally real instance of the same class of race, exposed by the dispatch becoming
 *       asynchronous rather than by anything already covered by a test before this round.</li>
 * </ul>
 *
 * <p>SQLite allows only one writer across the whole file at a time; two connections from different
 * threads attempting it at once can throw {@code SQLITE_BUSY} immediately rather than one simply
 * waiting out the configured {@code busy_timeout} (see {@code DatabaseConfig}'s own javadoc for why
 * that timeout alone did not resolve this) - confirmed directly via a real two-process end-to-end run.
 * Worse than a rare retry: the resulting {@code SQLException}, surfaced by {@link
 * PeerSyncIngestService#ingest} as an unchecked exception, silently and permanently kills that
 * connection's receive loop (nobody observes the {@code Future} a background executor task fails
 * with), dropping every later frame on that connection with no error reported anywhere. Holding this
 * one lock around each contending write removes the race at its root rather than retrying or ignoring
 * it after the fact. {@code DatabaseConfig}'s own {@code busy_timeout} configuration remains in place
 * as general defensive hardening (useful against transient contention this lock's boundary does not
 * happen to cover) but is never itself the correctness mechanism for the writers listed above - this
 * lock is.
 */
final class PeerLocalWriteLock {
    static final Object MONITOR = new Object();

    private PeerLocalWriteLock() {
    }
}

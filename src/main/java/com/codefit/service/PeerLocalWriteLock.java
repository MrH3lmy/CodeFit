package com.codefit.service;

/**
 * One process-wide monitor serializing every local SQLite write this device's own peer-sync machinery
 * can now trigger from more than one thread at once. Before this PR, nothing in #181/#182/#184 ever
 * ran a second concurrent writer against the same local database file from a background thread while
 * a connection was being established; PR A's own automatic orchestration ({@code
 * NetworkingService#onConnectionEstablished} starting an automatic receive loop for every established
 * connection) made that routine, and exposed a genuine, reproducible race between {@link
 * PeerSyncIngestService#ingest} (that receive loop's own write) and {@code
 * ContactService#recordAddressSighting} (already-existing #182 code {@code NetworkingService
 * #connectToContact}'s own completion runs, now genuinely concurrently with that same connection's new
 * automatic receive loop for the first time).
 *
 * <p>SQLite allows only one writer across the whole file at a time; two connections from different
 * threads attempting it at once can throw {@code SQLITE_BUSY} immediately rather than one simply
 * waiting out the configured {@code busy_timeout} (see {@code DatabaseConfig}'s own javadoc for why
 * that timeout alone did not resolve this) - confirmed directly via a real two-process end-to-end run:
 * the resulting {@code SQLException}, surfaced by {@link PeerSyncIngestService#ingest} as an unchecked
 * exception, silently and permanently kills that connection's receive loop (nobody observes the
 * {@code Future} a background executor task fails with), with no error reported anywhere - dropping
 * every later frame on that connection, including a revocation tombstone, with no retry. Holding this
 * one lock around each contending write removes the race at its root rather than retrying or ignoring
 * it after the fact.
 */
final class PeerLocalWriteLock {
    static final Object MONITOR = new Object();

    private PeerLocalWriteLock() {
    }
}

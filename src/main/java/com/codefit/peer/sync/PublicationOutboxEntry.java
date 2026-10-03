package com.codefit.peer.sync;

import com.codefit.peer.protocol.MessageType;

import java.time.Instant;

/**
 * One logical object the learner has explicitly approved for ongoing sharing with one contact
 * (#184). {@code logicalKey} is {@code PeerSyncOutboxService}'s own encoding: a window identity for
 * {@code PROGRESS_SUMMARY}, or the profile id itself for {@code PREPARATION_SNAPSHOT}.
 *
 * @param lastSyncedRevision an optimistic, UI-facing hint only (see {@code PublicationOutboxRepository});
 *                           never consulted to decide whether to (re)send this object
 */
public record PublicationOutboxEntry(long id, long contactId, MessageType messageType, String logicalKey,
                                      Instant approvedAt, Long lastSyncedRevision, Instant lastSyncedAt) {
}

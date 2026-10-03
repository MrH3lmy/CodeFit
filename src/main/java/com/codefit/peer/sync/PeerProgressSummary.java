package com.codefit.peer.sync;

import com.codefit.peer.protocol.ComparisonWindow;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.MetricValue;
import com.codefit.peer.protocol.ObjectId;

import java.time.Instant;
import java.util.List;

/**
 * A validated, cached {@code PROGRESS_SUMMARY} received from a peer (#184). This is received,
 * peer-authored data - never this learner's own evidence (that is {@code
 * com.codefit.peer.snapshot.LocalProgressSnapshot}) - held only so a future #185 can compare it
 * against local evidence. {@code epoch}/{@code revision} are the exact values the envelope carried,
 * kept for display/debugging; supersession has already happened by the time this is persisted (only
 * the latest revision per {@code (author, objectId)} is ever stored).
 */
public record PeerProgressSummary(IdentityId author, ObjectId objectId, long epoch, long revision,
                                   ComparisonWindow window, Instant cutoff, List<MetricValue> metrics, Instant receivedAt) {
}

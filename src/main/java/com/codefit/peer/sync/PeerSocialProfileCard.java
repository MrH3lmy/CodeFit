package com.codefit.peer.sync;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.ObjectId;
import com.codefit.peer.protocol.SocialProfileCard;

import java.time.Instant;

/** A validated, cached {@code SOCIAL_PROFILE_CARD} received from a peer (#184); one per author, latest revision only. */
public record PeerSocialProfileCard(IdentityId author, ObjectId objectId, long epoch, long revision,
                                     SocialProfileCard body, Instant receivedAt) {
}

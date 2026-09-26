package com.codefit.peer.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Object ids that are singletons per author (or per author/recipient pair) are derived, not random, so
 * "the current profile card" and "the current consent for this recipient" are single logical objects
 * whose revisions order naturally and cannot be forked into parallel records.
 */
final class DerivedObjectIds {
    private DerivedObjectIds() {
    }

    static ObjectId derive(String context, IdentityId... ids) {
        byte[][] parts = new byte[ids.length + 1][];
        parts[0] = (context + "\0").getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < ids.length; i++) {
            parts[i + 1] = ids[i].bytes();
        }
        return new ObjectId(Arrays.copyOf(ProtocolBytes.sha256(parts), ObjectId.LENGTH));
    }
}

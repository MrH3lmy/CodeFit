package com.codefit.peer.identity;

import com.codefit.peer.protocol.SharingScope;

import java.util.Comparator;
import java.util.List;

/**
 * Canonicalizes a scope list to the same shape {@code com.codefit.peer.protocol.ConsentRevision}
 * requires on the wire: strictly ascending by wire code, no duplicates. {@link PermissionGrant} and
 * {@link ContactPermission} both apply this at construction, so an out-of-order or duplicated grant
 * can never reach persistence or the outbox in a shape #184 would be unable to serialize as a
 * {@code ConsentRevision}.
 */
final class SharingScopes {
    private SharingScopes() {
    }

    static List<SharingScope> canonicalize(List<SharingScope> scopes) {
        return scopes.stream()
                .sorted(Comparator.comparingInt(SharingScope::code))
                .distinct()
                .toList();
    }
}

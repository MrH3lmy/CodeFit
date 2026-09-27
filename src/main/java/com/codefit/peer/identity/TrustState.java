package com.codefit.peer.identity;

/**
 * A contact's local trust/pairing state. Discovery alone (learning a candidate's identity key and
 * claimed display name, e.g. from a future #182 invitation or LAN announcement) only ever creates a
 * {@link #PENDING} contact; it never itself grants any {@link com.codefit.peer.protocol.SharingScope}.
 * Only an explicit {@code acceptInvitation} moves a contact to {@link #PAIRED}, and even then sharing
 * permissions stay empty until the user separately grants them.
 */
public enum TrustState {
    /** Known (invited, or discovered) but not yet explicitly accepted. Grants nothing. */
    PENDING,
    /** Explicitly accepted. May be granted sharing permissions. */
    PAIRED,
    /** Explicitly blocked: permissions are forced empty and stay empty until an explicit re-pair. */
    BLOCKED,
    /** Explicitly removed: permissions are forced empty; re-adding requires an explicit re-pair. */
    REMOVED
}

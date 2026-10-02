package com.codefit.peer.identity;

/** Where a cached reachable address for a contact came from (#182). */
public enum ContactAddressSource {
    /** One of the 1-4 IP-literal addresses carried by the invitation accepted to pair this contact. */
    INVITATION,
    /** Explicitly entered by the user. */
    MANUAL,
    /** Learned via opt-in LAN discovery (never from an untrusted/unpaired sender). */
    LAN_DISCOVERY
}

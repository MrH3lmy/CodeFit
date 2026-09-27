package com.codefit.peer.identity;

import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityKey;

import java.security.PrivateKey;

/**
 * The result of successfully opening the local identity vault: a live private key held only in
 * process memory for the caller's current use, never returned by any other {@code IdentityService}
 * method and never itself persisted. The JDK gives no guaranteed way to wipe an {@code Ed25519}
 * {@link PrivateKey} object's backing bytes on close (it does not implement
 * {@link javax.security.auth.Destroyable} on the {@code SunEC} provider) — that is a real, documented
 * limitation of running on the plain JDK rather than a platform keystore: the key lives in the JVM
 * heap for as long as this object (and anything that copied its signature output) is reachable, until
 * ordinary garbage collection reclaims it.
 */
public final class UnlockedIdentity {
    private final IdentityKey publicKey;
    private final PrivateKey privateKey;

    public UnlockedIdentity(IdentityKey publicKey, PrivateKey privateKey) {
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public IdentityKey publicKey() {
        return publicKey;
    }

    public byte[] sign(byte[] message) {
        return KeyPairs.sign(privateKey, message);
    }

    @Override
    public String toString() {
        return "UnlockedIdentity[" + publicKey + "]";
    }
}

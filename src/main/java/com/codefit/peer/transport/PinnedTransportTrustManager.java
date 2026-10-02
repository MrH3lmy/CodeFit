package com.codefit.peer.transport;

import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityKey;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;

/**
 * The dialer's trust manager: rejects everything except a certificate whose Ed25519 public key is
 * byte-for-byte the one transport key the caller already expects (from a Contact's cached
 * {@code IDENTITY_BINDING} or a just-accepted {@link com.codefit.peer.invitation.Invitation}). This is
 * the literal "pin the expected transport key" requirement, enforced at the TLS layer itself, before any
 * application byte is exchanged — never a trust-all manager. A wrong pin fails the handshake outright
 * with {@link CertificateException}, which callers surface as {@link ConnectionFailureReason#WRONG_PIN}.
 */
final class PinnedTransportTrustManager extends X509ExtendedTrustManager {
    private final byte[] expectedRawTransportKey;

    PinnedTransportTrustManager(IdentityKey expectedTransportKey) {
        this.expectedRawTransportKey = Objects.requireNonNull(expectedTransportKey, "expectedTransportKey").bytes();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        check(chain);
    }

    private void check(X509Certificate[] chain) throws CertificateException {
        StructuralTransportTrustManager.checkStructurallyValid(chain);
        byte[] presented = KeyPairs.rawPublicKey(chain[0].getPublicKey());
        if (!Arrays.equals(presented, expectedRawTransportKey)) {
            throw new CertificateException("Presented transport key does not match the pinned expected key.");
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}

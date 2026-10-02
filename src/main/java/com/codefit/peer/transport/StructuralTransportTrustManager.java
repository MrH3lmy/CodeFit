package com.codefit.peer.transport;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * The listener's trust manager. A listener cannot know in advance which of its paired contacts will
 * dial in next, so it cannot pin one specific key the way {@link PinnedTransportTrustManager} does for
 * an outbound connection. This is deliberately <strong>not</strong> a trust-all manager: it still
 * rejects anything that is not exactly one well-formed, currently self-consistent, self-signed Ed25519
 * certificate whose own signature verifies against its own public key. That is a real, structural check
 * — it is just not an identity decision. The identity decision (is this actually a known, paired
 * contact, and does its live certificate key match a currently-valid {@code IDENTITY_BINDING} it signed
 * with its identity key?) happens one layer up, after the handshake, in {@link PeerSession}, which is
 * the only place "Invitation parsing alone does not authenticate the network endpoint" is actually
 * enforced for inbound connections.
 */
final class StructuralTransportTrustManager extends X509ExtendedTrustManager {

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        checkStructurallyValid(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        checkStructurallyValid(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkStructurallyValid(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        checkStructurallyValid(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkStructurallyValid(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        checkStructurallyValid(chain);
    }

    static void checkStructurallyValid(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length != 1) {
            throw new CertificateException("Expected exactly one self-signed transport certificate, got "
                    + (chain == null ? 0 : chain.length) + ".");
        }
        X509Certificate certificate = chain[0];
        if (!"EdDSA".equals(certificate.getPublicKey().getAlgorithm())) {
            throw new CertificateException("Transport certificate is not an Ed25519/EdDSA key.");
        }
        certificate.checkValidity();
        if (!certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal())) {
            throw new CertificateException("Transport certificate must be self-signed (issuer must equal subject).");
        }
        try {
            certificate.verify(certificate.getPublicKey());
        } catch (GeneralSecurityException e) {
            throw new CertificateException("Transport certificate does not self-verify.", e);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}

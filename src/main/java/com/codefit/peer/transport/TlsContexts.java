package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityKey;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;

/**
 * Builds the JDK JSSE {@link SSLContext} for #182's transport: plain TCP, TLS 1.3, mutual
 * authentication, self-signed Ed25519 certificates. No certificate authority, no revocation checking,
 * and no AIA fetching, exactly as ADR-0001 §5 and the runtime dependency inventory record: this project
 * pins keys with a custom trust manager instead of using PKIX chain validation, so there is nothing for
 * OCSP/CRL/AIA to fetch — the two system properties below are still set explicitly, as documented, for
 * defense in depth and to leave direct evidence in the JVM's own state rather than relying only on the
 * absence of a PKIX trust manager.
 */
final class TlsContexts {
    static final String PROTOCOL = "TLSv1.3";

    static {
        // Explicitly disabled, matching docs/p2p/runtime-dependency-inventory.md: "#182 sets both
        // explicitly to false". Both are already unset by default; this removes any doubt.
        System.setProperty("com.sun.net.ssl.checkRevocation", "false");
        System.setProperty("com.sun.security.enableAIAcaIssuers", "false");
    }

    /**
     * Shared across every {@link SSLContext} this class builds. The platform default
     * {@code new SecureRandom()} algorithm can block gathering its first seed on a host with a limited
     * entropy pool (observed on this project's own CI/sandbox containers: tens of seconds on the very
     * first draw). One shared, already-seeded instance pays that cost at most once per JVM instead of
     * once per dial/listen — {@link SecureRandom} is documented thread-safe for concurrent use.
     */
    private static final SecureRandom SHARED_SECURE_RANDOM = newNonBlockingSecureRandom();

    private static SecureRandom newNonBlockingSecureRandom() {
        try {
            return SecureRandom.getInstance("NativePRNGNonBlocking");
        } catch (java.security.NoSuchAlgorithmException e) {
            return new SecureRandom();
        }
    }

    private TlsContexts() {
    }

    /** For the inbound listener: accepts any structurally valid self-signed Ed25519 client certificate. */
    static SSLContext forListener(TransportIdentity local) {
        return build(local, new TrustManager[]{new StructuralTransportTrustManager()});
    }

    /** For an outbound dial: accepts only a server certificate whose key equals {@code expectedTransportKey}. */
    static SSLContext forDialer(TransportIdentity local, IdentityKey expectedTransportKey) {
        return build(local, new TrustManager[]{new PinnedTransportTrustManager(expectedTransportKey)});
    }

    private static SSLContext build(TransportIdentity local, TrustManager[] trustManagers) {
        try {
            char[] password = "codefit-transport".toCharArray();
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("transport", local.keyPair().getPrivate(), password,
                    new Certificate[]{local.certificate()});

            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();

            SSLContext context = SSLContext.getInstance(PROTOCOL);
            context.init(keyManagers, trustManagers, SHARED_SECURE_RANDOM);
            return context;
        } catch (GeneralSecurityException | java.io.IOException e) {
            throw new IllegalStateException("Unable to build the transport TLS context.", e);
        }
    }

    static void hardenSocket(SSLSocket socket, boolean requireClientAuth) {
        socket.setEnabledProtocols(new String[]{PROTOCOL});
        if (requireClientAuth) {
            socket.setNeedClientAuth(true);
        }
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm(null);
        socket.setSSLParameters(parameters);
    }

    static void hardenServerSocket(SSLServerSocket serverSocket) {
        serverSocket.setEnabledProtocols(new String[]{PROTOCOL});
        serverSocket.setNeedClientAuth(true);
        SSLParameters parameters = serverSocket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm(null);
        serverSocket.setSSLParameters(parameters);
    }
}


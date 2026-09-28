package com.codefit.peer.transport;

import com.codefit.peer.identity.crypto.KeyPairs;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live loopback evidence for #182's transport-security selection (ADR-0001 §5): a real mutual TLS 1.3
 * handshake between two self-signed Ed25519 certificates, with a pinned dialer rejecting a wrong key
 * exactly as the "wrong identities/pins" acceptance criterion requires.
 */
class TlsContextsTest {

    private static TransportIdentity newTransportIdentity() {
        KeyPair keyPair = KeyPairs.generate();
        Instant now = Instant.now();
        Instant validFrom = now.minus(1, ChronoUnit.HOURS);
        Instant validUntil = now.plus(90, ChronoUnit.DAYS);
        return new TransportIdentity(keyPair, SelfSignedCertificateFactory.create(keyPair, validFrom, validUntil), validFrom, validUntil);
    }

    @Test
    void completesMutualTlsWhenTheDialerPinsTheCorrectKey() throws Exception {
        TransportIdentity server = newTransportIdentity();
        TransportIdentity client = newTransportIdentity();

        SSLContext serverContext = TlsContexts.forListener(server);
        SSLContext clientContext = TlsContexts.forDialer(client, server.publicKey());

        try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TlsContexts.hardenServerSocket(serverSocket);
            int port = serverSocket.getLocalPort();

            CompletableFuture<String> serverSide = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                    accepted.startHandshake();
                    return accepted.getSession().getCipherSuite();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            try (SSLSocket clientSocket = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                TlsContexts.hardenSocket(clientSocket, false);
                clientSocket.startHandshake();
                assertEquals("TLSv1.3", clientSocket.getSession().getProtocol());
                assertArrayEquals(server.publicKey().bytes(),
                        KeyPairs.rawPublicKey(clientSocket.getSession().getPeerCertificates()[0].getPublicKey()));
            }

            String serverCipherSuite = serverSide.get(5, TimeUnit.SECONDS);
            assertNotNull(serverCipherSuite);
        }
    }

    @Test
    void rejectsAWrongPinnedTransportKey() throws Exception {
        TransportIdentity server = newTransportIdentity();
        TransportIdentity impostor = newTransportIdentity();
        TransportIdentity client = newTransportIdentity();
        // Dialer pins the REAL server's key, but connects to a socket presenting the impostor's cert.
        SSLContext serverContext = TlsContexts.forListener(impostor);
        SSLContext clientContext = TlsContexts.forDialer(client, server.publicKey());

        try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TlsContexts.hardenServerSocket(serverSocket);
            int port = serverSocket.getLocalPort();

            CompletableFuture<Void> serverSide = CompletableFuture.runAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                    accepted.startHandshake();
                } catch (IOException ignored) {
                    // expected: the client aborts the handshake once it rejects the pin
                }
            });

            try (SSLSocket clientSocket = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                TlsContexts.hardenSocket(clientSocket, false);
                Exception failure = assertThrows(IOException.class, clientSocket::startHandshake);
                assertTrue(containsCertificateException(failure), "expected a certificate/pin rejection, got: " + failure);
            }
            serverSide.get(5, TimeUnit.SECONDS);
        }
    }

    private static boolean containsCertificateException(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }
}

package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.IdentityKey;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.net.InetAddress;
import java.security.KeyPair;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two independent, in-process "peers" (separate identity/transport key material each, talking over a
 * real loopback TLS socket) exercising #182's full post-handshake authentication protocol: the version
 * hello and the mutual {@code IDENTITY_BINDING} proof. This is the core of the "two independent
 * processes... exchange protocol messages over real sockets" acceptance criterion, minus only the
 * separate-OS-process framing (the sockets, TLS, and protocol codec are all real).
 */
class PeerSessionTest {

    private record Peer(UnlockedIdentity identity, TransportIdentity transport) {
        static Peer create() {
            KeyPair identityKeyPair = KeyPairs.generate();
            UnlockedIdentity identity = new UnlockedIdentity(
                    new IdentityKey(KeyPairs.rawPublicKey(identityKeyPair.getPublic())), identityKeyPair.getPrivate());
            KeyPair transportKeyPair = KeyPairs.generate();
            Instant now = Instant.now();
            Instant validFrom = now.minus(1, ChronoUnit.HOURS);
            Instant validUntil = now.plus(90, ChronoUnit.DAYS);
            TransportIdentity transport = new TransportIdentity(transportKeyPair,
                    SelfSignedCertificateFactory.create(transportKeyPair, validFrom, validUntil), validFrom, validUntil);
            return new Peer(identity, transport);
        }

        PeerSession.Context context(KnownContactLookup lookup) {
            return new PeerSession.Context(identity, transport, 1L, new LocalBindingEnvelopeCache(),
                    new PeerSession.ReplayStates(), lookup);
        }
    }

    @Test
    void mutuallyAuthenticatesTwoIndependentPeersOverRealSockets() throws Exception {
        Peer alice = Peer.create();
        Peer bob = Peer.create();
        KnownContactLookup aliceKnowsBobAsPaired = id -> id.equals(bob.identity().publicKey().id())
                ? KnownContactLookup.Status.PAIRED : KnownContactLookup.Status.UNKNOWN;
        KnownContactLookup bobKnowsAliceAsPaired = id -> id.equals(alice.identity().publicKey().id())
                ? KnownContactLookup.Status.PAIRED : KnownContactLookup.Status.UNKNOWN;

        SSLContext serverContext = TlsContexts.forListener(bob.transport());
        SSLContext clientContext = TlsContexts.forDialer(alice.transport(), bob.transport().publicKey());

        try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TlsContexts.hardenServerSocket(serverSocket);
            int port = serverSocket.getLocalPort();

            CompletableFuture<ConnectionOutcome> serverResult = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                    return PeerSession.accept(accepted, bob.context(bobKnowsAliceAsPaired), Instant.now());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            ConnectionOutcome clientResult;
            try (SSLSocket clientSocket = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                TlsContexts.hardenSocket(clientSocket, false);
                clientResult = PeerSession.dial(clientSocket, alice.context(aliceKnowsBobAsPaired),
                        bob.identity().publicKey().id(), Instant.now());
            }

            assertTrue(clientResult.authenticated(), "client should authenticate: " + clientResult);
            assertEquals(bob.identity().publicKey().id(), clientResult.remoteIdentityId());

            ConnectionOutcome server = serverResult.get(5, TimeUnit.SECONDS);
            assertTrue(server.authenticated(), "server should authenticate: " + server);
            assertEquals(alice.identity().publicKey().id(), server.remoteIdentityId());
        }
    }

    @Test
    void rejectsAnUnknownStrangerDialingTheListener() throws Exception {
        Peer bob = Peer.create();
        Peer stranger = Peer.create();
        KnownContactLookup bobKnowsNoOne = id -> KnownContactLookup.Status.UNKNOWN;

        SSLContext serverContext = TlsContexts.forListener(bob.transport());
        SSLContext clientContext = TlsContexts.forDialer(stranger.transport(), bob.transport().publicKey());

        try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TlsContexts.hardenServerSocket(serverSocket);
            int port = serverSocket.getLocalPort();

            CompletableFuture<ConnectionOutcome> serverResult = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                    return PeerSession.accept(accepted, bob.context(bobKnowsNoOne), Instant.now());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            try (SSLSocket clientSocket = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                TlsContexts.hardenSocket(clientSocket, false);
                assertThrows(java.io.IOException.class, () ->
                        PeerSession.dial(clientSocket, stranger.context(bobKnowsNoOne), bob.identity().publicKey().id(), Instant.now()));
            }

            ConnectionOutcome server = serverResult.get(5, TimeUnit.SECONDS);
            assertFalse(server.authenticated());
            assertEquals(ConnectionFailureReason.UNKNOWN_IDENTITY, server.failureReason());
        }
    }

    /**
     * If the caller addresses its own proof to the wrong expected identity (a local bookkeeping
     * mistake, e.g. confusing which contact record goes with a pinned transport key), the real peer's
     * own {@code EnvelopeAcceptancePolicy} rejects that misaddressed envelope as
     * {@code UNAUTHORIZED_AUDIENCE} before it ever gets a chance to reply — so the caller never even
     * receives a mismatched identity to compare against; the connection just fails safely. The explicit
     * {@code IDENTITY_MISMATCH} equality check in {@link PeerSession#dial} is defense in depth for the
     * case where a genuine reply's author differs from what was expected (guards against a corrupted or
     * malicious contact record being looked up between pinning and dialing).
     */
    @Test
    void misaddressingTheProofToTheWrongExpectedIdentityFailsSafelyInsteadOfAuthenticating() throws Exception {
        Peer bob = Peer.create();
        Peer wronglyExpectedIdentity = Peer.create();
        Peer alice = Peer.create();
        KnownContactLookup everyoneKnown = id -> KnownContactLookup.Status.PAIRED;

        SSLContext serverContext = TlsContexts.forListener(bob.transport());
        SSLContext clientContext = TlsContexts.forDialer(alice.transport(), bob.transport().publicKey());

        try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TlsContexts.hardenServerSocket(serverSocket);
            int port = serverSocket.getLocalPort();

            CompletableFuture<ConnectionOutcome> serverResult = CompletableFuture.supplyAsync(() -> {
                try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                    return PeerSession.accept(accepted, bob.context(everyoneKnown), Instant.now());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            try (SSLSocket clientSocket = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), port)) {
                TlsContexts.hardenSocket(clientSocket, false);
                assertThrows(java.io.IOException.class, () -> PeerSession.dial(clientSocket, alice.context(everyoneKnown),
                        wronglyExpectedIdentity.identity().publicKey().id(), Instant.now()));
            }

            ConnectionOutcome server = serverResult.get(5, TimeUnit.SECONDS);
            assertFalse(server.authenticated());
            assertEquals(ConnectionFailureReason.ENVELOPE_REJECTED, server.failureReason());
        }
    }

    @Test
    void resendingTheSameBindingWithinOneEpochIsIdempotentNotAForkOrStaleRevision() throws Exception {
        Peer alice = Peer.create();
        Peer bob = Peer.create();
        KnownContactLookup aliceKnowsBob = id -> KnownContactLookup.Status.PAIRED;
        KnownContactLookup bobKnowsAlice = id -> KnownContactLookup.Status.PAIRED;

        PeerSession.Context aliceContext = alice.context(aliceKnowsBob);
        PeerSession.Context bobContext = bob.context(bobKnowsAlice);

        for (int attempt = 0; attempt < 2; attempt++) {
            SSLContext serverContext = TlsContexts.forListener(bob.transport());
            SSLContext clientContext = TlsContexts.forDialer(alice.transport(), bob.transport().publicKey());
            try (SSLServerSocket serverSocket = (SSLServerSocket) serverContext.getServerSocketFactory()
                    .createServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
                TlsContexts.hardenServerSocket(serverSocket);
                int port = serverSocket.getLocalPort();
                CompletableFuture<ConnectionOutcome> serverResult = CompletableFuture.supplyAsync(() -> {
                    try (SSLSocket accepted = (SSLSocket) serverSocket.accept()) {
                        return PeerSession.accept(accepted, bobContext, Instant.now());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
                try (SSLSocket clientSocket = (SSLSocket) clientContext.getSocketFactory()
                        .createSocket(InetAddress.getLoopbackAddress(), port)) {
                    TlsContexts.hardenSocket(clientSocket, false);
                    ConnectionOutcome clientResult = PeerSession.dial(clientSocket, aliceContext, bob.identity().publicKey().id(), Instant.now());
                    assertTrue(clientResult.authenticated(), "attempt " + attempt + ": " + clientResult);
                }
                ConnectionOutcome server = serverResult.get(5, TimeUnit.SECONDS);
                assertTrue(server.authenticated(), "attempt " + attempt + " (server side): " + server);
            }
        }
    }
}

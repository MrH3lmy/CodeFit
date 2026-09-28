package com.codefit.peer.transport;

import com.codefit.peer.identity.UnlockedIdentity;
import com.codefit.peer.identity.crypto.KeyPairs;
import com.codefit.peer.protocol.AcceptanceVerdict;
import com.codefit.peer.protocol.AuthorReplayState;
import com.codefit.peer.protocol.EnvelopeAcceptancePolicy;
import com.codefit.peer.protocol.IdentityBinding;
import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;
import com.codefit.peer.protocol.ProtocolVersion;
import com.codefit.peer.protocol.RejectionReason;
import com.codefit.peer.protocol.SignedEnvelope;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The post-TLS-handshake application protocol for #182: a transport-level version {@code hello}
 * exchange, then a mandatory, mutual {@code IDENTITY_BINDING} envelope exchange that is the only thing
 * that actually authenticates <em>who</em> is on the other end of the socket. Mutual TLS alone only
 * proves the peer holds the private key for the certificate it presented; it says nothing about which
 * identity authorized that transport key, which is exactly why "parsing an invitation alone" or
 * "completing a TLS handshake alone" must never establish trust (ADR-0001 §4, #182 requirements).
 *
 * <p>Whoever dialed (the TLS client) always proves itself first, because it is the one that already
 * claims to know who it is calling; the accepting side (the TLS server) cannot address its own binding
 * envelope until it has learned the caller's identity from that first proof. Both sides send their own
 * {@code hello} immediately (no ordering dependency: TCP buffers a few bytes without either side needing
 * to block), so there is no deadlock between the two roles.
 */
final class PeerSession {
    private static final List<Integer> SUPPORTED_MAJOR_VERSIONS = List.of(ProtocolVersion.MAJOR);

    private PeerSession() {
    }

    record Context(UnlockedIdentity localIdentity, TransportIdentity localTransport, long writerEpoch,
                    LocalBindingEnvelopeCache bindingCache, ReplayStates replayStates,
                    KnownContactLookup contactLookup) {
    }

    /** Per-process (never-persisted-yet, per protocol §10: "Replay state is in memory in #180") author replay tracking. */
    static final class ReplayStates {
        private final Map<IdentityId, AuthorReplayState> states = new ConcurrentHashMap<>();

        AuthorReplayState forAuthor(IdentityKey author) {
            return states.computeIfAbsent(author.id(), id -> new AuthorReplayState(author));
        }
    }

    /** TLS client role: dials expecting {@code expectedRemoteIdentity} at the pinned transport key. */
    static ConnectionOutcome dial(SSLSocket socket, Context context, IdentityId expectedRemoteIdentity, Instant now) throws IOException {
        socket.startHandshake();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        HandshakeIo.writeHello(out, SUPPORTED_MAJOR_VERSIONS);
        SignedEnvelope ownBinding = context.bindingCache().get(context.localIdentity(), context.localTransport(),
                expectedRemoteIdentity, context.writerEpoch(), now);
        HandshakeIo.writeEnvelopeFrame(out, ownBinding);

        List<Integer> peerVersions = HandshakeIo.readHello(in);
        if (!hasOverlap(peerVersions, SUPPORTED_MAJOR_VERSIONS)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.UNSUPPORTED_VERSION,
                    "Peer supports major versions " + peerVersions + ", we support " + SUPPORTED_MAJOR_VERSIONS);
        }

        SignedEnvelope peerBinding = HandshakeIo.readEnvelopeFrame(in);
        if (!peerBinding.header().author().id().equals(expectedRemoteIdentity)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.IDENTITY_MISMATCH,
                    "Expected " + expectedRemoteIdentity + " but the peer's binding was authored by "
                            + peerBinding.header().author().id());
        }
        return validateBindingAndAuthorize(socket, context, peerBinding, now);
    }

    /** TLS server role: accepts any structurally valid caller and learns their claimed identity from the exchange. */
    static ConnectionOutcome accept(SSLSocket socket, Context context, Instant now) throws IOException {
        socket.startHandshake();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        HandshakeIo.writeHello(out, SUPPORTED_MAJOR_VERSIONS);

        List<Integer> peerVersions = HandshakeIo.readHello(in);
        SignedEnvelope callerBinding = HandshakeIo.readEnvelopeFrame(in);
        if (!hasOverlap(peerVersions, SUPPORTED_MAJOR_VERSIONS)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.UNSUPPORTED_VERSION,
                    "Caller supports major versions " + peerVersions + ", we support " + SUPPORTED_MAJOR_VERSIONS);
        }

        ConnectionOutcome callerVerdict = validateBindingAndAuthorize(socket, context, callerBinding, now);
        if (!callerVerdict.authenticated()) {
            return callerVerdict;
        }

        SignedEnvelope ownBinding = context.bindingCache().get(context.localIdentity(), context.localTransport(),
                callerVerdict.remoteIdentityId(), context.writerEpoch(), now);
        HandshakeIo.writeEnvelopeFrame(out, ownBinding);
        return callerVerdict;
    }

    private static ConnectionOutcome validateBindingAndAuthorize(SSLSocket socket, Context context, SignedEnvelope bindingEnvelope,
                                                        Instant now) throws IOException {
        if (!(bindingEnvelope.body() instanceof IdentityBinding binding)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.MALFORMED_FRAME,
                    "Expected the first envelope to carry an IDENTITY_BINDING.");
        }
        IdentityKey claimedAuthor = bindingEnvelope.header().author();
        IdentityId claimedAuthorId = claimedAuthor.id();

        KnownContactLookup.Status status = context.contactLookup().statusOf(claimedAuthorId);
        if (status == KnownContactLookup.Status.UNKNOWN) {
            return ConnectionOutcome.fail(ConnectionFailureReason.UNKNOWN_IDENTITY, "Not a known contact: " + claimedAuthorId);
        }
        if (status == KnownContactLookup.Status.PENDING) {
            return ConnectionOutcome.fail(ConnectionFailureReason.NOT_PAIRED, "Contact is pending, not yet paired: " + claimedAuthorId);
        }
        if (status == KnownContactLookup.Status.BLOCKED_OR_REMOVED) {
            return ConnectionOutcome.fail(ConnectionFailureReason.NOT_PAIRED, "Contact is blocked or removed: " + claimedAuthorId);
        }

        AuthorReplayState replayState = context.replayStates().forAuthor(claimedAuthor);
        AcceptanceVerdict verdict = new EnvelopeAcceptancePolicy(localReceiverId(context))
                .evaluate(bindingEnvelope, replayState, now);
        if (!verdict.accepted() && verdict.reason() != RejectionReason.DUPLICATE) {
            return ConnectionOutcome.fail(ConnectionFailureReason.ENVELOPE_REJECTED,
                    "IDENTITY_BINDING rejected: " + verdict.reason());
        }

        if (now.isBefore(binding.validFrom())) {
            return ConnectionOutcome.fail(ConnectionFailureReason.BINDING_NOT_YET_VALID, "Binding is not valid yet.");
        }
        if (!now.isBefore(binding.validUntil())) {
            return ConnectionOutcome.fail(ConnectionFailureReason.BINDING_EXPIRED, "Binding has expired.");
        }

        byte[] liveCertificateKey = rawPeerCertificateKey(socket);
        if (!java.util.Arrays.equals(liveCertificateKey, binding.transportKey().bytes())) {
            return ConnectionOutcome.fail(ConnectionFailureReason.BINDING_KEY_MISMATCH,
                    "The live TLS certificate key does not match the transport key the IDENTITY_BINDING vouches for.");
        }

        return ConnectionOutcome.ok(claimedAuthorId);
    }

    private static IdentityId localReceiverId(Context context) {
        return context.localIdentity().publicKey().id();
    }

    private static byte[] rawPeerCertificateKey(SSLSocket socket) throws IOException {
        try {
            Certificate[] certs = socket.getSession().getPeerCertificates();
            if (certs.length == 0 || !(certs[0] instanceof X509Certificate x509)) {
                throw new TransportProtocolException(ConnectionFailureReason.TLS_HANDSHAKE_FAILED,
                        "No X.509 peer certificate on the TLS session.");
            }
            return KeyPairs.rawPublicKey(x509.getPublicKey());
        } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
            throw new TransportProtocolException(ConnectionFailureReason.TLS_HANDSHAKE_FAILED,
                    "Peer did not present a certificate.", e);
        }
    }

    private static boolean hasOverlap(List<Integer> a, List<Integer> b) {
        return a.stream().anyMatch(b::contains);
    }
}

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
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketException;
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
        return validateBindingAndAuthorize(socket, context, peerBinding, now, null);
    }

    /**
     * TLS role: dial in <em>rollover</em> mode, after the pinned dial was refused because the peer's
     * certificate key is not the one pinned (transport-v1 §9). The TLS layer here accepts any structurally
     * valid certificate, so nothing about the socket is trusted yet; consequently this side discloses
     * <strong>nothing</strong> (no identity, no binding) until the peer has first proven, with an
     * identity-signed {@code IDENTITY_BINDING}, that its identity key authorizes exactly the key it
     * presented, that the binding is valid now, and that it is strictly newer than the pinned one. Only
     * then does it send its own binding. A peer that cannot do that is refused and nothing is persisted.
     */
    static ConnectionOutcome dialForRollover(SSLSocket socket, Context context, IdentityId expectedRemoteIdentity,
                                             PinnedBinding pinned, Instant now) throws IOException {
        socket.startHandshake();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        HandshakeIo.writeHello(out, SUPPORTED_MAJOR_VERSIONS, true);
        HandshakeIo.Hello peerHello = HandshakeIo.readHelloFrame(in);
        if (!hasOverlap(peerHello.versions(), SUPPORTED_MAJOR_VERSIONS)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.UNSUPPORTED_VERSION,
                    "Peer supports major versions " + peerHello.versions() + ", we support " + SUPPORTED_MAJOR_VERSIONS);
        }

        SignedEnvelope peerBinding;
        try {
            peerBinding = HandshakeIo.readEnvelopeFrame(in);
        } catch (EOFException | SocketException refused) {
            return ConnectionOutcome.fail(ConnectionFailureReason.ROLLOVER_REFUSED,
                    "The peer closed the connection instead of proving a newer transport key.");
        }
        if (!peerBinding.header().author().id().equals(expectedRemoteIdentity)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.IDENTITY_MISMATCH,
                    "Expected " + expectedRemoteIdentity + " but the peer's binding was authored by "
                            + peerBinding.header().author().id());
        }
        ConnectionOutcome verdict = validateBindingAndAuthorize(socket, context, peerBinding, now, pinned);
        if (!verdict.authenticated()) {
            return verdict;
        }
        if (verdict.remoteBinding().transportKey().equals(pinned.transportKey())) {
            return ConnectionOutcome.fail(ConnectionFailureReason.ROLLOVER_REFUSED,
                    "The peer presented the pinned key; there is nothing to roll over.");
        }

        SignedEnvelope ownBinding = context.bindingCache().get(context.localIdentity(), context.localTransport(),
                expectedRemoteIdentity, context.writerEpoch(), now);
        HandshakeIo.writeEnvelopeFrame(out, ownBinding);
        return verdict;
    }

    /** TLS server role: accepts any structurally valid caller and learns their claimed identity from the exchange. */
    static ConnectionOutcome accept(SSLSocket socket, Context context, Instant now) throws IOException {
        socket.startHandshake();
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        HandshakeIo.writeHello(out, SUPPORTED_MAJOR_VERSIONS);

        HandshakeIo.Hello callerHello = HandshakeIo.readHelloFrame(in);
        if (callerHello.rolloverRequested()) {
            return acceptRollover(socket, context, callerHello, out, in, now);
        }
        SignedEnvelope callerBinding = HandshakeIo.readEnvelopeFrame(in);
        if (!hasOverlap(callerHello.versions(), SUPPORTED_MAJOR_VERSIONS)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.UNSUPPORTED_VERSION,
                    "Caller supports major versions " + callerHello.versions() + ", we support " + SUPPORTED_MAJOR_VERSIONS);
        }

        ConnectionOutcome callerVerdict = validateBindingAndAuthorize(socket, context, callerBinding, now, null);
        if (!callerVerdict.authenticated()) {
            return callerVerdict;
        }

        SignedEnvelope ownBinding = context.bindingCache().get(context.localIdentity(), context.localTransport(),
                callerVerdict.remoteIdentityId(), context.writerEpoch(), now);
        HandshakeIo.writeEnvelopeFrame(out, ownBinding);
        return callerVerdict;
    }

    /**
     * Server side of the rollover handshake: the caller's pinned key for us was refused, so it asked us to
     * go first. We only do so for a caller whose <em>TLS-proven</em> client key is the key we have pinned
     * for a paired contact (that proof of possession, not any claim, is what tells us whom to address); an
     * unrecognized key gets the connection closed with nothing disclosed. Whatever the caller then sends is
     * validated exactly like a normal inbound binding, and must be authored by that same contact.
     */
    private static ConnectionOutcome acceptRollover(SSLSocket socket, Context context, HandshakeIo.Hello callerHello,
                                                    OutputStream out, InputStream in, Instant now) throws IOException {
        if (!hasOverlap(callerHello.versions(), SUPPORTED_MAJOR_VERSIONS)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.UNSUPPORTED_VERSION,
                    "Caller supports major versions " + callerHello.versions() + ", we support " + SUPPORTED_MAJOR_VERSIONS);
        }
        IdentityKey callerTransportKey = new IdentityKey(rawPeerCertificateKey(socket));
        java.util.Optional<IdentityId> identified = context.contactLookup().pairedContactPinnedTo(callerTransportKey);
        if (identified.isEmpty()
                || context.contactLookup().statusOf(identified.get()) != KnownContactLookup.Status.PAIRED) {
            return ConnectionOutcome.fail(ConnectionFailureReason.ROLLOVER_REFUSED,
                    "Rollover requested by a TLS client key that is not pinned for any paired contact.");
        }
        IdentityId caller = identified.get();

        SignedEnvelope ownBinding = context.bindingCache().get(context.localIdentity(), context.localTransport(),
                caller, context.writerEpoch(), now);
        HandshakeIo.writeEnvelopeFrame(out, ownBinding);

        SignedEnvelope callerBinding = HandshakeIo.readEnvelopeFrame(in);
        if (!callerBinding.header().author().id().equals(caller)) {
            return ConnectionOutcome.fail(ConnectionFailureReason.IDENTITY_MISMATCH,
                    "The rollover caller's key is pinned for " + caller + " but its binding was authored by "
                            + callerBinding.header().author().id());
        }
        return validateBindingAndAuthorize(socket, context, callerBinding, now, null);
    }

    /**
     * @param explicitPin the pin to compare against, or {@code null} to use whatever {@code contactLookup}
     *                    currently pins for the author (a first-ever contact simply has none)
     */
    private static ConnectionOutcome validateBindingAndAuthorize(SSLSocket socket, Context context, SignedEnvelope bindingEnvelope,
                                                        Instant now, PinnedBinding explicitPin) throws IOException {
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

        // Staleness / rollover rule: the key we pin may only ever move forward. The same key is always
        // fine (a plain reconnect or a validity refresh); a different key is acceptable only when its
        // identity-signed binding is strictly newer than the pinned one, so an old binding captured
        // before a rotation (together with a since-compromised old key) cannot roll a contact back.
        PinnedBinding pin = explicitPin != null ? explicitPin
                : context.contactLookup().pinnedBindingOf(claimedAuthorId).orElse(null);
        if (pin != null && !pin.transportKey().equals(binding.transportKey())
                && !binding.validFrom().isAfter(pin.validFrom())) {
            return ConnectionOutcome.fail(ConnectionFailureReason.STALE_BINDING,
                    "The IDENTITY_BINDING (valid from " + binding.validFrom() + ") is not newer than the pinned key's binding ("
                            + pin.validFrom() + ").");
        }

        return ConnectionOutcome.ok(claimedAuthorId, binding);
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

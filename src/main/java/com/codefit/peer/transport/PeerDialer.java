package com.codefit.peer.transport;

import com.codefit.peer.protocol.IdentityId;
import com.codefit.peer.protocol.IdentityKey;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.security.cert.CertificateException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The outbound side of #182's transport: bounded dialing to a specific pinned peer address, with
 * backoff, jitter, an attempt cap, and cooperative cancellation (#182: "Bound all dialing... Retry with
 * backoff/jitter/caps and allow cancellation"). Never contacts anything but the address the caller
 * supplies — no bootstrap list, no fallback address, no DNS.
 */
final class PeerDialer {
    private static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 8_000;
    private static final int DEFAULT_HANDSHAKE_TIMEOUT_MILLIS = 15_000;

    /** Failure reasons where retrying the same address again is pointless: nothing about the network changes the outcome. */
    private static final Set<ConnectionFailureReason> NON_RETRYABLE = Set.of(
            ConnectionFailureReason.WRONG_PIN, ConnectionFailureReason.IDENTITY_MISMATCH,
            ConnectionFailureReason.UNKNOWN_IDENTITY, ConnectionFailureReason.NOT_PAIRED,
            ConnectionFailureReason.UNSUPPORTED_VERSION, ConnectionFailureReason.BINDING_KEY_MISMATCH,
            ConnectionFailureReason.BINDING_EXPIRED, ConnectionFailureReason.BINDING_NOT_YET_VALID,
            ConnectionFailureReason.MALFORMED_FRAME, ConnectionFailureReason.OVERSIZED_FRAME,
            ConnectionFailureReason.ENVELOPE_REJECTED, ConnectionFailureReason.STALE_BINDING,
            ConnectionFailureReason.ROLLOVER_REFUSED);

    private PeerDialer() {
    }

    /** One connection attempt. Never throws for an ordinary network/protocol failure; {@code result} says why. */
    static DialOutcome dialOnce(TransportIdentity localTransport, PeerSession.Context context, PeerAddress address,
                                 IdentityId expectedRemoteIdentity, IdentityKey expectedTransportKey, Instant now) {
        return dialOnce(localTransport, context, address, expectedRemoteIdentity, expectedTransportKey, now,
                DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    static DialOutcome dialOnce(TransportIdentity localTransport, PeerSession.Context context, PeerAddress address,
                                 IdentityId expectedRemoteIdentity, IdentityKey expectedTransportKey, Instant now,
                                 int connectTimeoutMillis, int handshakeTimeoutMillis) {
        SSLContext tls = TlsContexts.forDialer(localTransport, expectedTransportKey);
        return attempt(tls, address, connectTimeoutMillis, handshakeTimeoutMillis,
                socket -> PeerSession.dial(socket, context, expectedRemoteIdentity, now));
    }

    /**
     * One transport-key <em>rollover</em> attempt, made only after a pinned dial was refused with
     * {@link ConnectionFailureReason#WRONG_PIN}. TLS here accepts any structurally valid certificate (the
     * pin is exactly what just failed), so the connection is trusted only if the peer then proves, with an
     * identity-signed binding strictly newer than {@code pinned}, that its identity authorizes the key it
     * presented; see {@link PeerSession#dialForRollover}. Never retried: a failure means the peer holds no
     * valid newer authorization and the old pin stays exactly as it was.
     */
    static DialOutcome rolloverOnce(TransportIdentity localTransport, PeerSession.Context context, PeerAddress address,
                                     IdentityId expectedRemoteIdentity, PinnedBinding pinned, Instant now) {
        return rolloverOnce(localTransport, context, address, expectedRemoteIdentity, pinned, now,
                DEFAULT_CONNECT_TIMEOUT_MILLIS, DEFAULT_HANDSHAKE_TIMEOUT_MILLIS);
    }

    static DialOutcome rolloverOnce(TransportIdentity localTransport, PeerSession.Context context, PeerAddress address,
                                     IdentityId expectedRemoteIdentity, PinnedBinding pinned, Instant now,
                                     int connectTimeoutMillis, int handshakeTimeoutMillis) {
        SSLContext tls = TlsContexts.forRolloverDialer(localTransport);
        return attempt(tls, address, connectTimeoutMillis, handshakeTimeoutMillis,
                socket -> PeerSession.dialForRollover(socket, context, expectedRemoteIdentity, pinned, now));
    }

    @FunctionalInterface
    private interface SessionAction {
        ConnectionOutcome run(SSLSocket socket) throws IOException;
    }

    private static DialOutcome attempt(SSLContext tls, PeerAddress address, int connectTimeoutMillis,
                                        int handshakeTimeoutMillis, SessionAction action) {
        SSLSocket socket = null;
        try {
            socket = (SSLSocket) tls.getSocketFactory().createSocket();
            socket.connect(new InetSocketAddress(address.toInetAddress(), address.port()), connectTimeoutMillis);
            TlsContexts.hardenSocket(socket, false);
            socket.setSoTimeout(handshakeTimeoutMillis);
            ConnectionOutcome result = action.run(socket);
            if (result.authenticated()) {
                socket.setSoTimeout(0);
                return new DialOutcome(result, new PeerConnection(socket, result.remoteIdentityId(), Instant.now()));
            }
            closeQuietly(socket);
            return new DialOutcome(result, null);
        } catch (SocketTimeoutException e) {
            closeQuietly(socket);
            return new DialOutcome(ConnectionOutcome.fail(ConnectionFailureReason.CONNECT_TIMEOUT, e.getMessage()), null);
        } catch (ConnectException e) {
            closeQuietly(socket);
            return new DialOutcome(ConnectionOutcome.fail(ConnectionFailureReason.CONNECTION_REFUSED, e.getMessage()), null);
        } catch (NoRouteToHostException e) {
            closeQuietly(socket);
            return new DialOutcome(ConnectionOutcome.fail(ConnectionFailureReason.NETWORK_UNREACHABLE, e.getMessage()), null);
        } catch (SSLHandshakeException e) {
            closeQuietly(socket);
            ConnectionFailureReason reason = containsCertificateException(e)
                    ? ConnectionFailureReason.WRONG_PIN : ConnectionFailureReason.TLS_HANDSHAKE_FAILED;
            return new DialOutcome(ConnectionOutcome.fail(reason, e.getMessage()), null);
        } catch (TransportProtocolException e) {
            closeQuietly(socket);
            return new DialOutcome(ConnectionOutcome.fail(e.reason(), e.getMessage()), null);
        } catch (IOException e) {
            closeQuietly(socket);
            return new DialOutcome(ConnectionOutcome.fail(ConnectionFailureReason.TLS_HANDSHAKE_FAILED, e.getMessage()), null);
        }
    }

    private static void closeQuietly(SSLSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // discarding regardless
        }
    }

    /**
     * Dials with bounded exponential backoff/jitter until it authenticates, exhausts
     * {@code policy.maxAttempts()}, hits a non-retryable rejection, or {@code cancelled} flips true. The
     * caller polls {@code cancelled} between attempts and during each backoff sleep; a cancellation mid
     * sleep returns promptly rather than waiting out the remaining delay.
     */
    static DialOutcome dialWithRetry(TransportIdentity localTransport, PeerSession.Context context, PeerAddress address,
                                      IdentityId expectedRemoteIdentity, IdentityKey expectedTransportKey,
                                      RetryPolicy policy, AtomicBoolean cancelled, Consumer<ConnectionEvent> onEvent) {
        DialOutcome last = new DialOutcome(ConnectionOutcome.fail(ConnectionFailureReason.CANCELLED, "Never attempted."), null);
        for (int attempt = 1; attempt <= policy.maxAttempts(); attempt++) {
            if (cancelled.get()) {
                return new DialOutcome(ConnectionOutcome.fail(ConnectionFailureReason.CANCELLED, "Cancelled before attempt " + attempt), null);
            }
            notify(onEvent, expectedRemoteIdentity, ConnectionState.CONNECTING, null, null);
            last = dialOnce(localTransport, context, address, expectedRemoteIdentity, expectedTransportKey, Instant.now());
            if (last.result().authenticated()) {
                return last;
            }
            if (NON_RETRYABLE.contains(last.result().failureReason())) {
                notify(onEvent, expectedRemoteIdentity, ConnectionState.REJECTED, last.result().failureReason(), last.result().detail());
                return last;
            }
            if (attempt == policy.maxAttempts()) {
                notify(onEvent, expectedRemoteIdentity, ConnectionState.UNREACHABLE, last.result().failureReason(), last.result().detail());
                return last;
            }
            notify(onEvent, expectedRemoteIdentity, ConnectionState.RETRY_WAITING, last.result().failureReason(), last.result().detail());
            if (!sleepCancellable(policy.delayForAttempt(attempt, ThreadLocalRandom.current()), cancelled)) {
                return new DialOutcome(ConnectionOutcome.fail(ConnectionFailureReason.CANCELLED, "Cancelled during backoff."), null);
            }
        }
        return last;
    }

    private static void notify(Consumer<ConnectionEvent> onEvent, IdentityId remote, ConnectionState state,
                                ConnectionFailureReason reason, String detail) {
        if (onEvent != null) {
            onEvent.accept(reason == null ? ConnectionEvent.of(remote, state) : ConnectionEvent.failed(remote, state, reason, detail));
        }
    }

    /** Sleeps in short slices so a cancellation flag flips take effect promptly; returns false if cancelled. */
    private static boolean sleepCancellable(java.time.Duration duration, AtomicBoolean cancelled) {
        long remainingMillis = duration.toMillis();
        long sliceMillis = 200;
        while (remainingMillis > 0) {
            if (cancelled.get()) {
                return false;
            }
            long sleepFor = Math.min(sliceMillis, remainingMillis);
            try {
                Thread.sleep(sleepFor);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remainingMillis -= sleepFor;
        }
        return !cancelled.get();
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

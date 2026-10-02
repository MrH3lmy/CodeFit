package com.codefit.peer.transport;

import com.codefit.peer.protocol.EnvelopeCodec;
import com.codefit.peer.protocol.ProtocolException;
import com.codefit.peer.protocol.ProtocolVersion;
import com.codefit.peer.protocol.SignedEnvelope;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Bounded, blocking read/write helpers for the two byte structures that travel over one TLS-protected
 * TCP stream in #182: the small transport-level version {@code hello} (distinct from and sent before
 * any protocol v1 envelope, matching {@code docs/p2p/protocol-v1.md} §12: "the TLS session carries an
 * initial hello exchange... That exchange is transport-level and does not change these envelope
 * bytes"), and protocol v1 envelope frames themselves, which reuse {@link EnvelopeCodec} unchanged.
 * Every read checks a length against a fixed bound before allocating or reading it, mirroring the
 * protocol codec's own rule.
 */
final class HandshakeIo {
    private static final byte[] HELLO_MAGIC = {'C', 'F', 'H', '1'};
    /**
     * Same layout as {@link #HELLO_MAGIC}, sent only by a dialer whose pinned key was refused by the
     * listener's certificate and which is asking the peer to prove a newer, identity-authorized key
     * <em>before</em> the dialer discloses anything (transport-v1 §9).
     */
    private static final byte[] ROLLOVER_HELLO_MAGIC = {'C', 'F', 'R', '1'};
    private static final int MAX_SUPPORTED_VERSIONS = 8;

    private HandshakeIo() {
    }

    /** A parsed transport hello: the peer's supported major versions and whether it asked for a rollover proof first. */
    record Hello(List<Integer> versions, boolean rolloverRequested) {
    }

    static void writeHello(OutputStream out, List<Integer> supportedMajorVersions) throws IOException {
        writeHello(out, supportedMajorVersions, false);
    }

    static void writeHello(OutputStream out, List<Integer> supportedMajorVersions, boolean rolloverRequested) throws IOException {
        if (supportedMajorVersions.isEmpty() || supportedMajorVersions.size() > MAX_SUPPORTED_VERSIONS) {
            throw new IllegalArgumentException("supportedMajorVersions must have 1.." + MAX_SUPPORTED_VERSIONS + " entries.");
        }
        byte[] frame = new byte[HELLO_MAGIC.length + 1 + supportedMajorVersions.size()];
        System.arraycopy(rolloverRequested ? ROLLOVER_HELLO_MAGIC : HELLO_MAGIC, 0, frame, 0, HELLO_MAGIC.length);
        frame[HELLO_MAGIC.length] = (byte) supportedMajorVersions.size();
        for (int i = 0; i < supportedMajorVersions.size(); i++) {
            int version = supportedMajorVersions.get(i);
            if (version < 0 || version > 0xFF) {
                throw new IllegalArgumentException("Major version out of range: " + version);
            }
            frame[HELLO_MAGIC.length + 1 + i] = (byte) version;
        }
        out.write(frame);
        out.flush();
    }

    /** Reads a hello and returns only its versions; a rollover request is reported as malformed here. */
    static List<Integer> readHello(InputStream in) throws IOException {
        Hello hello = readHelloFrame(in);
        if (hello.rolloverRequested()) {
            throw new TransportProtocolException(ConnectionFailureReason.MALFORMED_FRAME, "Unexpected rollover hello.");
        }
        return hello.versions();
    }

    static Hello readHelloFrame(InputStream in) throws IOException {
        byte[] magicAndCount = readExactly(in, HELLO_MAGIC.length + 1);
        boolean rollover;
        if (java.util.Arrays.equals(java.util.Arrays.copyOf(magicAndCount, HELLO_MAGIC.length), HELLO_MAGIC)) {
            rollover = false;
        } else if (java.util.Arrays.equals(java.util.Arrays.copyOf(magicAndCount, HELLO_MAGIC.length), ROLLOVER_HELLO_MAGIC)) {
            rollover = true;
        } else {
            throw new TransportProtocolException(ConnectionFailureReason.MALFORMED_FRAME, "Bad hello magic.");
        }
        int count = magicAndCount[HELLO_MAGIC.length] & 0xFF;
        if (count == 0 || count > MAX_SUPPORTED_VERSIONS) {
            throw new TransportProtocolException(ConnectionFailureReason.MALFORMED_FRAME,
                    "Hello version count out of bounds: " + count);
        }
        byte[] versions = readExactly(in, count);
        List<Integer> result = new ArrayList<>(count);
        for (byte version : versions) {
            result.add(version & 0xFF);
        }
        return new Hello(result, rollover);
    }

    /** Writes one complete protocol v1 frame (header + payload) as produced by {@link EnvelopeCodec#encodeFrame}. */
    static void writeEnvelopeFrame(OutputStream out, SignedEnvelope envelope) throws IOException {
        out.write(EnvelopeCodec.encodeFrame(envelope));
        out.flush();
    }

    /**
     * Reads and verifies exactly one protocol v1 frame: the fixed 8-byte header is read and validated
     * (magic, major version, payload bound) <em>before</em> the payload is allocated or read, exactly as
     * {@code docs/p2p/protocol-v1.md} §3 requires of a transport.
     */
    static SignedEnvelope readEnvelopeFrame(InputStream in) throws IOException {
        byte[] header = readExactly(in, ProtocolVersion.FRAME_HEADER_BYTES);
        int payloadLength;
        try {
            payloadLength = EnvelopeCodec.payloadLength(header);
        } catch (ProtocolException e) {
            throw translate(e);
        }
        byte[] payload = readExactly(in, payloadLength);
        byte[] frame = new byte[header.length + payload.length];
        System.arraycopy(header, 0, frame, 0, header.length);
        System.arraycopy(payload, 0, frame, header.length, payload.length);
        try {
            return EnvelopeCodec.decodeFrame(frame);
        } catch (ProtocolException e) {
            throw translate(e);
        }
    }

    private static TransportProtocolException translate(ProtocolException e) {
        ConnectionFailureReason reason = switch (e.reason()) {
            case OVERSIZED -> ConnectionFailureReason.OVERSIZED_FRAME;
            case UNSUPPORTED_VERSION -> ConnectionFailureReason.UNSUPPORTED_VERSION;
            default -> ConnectionFailureReason.MALFORMED_FRAME;
        };
        return new TransportProtocolException(reason, e.getMessage(), e);
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] buffer = new byte[length];
        int total = 0;
        while (total < length) {
            int read = in.read(buffer, total, length - total);
            if (read < 0) {
                throw new EOFException("Connection closed after " + total + " of " + length + " expected bytes.");
            }
            total += read;
        }
        return buffer;
    }
}

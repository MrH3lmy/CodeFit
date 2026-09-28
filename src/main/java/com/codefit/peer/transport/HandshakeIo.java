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
    private static final int MAX_SUPPORTED_VERSIONS = 8;

    private HandshakeIo() {
    }

    static void writeHello(OutputStream out, List<Integer> supportedMajorVersions) throws IOException {
        if (supportedMajorVersions.isEmpty() || supportedMajorVersions.size() > MAX_SUPPORTED_VERSIONS) {
            throw new IllegalArgumentException("supportedMajorVersions must have 1.." + MAX_SUPPORTED_VERSIONS + " entries.");
        }
        byte[] frame = new byte[HELLO_MAGIC.length + 1 + supportedMajorVersions.size()];
        System.arraycopy(HELLO_MAGIC, 0, frame, 0, HELLO_MAGIC.length);
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

    static List<Integer> readHello(InputStream in) throws IOException {
        byte[] magicAndCount = readExactly(in, HELLO_MAGIC.length + 1);
        for (int i = 0; i < HELLO_MAGIC.length; i++) {
            if (magicAndCount[i] != HELLO_MAGIC[i]) {
                throw new TransportProtocolException(ConnectionFailureReason.MALFORMED_FRAME, "Bad hello magic.");
            }
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
        return result;
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

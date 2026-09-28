package com.codefit.peer.transport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit-level coverage (no sockets needed) for #182's bounded, "check-before-allocate" frame handling:
 * malformed and oversized hello/envelope frames must be rejected before any oversized read is attempted.
 */
class HandshakeIoTest {

    @Test
    void roundTripsAHello() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HandshakeIo.writeHello(out, List.of(1, 2, 3));
        List<Integer> read = HandshakeIo.readHello(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(List.of(1, 2, 3), read);
    }

    @Test
    void rejectsHelloWithBadMagic() {
        byte[] bogus = {'X', 'X', 'X', 'X', 1, 1};
        TransportProtocolException failure = assertThrows(TransportProtocolException.class,
                () -> HandshakeIo.readHello(new ByteArrayInputStream(bogus)));
        assertEquals(ConnectionFailureReason.MALFORMED_FRAME, failure.reason());
    }

    @Test
    void rejectsHelloWithZeroVersionCount() {
        byte[] bogus = {'C', 'F', 'H', '1', 0};
        TransportProtocolException failure = assertThrows(TransportProtocolException.class,
                () -> HandshakeIo.readHello(new ByteArrayInputStream(bogus)));
        assertEquals(ConnectionFailureReason.MALFORMED_FRAME, failure.reason());
    }

    @Test
    void rejectsHelloWithOversizedVersionCountBeforeReadingItsBody() {
        // Declares 250 versions but supplies none of them: a correct reader must reject based on the
        // declared count alone, before trying to read 250 bytes that are not there.
        byte[] bogus = {'C', 'F', 'H', '1', (byte) 250};
        TransportProtocolException failure = assertThrows(TransportProtocolException.class,
                () -> HandshakeIo.readHello(new ByteArrayInputStream(bogus)));
        assertEquals(ConnectionFailureReason.MALFORMED_FRAME, failure.reason());
    }

    @Test
    void rejectsATruncatedHello() {
        byte[] truncated = {'C', 'F', 'H'};
        assertThrows(java.io.EOFException.class, () -> HandshakeIo.readHello(new ByteArrayInputStream(truncated)));
    }

    @Test
    void rejectsAnEnvelopeFrameDeclaringAnOversizedPayloadWithoutReadingIt() {
        // Header claims a payload far beyond the protocol's 64 KiB bound; a correct reader must reject
        // based on the 8-byte header alone (protocol-v1.md §3), never attempt to allocate/read that much.
        byte[] header = {'C', 'F', 'P', 0x01, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        TransportProtocolException failure = assertThrows(TransportProtocolException.class,
                () -> HandshakeIo.readEnvelopeFrame(new ByteArrayInputStream(header)));
        assertEquals(ConnectionFailureReason.OVERSIZED_FRAME, failure.reason());
    }

    @Test
    void rejectsAnEnvelopeFrameWithBadMagic() {
        byte[] header = {'X', 'X', 'X', 0x01, 0, 0, 0, 0};
        TransportProtocolException failure = assertThrows(TransportProtocolException.class,
                () -> HandshakeIo.readEnvelopeFrame(new ByteArrayInputStream(header)));
        assertEquals(ConnectionFailureReason.MALFORMED_FRAME, failure.reason());
    }

    @Test
    void rejectsAnEnvelopeFrameWithAnUnsupportedMajorVersion() {
        byte[] header = {'C', 'F', 'P', 0x02, 0, 0, 0, 0};
        TransportProtocolException failure = assertThrows(TransportProtocolException.class,
                () -> HandshakeIo.readEnvelopeFrame(new ByteArrayInputStream(header)));
        assertEquals(ConnectionFailureReason.UNSUPPORTED_VERSION, failure.reason());
    }

    @Test
    void rejectsATruncatedEnvelopeFrameBody() {
        // Declares a small, legal payload length but supplies fewer bytes than that.
        byte[] header = {'C', 'F', 'P', 0x01, 0, 0, 0, 10};
        assertThrows(java.io.EOFException.class, () -> HandshakeIo.readEnvelopeFrame(new ByteArrayInputStream(header)));
    }
}

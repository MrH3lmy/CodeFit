package com.codefit.peer.protocol;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden frames pin the v1 wire format: encoding a fixture must reproduce the committed bytes exactly
 * (Ed25519 is deterministic), and decoding the committed bytes must yield the same typed envelope.
 */
class GoldenFixtureTest {

    @TestFactory
    Stream<DynamicTest> everyFixtureEncodesToItsGoldenBytesAndDecodesBack() {
        return ProtocolFixtures.all().entrySet().stream().map(fixture -> DynamicTest.dynamicTest(fixture.getKey(), () -> {
            byte[] golden = ProtocolFixtures.readGolden(fixture.getKey());
            assertArrayEquals(golden, EnvelopeCodec.encodeFrame(fixture.getValue()), "canonical bytes changed");

            SignedEnvelope decoded = EnvelopeCodec.decodeFrame(golden);
            assertEquals(fixture.getValue(), decoded);
            assertTrue(decoded.verifySignature());
            assertArrayEquals(golden, EnvelopeCodec.encodeFrame(decoded), "decode/encode must be the identity");
            assertEquals(fixture.getValue().messageId(), decoded.messageId());
        }));
    }

    @Test
    void goldenDirectoryContainsExactlyTheFixtureSet() throws IOException {
        Set<String> onDisk;
        try (Stream<java.nio.file.Path> files = Files.list(ProtocolFixtures.GOLDEN_DIR)) {
            onDisk = files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".frame.hex"))
                    .map(name -> name.substring(0, name.length() - ".frame.hex".length()))
                    .collect(Collectors.toSet());
        }
        assertEquals(ProtocolFixtures.all().keySet(), onDisk);
    }

    @Test
    void decodingIsDeterministicAcrossRepeatedParses() {
        for (Map.Entry<String, SignedEnvelope> fixture : ProtocolFixtures.all().entrySet()) {
            byte[] golden = ProtocolFixtures.readGolden(fixture.getKey());
            assertEquals(EnvelopeCodec.decodeFrame(golden), EnvelopeCodec.decodeFrame(golden.clone()));
        }
    }

    @Test
    void messageIdIsSha256OfTheSignedPayload() {
        SignedEnvelope envelope = ProtocolFixtures.all().get("tombstone");
        byte[] frame = EnvelopeCodec.encodeFrame(envelope);
        byte[] payload = java.util.Arrays.copyOfRange(frame, ProtocolVersion.FRAME_HEADER_BYTES, frame.length);
        assertArrayEquals(ProtocolBytes.sha256(payload), envelope.messageId().bytes());
    }
}

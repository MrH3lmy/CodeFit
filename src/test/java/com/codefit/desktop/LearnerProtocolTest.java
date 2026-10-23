package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

class LearnerProtocolTest {
    private final String id = UUID.randomUUID().toString(),
            session = UUID.randomUUID().toString(),
            profile = "a".repeat(64);

    private String request() {
        return "{\"v\":3,\"id\":\""
                + id
                + "\",\"method\":\"learner.overview.read\",\"profileId\":\""
                + profile
                + "\",\"sessionId\":\""
                + session
                + "\"}";
    }

    @Test
    void onlyExactVersionedReadCapabilityIsAccepted() throws Exception {
        assertEquals(
                "learner.skills.read",
                LearnerProtocol.parse(
                                request()
                                        .replace("learner.overview.read", "learner.skills.read")
                                        .getBytes(StandardCharsets.UTF_8))
                        .method());
        assertEquals(
                profile,
                LearnerProtocol.parse(request().getBytes(StandardCharsets.UTF_8)).profileId());
        for (String invalid :
                List.of(
                        request().replace("\"v\":3", "\"v\":2"),
                        request().replace("learner.overview.read", "decks.create"),
                        request().replace("profileId", "path"),
                        request().replace("\"v\":3", "\"v\":3,\"v\":3"),
                        request() + "{}",
                        request().replace(profile, "bad"),
                        request().replace("\"v\":3", "\"v\":\"3\""),
                        "\ufeff" + request()))
            assertThrows(
                    IOException.class,
                    () -> LearnerProtocol.parse(invalid.getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                IOException.class,
                () -> LearnerProtocol.parse(request().getBytes(StandardCharsets.UTF_16BE)));
        assertThrows(IOException.class, () -> LearnerProtocol.parse(new byte[] {(byte) 255}));
        assertThrows(IOException.class, () -> LearnerProtocol.parse(new byte[1025]));
    }

    @Test
    void framingRequiresCompleteBoundedUtf8Frame() throws Exception {
        assertNull(LearnerProtocol.readFrame(new ByteArrayInputStream(new byte[0])));
        assertArrayEquals(
                request().getBytes(StandardCharsets.UTF_8),
                LearnerProtocol.readFrame(
                        new ByteArrayInputStream(
                                (request() + "\n").getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                IOException.class,
                () ->
                        LearnerProtocol.readFrame(
                                new ByteArrayInputStream(
                                        request().getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                IOException.class,
                () -> LearnerProtocol.readFrame(new ByteArrayInputStream(new byte[1025])));
    }

    @Test
    void unavailableIsExplicitAndNoDomainRecordsAreSerialized() throws Exception {
        var value =
                new LearnerReadProjection.Overview(
                        profile,
                        session,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        null,
                        null,
                        null,
                        0,
                        0,
                        "2026-01-01");
        String json = new String(LearnerProtocol.result(id, value), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"xp\":null"));
        assertTrue(json.contains("\"displayName\":null"));
        assertTrue(json.contains("\"recommendations\":{\"status\":\"unavailable\"}"));
        assertFalse(json.contains("entitlement"));
        assertFalse(json.contains("answer"));
        assertFalse(json.contains("inputs"));
    }
}

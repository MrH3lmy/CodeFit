package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

class ApplicationProtocolTest {
    static final String ID = "52a766f0-afc2-48e6-8d60-111c5f8cf814";
    static final String READ =
            "{\"v\":2,\"id\":\"" + ID + "\",\"method\":\"state.read\",\"afterId\":0}";

    @Test
    void exactTypedAllowlistAndUtf8Only() throws Exception {
        assertEquals(
                "state.read",
                ApplicationProtocol.parse(READ.getBytes(StandardCharsets.UTF_8)).method());
        for (String bad :
                List.of(
                        READ.replace("2,", "1,"),
                        READ.replace("0}", "0.0}"),
                        READ.replace("0}", "-1}"),
                        READ.replace("0}", "9007199254740992}"),
                        READ.replace("}", ",\"id\":\"" + ID + "\"}"),
                        READ.replace("}", ",\"sql\":\"select 1\"}"),
                        READ.replace("state.read", "PlayerSystemService.overview"),
                        READ + "{}",
                        "[]",
                        "null",
                        "{}",
                        READ.replace("0}", "null}"),
                        READ.replace("0}", "[]}"),
                        READ.replace("state.read", "decks.create"))) {
            assertThrows(
                    IOException.class,
                    () -> ApplicationProtocol.parse(bad.getBytes(StandardCharsets.UTF_8)),
                    bad);
        }
        for (var charset :
                List.of(
                        StandardCharsets.UTF_16,
                        StandardCharsets.UTF_16BE,
                        StandardCharsets.UTF_16LE))
            assertThrows(
                    IOException.class, () -> ApplicationProtocol.parse(READ.getBytes(charset)));
        assertThrows(
                IOException.class,
                () ->
                        ApplicationProtocol.parse(
                                ("\ufeff" + READ).getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> ApplicationProtocol.parse(new byte[] {(byte) 0xff}));
    }

    @Test
    void mutationRequiresProfileIdentityAndStrictBoundedText() throws Exception {
        String create =
                "{\"v\":2,\"id\":\""
                        + ID
                        + "\",\"method\":\"decks.create\",\"profileId\":\""
                        + ID
                        + "\",\"commandId\":\""
                        + ID
                        + "\",\"name\":\"Unicode λ 🚀\",\"description\":\"\"}";
        assertEquals(
                "Unicode λ 🚀",
                ApplicationProtocol.parse(create.getBytes(StandardCharsets.UTF_8)).name());
        for (String name :
                List.of("", " ", "x".repeat(257), "\\ud800", "\\udc00", "a\\n", "\\u0085"))
            assertThrows(
                    IOException.class,
                    () ->
                            ApplicationProtocol.parse(
                                    create.replace("Unicode λ 🚀", name)
                                            .getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                IOException.class,
                () ->
                        ApplicationProtocol.parse(
                                create.replace("profileId", "path")
                                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void boundedFramingNeverAcceptsPartialEof() throws Exception {
        assertNull(ApplicationProtocol.readFrame(new ByteArrayInputStream(new byte[0])));
        assertArrayEquals(
                READ.getBytes(StandardCharsets.UTF_8),
                ApplicationProtocol.readFrame(
                        new ByteArrayInputStream((READ + "\n").getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                IOException.class,
                () ->
                        ApplicationProtocol.readFrame(
                                new ByteArrayInputStream(READ.getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                IOException.class,
                () ->
                        ApplicationProtocol.readFrame(
                                new ByteArrayInputStream(
                                        new byte[ApplicationProtocol.MAX_REQUEST_BYTES + 1])));
    }
}

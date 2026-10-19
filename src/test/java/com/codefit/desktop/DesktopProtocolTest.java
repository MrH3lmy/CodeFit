package com.codefit.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.codefit.model.Deck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

class DesktopProtocolTest {
    static final String ID = "52a766f0-afc2-48e6-8d60-111c5f8cf814";
    static final String REQUEST = "{\"v\":1,\"id\":\"" + ID + "\",\"method\":\"decks.list\"}";

    @Test
    void acceptsOnlyVersionedCorrelatedRead() throws Exception {
        assertEquals(ID, DesktopProtocol.parse(REQUEST.getBytes(StandardCharsets.UTF_8)).id());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "[]",
                "null",
                "{\"v\":1,\"v\":1}",
                "{\"v\":1.0}",
                "{\"v\":2}",
                "{\"v\":\"1\"}",
                "{\"v\":1}{}",
                "{\"v\":1,}",
                "{\"method\":\"decks.create\"}",
                "{\"method\":\"challenge.show\"}",
                "{\"v\":1,\"sql\":\"SELECT 1\"}"
            })
    void rejectsMalformedOrUnauthorizedRequests(String input) {
        assertThrows(
                IOException.class,
                () -> DesktopProtocol.parse(input.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsExtraFieldsDuplicateIdsInvalidUtf8AndTrailingData() {
        for (String request :
                List.of(
                        REQUEST.replace("}", ",\"path\":\"/tmp/codefit.db\"}"),
                        REQUEST.replace("}", ",\"id\":\"" + ID + "\"}"),
                        REQUEST + "{}",
                        REQUEST.replace(ID, "../../codefit.db"),
                        REQUEST.replace("decks.list", "decks.create"))) {
            assertThrows(
                    IOException.class,
                    () -> DesktopProtocol.parse(request.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(
                IOException.class,
                () ->
                        DesktopProtocol.parse(
                                new byte[] {'{', '"', (byte) 0xff, '"', ':', '1', '}'}));
    }

    @Test
    void boundsFramesAndRequiresNewlineOnNonemptyEof() throws Exception {
        assertNull(DesktopProtocol.readFrame(new ByteArrayInputStream(new byte[0])));
        assertArrayEquals(
                REQUEST.getBytes(StandardCharsets.UTF_8),
                DesktopProtocol.readFrame(
                        new ByteArrayInputStream(
                                (REQUEST + "\n").getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                IOException.class,
                () ->
                        DesktopProtocol.readFrame(
                                new ByteArrayInputStream(
                                        REQUEST.getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                IOException.class,
                () ->
                        DesktopProtocol.readFrame(
                                new ByteArrayInputStream(
                                        "x".repeat(1025).getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void responseEscapesContentAndNeverSerializesDomainObjectsWholesale() throws Exception {
        Deck deck = new Deck(7, "A \"quoted\" deck", "Unicode λ\nnew line", null);
        String response =
                new String(DesktopProtocol.decks(ID, List.of(deck)), StandardCharsets.UTF_8);
        assertTrue(response.contains("A \\\"quoted\\\" deck"));
        assertTrue(response.contains("Unicode λ\\nnew line"));
        assertFalse(response.contains("createdAt"));
        assertFalse(response.contains("back"));
        assertTrue(response.contains("\"readOnly\":true"));
        assertThrows(
                IOException.class,
                () -> DesktopProtocol.decks(ID, java.util.Collections.nCopies(65, deck)));
        assertThrows(
                IOException.class,
                () -> DesktopProtocol.decks(ID, List.of(new Deck(1, "x".repeat(257), "", null))));
    }
}

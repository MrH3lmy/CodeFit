package com.codefit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCardSpecCodecTest {

    @Test
    void roundTripsAFullSpec() {
        SqlCardSpec spec = new SqlCardSpec(
                "CREATE TABLE t (\n  id INTEGER PRIMARY KEY\n);",
                "INSERT INTO t VALUES (1);",
                "SELECT id FROM t;",
                null, true, false, 1500);

        String encoded = SqlCardSpecCodec.encode(spec);
        SqlCardSpec decoded = SqlCardSpecCodec.decode(encoded);

        assertEquals(spec, decoded);
    }

    @Test
    void encodedFormHasNoRawNewlinesOrTabs() {
        SqlCardSpec spec = new SqlCardSpec(
                "CREATE TABLE t (\n\tid INTEGER PRIMARY KEY\n);",
                "INSERT INTO t VALUES (1);\nINSERT INTO t VALUES (2);",
                "SELECT id FROM t;",
                null, false, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        String encoded = SqlCardSpecCodec.encode(spec);

        assertFalse(encoded.contains("\n"));
        assertFalse(encoded.contains("\t"));
        assertEquals(spec, SqlCardSpecCodec.decode(encoded));
    }

    @Test
    void roundTripsAnExpectedErrorSpecWithoutAReferenceQuery() {
        SqlCardSpec spec = new SqlCardSpec("CREATE TABLE t (id INTEGER);", "", null, "no such column",
                false, false, SqlCardSpec.DEFAULT_TIMEOUT_MILLIS);

        SqlCardSpec decoded = SqlCardSpecCodec.decode(SqlCardSpecCodec.encode(spec));

        assertNull(decoded.referenceQuery());
        assertEquals("no such column", decoded.expectedError());
        assertTrue(decoded.expectsError());
    }

    @Test
    void decodeRejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> SqlCardSpecCodec.decode(""));
        assertThrows(IllegalArgumentException.class, () -> SqlCardSpecCodec.decode(null));
    }

    @Test
    void decodeRejectsPlainTextThatIsNotJson() {
        assertThrows(IllegalArgumentException.class, () -> SqlCardSpecCodec.decode("SELECT 1;"));
    }

    @Test
    void decodeRejectsAConfigWithNeitherReferenceQueryNorExpectedError() {
        String withoutEither = "{\"schema\":\"\",\"seed\":\"\",\"reference\":null,\"expectedError\":null,"
                + "\"orderMatters\":false,\"allowDdl\":false,\"timeoutMillis\":2000}";
        assertThrows(IllegalArgumentException.class, () -> SqlCardSpecCodec.decode(withoutEither));
    }

    @Test
    void decodeToleratesReorderedFields() {
        String reordered = "{\"timeoutMillis\":500,\"reference\":\"SELECT 1;\",\"orderMatters\":true,"
                + "\"schema\":\"\",\"allowDdl\":false,\"seed\":\"\",\"expectedError\":null}";

        SqlCardSpec decoded = SqlCardSpecCodec.decode(reordered);

        assertEquals("SELECT 1;", decoded.referenceQuery());
        assertEquals(500, decoded.timeoutMillis());
        assertTrue(decoded.orderMatters());
    }
}

package org.techhouse.unit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.techhouse.client.ReplyHeader;

public class ReplyHeaderTest {
    @Test
    public void test_reads_the_top_level_status_fields_wherever_they_sit() {
        final var header = ReplyHeader.of("{\"results\":[{\"status\":\"ERROR\",\"type\":\"x\"}],"
                + "\"type\":\"AGGREGATE\",\"status\":\"OK\",\"message\":\"m\",\"errorCode\":null}");

        assertTrue(header.isOk());
        assertEquals("AGGREGATE", header.type());
        assertNull(header.errorCode());
    }

    @Test
    public void test_an_escaped_quote_does_not_end_a_string() {
        final var header = ReplyHeader
                .of("{\"message\":\"say \\\"status\\\":\\\"OK\\\"\",\"status\":\"ERROR\",\"errorCode\":\"409-2\"}");

        assertFalse(header.isOk());
        assertEquals("ERROR", header.status());
        assertEquals("409-2", header.errorCode());
    }

    @Test
    public void test_a_reply_that_is_not_an_object_has_no_status() {
        final var header = ReplyHeader.of("The command is not valid {\"status\":\"OK\"}");

        assertFalse(header.isOk());
        assertNull(header.status());
    }

    @Test
    public void test_bytes_fed_in_chunks_give_the_same_header() {
        final var bytes = "  {\"o\":{\"status\":\"x\"},\"status\":\"OK\",\"type\":\"SAVE\"}"
                .getBytes(StandardCharsets.UTF_8);
        final var header = new ReplyHeader();
        for (var from = 0; from < bytes.length; from += 3) {
            header.accept(bytes, from, Math.min(bytes.length, from + 3));
        }

        assertTrue(header.isOk());
        assertEquals("SAVE", header.type());
    }
}

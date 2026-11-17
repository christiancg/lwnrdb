package org.techhouse.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

public class HttpRequestHeadTest {

    private static HttpRequestHead read(String text, int maxBytes) throws IOException {
        return HttpRequestHead.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)), maxBytes);
    }

    @Test
    public void test_parses_the_request_line_and_headers_case_insensitively() throws IOException {
        final var head = read("GET /metrics?x=1 HTTP/1.1\r\nHost: db\r\nAuthorization:  Bearer t0k3n \r\n\r\n", 1024);

        assertEquals("GET", head.method());
        assertEquals("/metrics", head.path());
        assertEquals("db", head.header("host"));
        assertEquals("Bearer t0k3n", head.header("AUTHORIZATION"));
        assertNull(head.header("Accept"));
    }

    @Test
    public void test_accepts_bare_line_feeds() throws IOException {
        final var head = read("HEAD /metrics HTTP/1.0\nAccept: */*\n\n", 1024);

        assertEquals("HEAD", head.method());
        assertEquals("*/*", head.header("accept"));
    }

    @Test
    public void test_a_head_one_byte_over_the_cap_is_too_large() throws IOException {
        final var text = "GET /metrics HTTP/1.1\r\n\r\n";

        assertEquals("/metrics", read(text, text.length()).path());
        assertThrows(HttpRequestHead.TooLarge.class, () -> read(text, text.length() - 1));
    }

    @Test
    public void test_a_malformed_request_line_is_refused() {
        assertThrows(HttpRequestHead.Malformed.class, () -> read("GET /metrics\r\n\r\n", 1024));
        assertThrows(HttpRequestHead.Malformed.class, () -> read("GET metrics HTTP/1.1\r\n\r\n", 1024));
        assertThrows(HttpRequestHead.Malformed.class, () -> read(" /metrics HTTP/1.1\r\n\r\n", 1024));
        assertThrows(HttpRequestHead.Malformed.class, () -> read("GET /metrics FTP/1\r\n\r\n", 1024));
        assertThrows(HttpRequestHead.Malformed.class, () -> read("\r\n", 1024));
    }

    @Test
    public void test_a_header_without_a_colon_is_refused() {
        assertThrows(HttpRequestHead.Malformed.class, () -> read("GET /metrics HTTP/1.1\r\nnocolon\r\n\r\n", 1024));
        assertThrows(HttpRequestHead.Malformed.class, () -> read("GET /metrics HTTP/1.1\r\n: empty\r\n\r\n", 1024));
    }

    @Test
    public void test_a_connection_ending_before_the_blank_line_is_an_eof() {
        assertThrows(EOFException.class, () -> read("GET /metrics HTTP/1.1\r\nHost: db\r\n", 1024));
        assertThrows(EOFException.class, () -> read("", 1024));
    }
}

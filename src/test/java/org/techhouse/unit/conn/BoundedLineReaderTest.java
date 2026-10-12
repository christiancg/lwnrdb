package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.conn.BoundedLineReader;
import org.techhouse.ex.RequestTooLargeException;
import org.techhouse.test.TestUtils;

public class BoundedLineReaderTest {
    private static final int LIMIT = 1000;

    private static final class ScriptedStream extends InputStream {
        private final Deque<Object> script = new ArrayDeque<>();

        ScriptedStream then(String text) {
            return then(text.getBytes(StandardCharsets.UTF_8));
        }

        ScriptedStream then(byte[] bytes) {
            script.add(bytes);
            return this;
        }

        ScriptedStream thenTimeout() {
            script.add(new SocketTimeoutException("scripted"));
            return this;
        }

        @Override
        public int read() throws IOException {
            final var single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public int read(byte[] target, int offset, int length) throws IOException {
            final var next = script.pollFirst();
            if (next == null) {
                return -1;
            }
            if (next instanceof IOException exception) {
                throw exception;
            }
            final var chunk = (byte[]) next;
            final var served = Math.min(length, chunk.length);
            System.arraycopy(chunk, 0, target, offset, served);
            if (served < chunk.length) {
                script.addFirst(Arrays.copyOfRange(chunk, served, chunk.length));
            }
            return served;
        }
    }

    private static final class EndlessStream extends InputStream {
        private final long delayMillis;
        private final int bytesPerRead;
        private long delivered;

        EndlessStream(long delayMillis, int bytesPerRead) {
            this.delayMillis = delayMillis;
            this.bytesPerRead = bytesPerRead;
        }

        @Override
        public int read() {
            return 'a';
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public int read(byte[] target, int offset, int length) throws IOException {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            final var served = Math.min(length, bytesPerRead);
            Arrays.fill(target, offset, offset + served, (byte) 'a');
            delivered += served;
            return served;
        }
    }

    private static BoundedLineReader reader(InputStream in) {
        return new BoundedLineReader(in, _ -> {
        });
    }

    private static BoundedLineReader reader(String text) {
        return reader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void test_reads_lf_and_crlf_terminated_lines() throws IOException {
        final var reader = reader("one\ntwo\r\nthree\n");

        assertEquals("one", reader.readLine(LIMIT));
        assertEquals("two", reader.readLine(LIMIT));
        assertEquals("three", reader.readLine(LIMIT));
        assertNull(reader.readLine(LIMIT));
    }

    @Test
    public void test_returns_a_final_unterminated_line_then_null() throws IOException {
        final var reader = reader("one\ntail");

        assertEquals("one", reader.readLine(LIMIT));
        assertEquals("tail", reader.readLine(LIMIT));
        assertNull(reader.readLine(LIMIT));
        assertNull(reader.readLine(LIMIT));
        assertEquals("tail", reader("tail\r").readLine(LIMIT));
        assertNull(reader("").readLine(LIMIT));
    }

    @Test
    public void test_a_line_of_exactly_the_limit_is_accepted_and_one_byte_more_is_refused() throws IOException {
        assertEquals("abcde", reader("abcde\n").readLine(5));
        assertEquals("abcde", reader("abcde\r\n").readLine(5));
        assertEquals("abcde", reader("abcde").readLine(5));
        assertEquals("abcé", reader("abcé\n").readLine(5));
        assertThrows(RequestTooLargeException.class, () -> reader("abcdef\n").readLine(5));
        assertThrows(RequestTooLargeException.class, () -> reader("abcdé\n").readLine(5));
        assertThrows(RequestTooLargeException.class, () -> reader("abcdef").readLine(5));
        assertThrows(RequestTooLargeException.class, () -> reader("abcdef\r\n").readLine(5));
    }

    @Test
    public void test_refuses_without_waiting_for_a_terminator() {
        final var stream = new EndlessStream(0, Integer.MAX_VALUE);
        final var reader = reader(stream);

        final var refusal = assertThrows(RequestTooLargeException.class, () -> reader.readLine(64));

        assertTrue(refusal.getMessage().contains("64"), refusal.getMessage());
        assertTrue(stream.delivered <= 64 + 2, "requested " + stream.delivered + " bytes");
    }

    @Test
    public void test_keeps_the_partial_line_across_a_timeout() throws IOException {
        try (var stream = new ScriptedStream()) {
            final var reader = reader(stream.then("{\"ty").thenTimeout().then("pe\":1}\n"));

            assertThrows(SocketTimeoutException.class, () -> reader.readLine(1000, LIMIT));
            assertEquals("{\"type\":1}", reader.readLine(1000, LIMIT));
        }
    }

    @Test
    public void test_keeps_a_split_multibyte_character_across_a_timeout() throws IOException {
        final var accent = "é".getBytes(StandardCharsets.UTF_8);
        try (var stream = new ScriptedStream()) {
            final var reader = reader(stream.then("caf").then(new byte[]{accent[0]}).thenTimeout()
                    .then(new byte[]{accent[1]}).then("!\n"));

            assertThrows(SocketTimeoutException.class, () -> reader.readLine(1000, LIMIT));
            assertEquals("café!", reader.readLine(1000, LIMIT));
        }
    }

    @Test
    public void test_the_budget_is_one_deadline_not_a_per_read_timer() {
        final var timeouts = new ArrayList<Integer>();
        final var reader = new BoundedLineReader(new EndlessStream(5, 1), timeouts::add);

        assertThrows(SocketTimeoutException.class, () -> reader.readLine(80, LIMIT));

        assertTrue(timeouts.size() > 1, timeouts.toString());
        for (var i = 1; i < timeouts.size(); i++) {
            assertTrue(timeouts.get(i) <= timeouts.get(i - 1), timeouts.toString());
        }
        assertTrue(timeouts.stream().allMatch(millis -> millis >= 1 && millis <= 80), timeouts.toString());
    }

    @Test
    public void test_zero_budget_sets_no_timeout() throws IOException {
        final List<Integer> timeouts = new ArrayList<>();
        try (var stream = new ScriptedStream()) {
            final var reader = new BoundedLineReader(stream.then("a\n").then("b\n").then("c\n"), timeouts::add);

            assertEquals("a", reader.readLine(0, LIMIT));
            assertEquals(List.of(0), timeouts);
            assertEquals("b", reader.readLine(5000, LIMIT));
            assertTrue(timeouts.getLast() > 0);
            assertEquals("c", reader.readLine(-1, LIMIT));
            assertEquals(0, timeouts.getLast());
        }
    }

    @Test
    public void test_the_reader_is_unusable_after_a_refusal_and_drops_its_buffer() throws Exception {
        final var reader = reader("aaaaaaaaaa\nok\n");

        assertThrows(RequestTooLargeException.class, () -> reader.readLine(4));

        assertEquals(0, TestUtils.getPrivateField(reader, "buffer", byte[].class).length);
        assertThrows(RequestTooLargeException.class, () -> reader.readLine(LIMIT));
    }

    @Test
    public void test_a_bare_carriage_return_is_not_a_terminator() throws IOException {
        final var reader = reader("a\rb\nc\n");

        assertEquals("a\rb", reader.readLine(LIMIT));
        assertEquals("c", reader.readLine(LIMIT));
    }

    @Test
    public void test_long_lines_grow_the_buffer_and_pipelined_lines_survive_compaction() throws IOException {
        final var line = "x".repeat(20_000);
        final var huge = "y".repeat(100_000);
        final var reader = reader(line + "\n" + line + "\n" + line + "\n" + huge + "\n");

        assertEquals(line, reader.readLine(200_000));
        assertEquals(line, reader.readLine(200_000));
        assertEquals(line, reader.readLine(200_000));
        assertEquals(huge, reader.readLine(200_000));
        assertNull(reader.readLine(200_000));
    }

    @Test
    public void test_a_huge_limit_does_not_overflow() throws IOException {
        assertEquals("ok", reader("ok\n").readLine(Integer.MAX_VALUE));
        assertEquals("ok", reader("ok").readLine(Integer.MAX_VALUE));
    }

    @Test
    public void test_a_lowered_limit_refuses_bytes_already_buffered() throws IOException {
        final var reader = reader("short\n" + "z".repeat(50));

        assertEquals("short", reader.readLine(LIMIT));
        assertThrows(RequestTooLargeException.class, () -> reader.readLine(10));
    }
}

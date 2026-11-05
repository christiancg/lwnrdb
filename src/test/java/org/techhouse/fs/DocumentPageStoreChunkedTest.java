package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.DbEntry;

public class DocumentPageStoreChunkedTest {
    private static final String DB = "chunkDb";
    private static final String COLL = "chunkColl";
    private static final int CHUNK = ChunkedLines.CHUNK_BYTES;

    @TempDir
    File tmp;

    private FilePaths paths;

    @BeforeEach
    public void setUp() throws IOException {
        paths = new FilePaths();
        paths.useDbPath(tmp.getAbsolutePath());
        Files.createDirectories(paths.collectionFolder(DB, COLL).toPath());
    }

    private static String record(String id, int payloadChars) {
        return "{\"_id\":\"" + id + "\",\"payload\":\"" + "x".repeat(payloadChars) + "\"}";
    }

    private static String recordOfLength(String id, int length) {
        return record(id, length - record(id, 0).length());
    }

    private void writePage(byte[] bytes) throws IOException {
        Files.write(paths.collectionPage(DB, COLL, 0).toPath(), bytes);
    }

    private static byte[] bytes(String... parts) {
        final var out = new ByteArrayOutputStream();
        for (final var part : parts) {
            out.writeBytes(part.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private Map<String, DbEntry> read() throws IOException {
        return new DocumentPageStore(paths).readWholeCollectionPage(DB, COLL, 0);
    }

    private static List<String> chunkedLines(byte[] content, int... cuts) {
        final var chunks = new ArrayList<byte[]>();
        var from = 0;
        for (final var cut : cuts) {
            chunks.add(Arrays.copyOfRange(content, from, cut));
            from = cut;
        }
        chunks.add(Arrays.copyOfRange(content, from, content.length));
        final var lines = new ArrayList<String>();
        ChunkedLines.forEachLine(chunks,
                (bytes, start, end) -> lines.add(new String(bytes, start, end - start, StandardCharsets.UTF_8)));
        return lines;
    }

    private static List<String> wholeLines(byte[] content) {
        final var lines = new ArrayList<String>();
        var start = 0;
        while (start < content.length) {
            final var end = FileLocks.lineEnd(content, start);
            lines.add(new String(content, start, end - start, StandardCharsets.UTF_8));
            start = FileLocks.nextLineStart(content, end);
        }
        return lines;
    }

    @Test
    public void test_a_line_spanning_two_chunks_is_parsed_whole() throws IOException {
        final var before = recordOfLength("before", CHUNK - 100);
        final var spanning = record("spanning", 500);
        writePage(bytes(before, "\n", spanning, "\n", record("after", 10), "\n"));

        final var page = read();

        assertEquals(3, page.size());
        assertEquals("x".repeat(500), page.get("spanning").getData().get("payload").asJsonString().getValue());
        assertEquals(0L, page.get("spanning").getPage());
    }

    @Test
    public void test_a_line_spanning_three_chunks_is_parsed_whole() throws IOException {
        final var huge = record("huge", 2 * CHUNK + 77);
        writePage(bytes(record("first", 5), "\n", huge, "\n", record("last", 5), "\n"));

        final var page = read();

        assertEquals(3, page.size());
        assertEquals(2 * CHUNK + 77, page.get("huge").getData().get("payload").asJsonString().getValue().length());
    }

    @Test
    public void test_a_hole_starting_in_one_chunk_and_ending_in_the_next_is_skipped() throws IOException {
        final var before = recordOfLength("before", CHUNK - 40);
        writePage(bytes(before, "\n", " ".repeat(300), "\n", record("after", 10), "\n"));

        final var page = read();

        assertEquals(List.of("after", "before"), page.keySet().stream().sorted().toList());
    }

    @Test
    public void test_a_record_whose_first_byte_starts_a_chunk_is_not_mistaken_for_a_hole() throws IOException {
        final var before = recordOfLength("before", CHUNK - 1);
        writePage(bytes(before, "\n", record("next", 20), "\n"));

        assertEquals(List.of("before", "next"), read().keySet().stream().sorted().toList());
    }

    @Test
    public void test_a_crlf_split_across_chunks_keeps_its_terminator() throws IOException {
        final var content = bytes("{\"_id\":\"a\"}\r", "\n{\"_id\":\"b\"}\r\n");

        assertEquals(List.of("{\"_id\":\"a\"}", "{\"_id\":\"b\"}"), chunkedLines(content, 12));
        final var crlf = recordOfLength("crlf", CHUNK - 1);
        writePage(bytes(crlf, "\r\n", record("b", 3), "\r\n"));
        assertEquals(List.of("b", "crlf"), read().keySet().stream().sorted().toList());
    }

    @Test
    public void test_a_lone_carriage_return_at_a_chunk_end_does_not_swallow_the_next_line() {
        final var content = bytes("{\"_id\":\"a\"}\r", "{\"_id\":\"b\"}\n");

        assertEquals(List.of("{\"_id\":\"a\"}", "{\"_id\":\"b\"}"), chunkedLines(content, 12));
    }

    @Test
    public void test_every_split_point_yields_the_lines_of_the_whole_buffer() {
        final var content = bytes("{\"_id\":\"a\"}\n", "   \n", "\n", "{\"_id\":\"b\"}\r\n", "\r", "{\"_id\":\"c\"}\r",
                "\r\n", "{\"_id\":\"torn");
        final var expected = wholeLines(content);

        for (var cut = 0; cut <= content.length; cut++) {
            assertEquals(expected, chunkedLines(content, cut), "one cut at " + cut);
            for (var second = cut; second <= content.length; second++) {
                assertEquals(expected, chunkedLines(content, cut, second), "cuts at " + cut + " and " + second);
            }
        }
    }

    @Test
    public void test_no_read_allocates_more_than_one_chunk() throws IOException {
        final var content = new byte[3 * CHUNK + 12345];
        Arrays.fill(content, (byte) 'y');
        for (var i = 99; i < content.length; i += 100) {
            content[i] = '\n';
        }
        final var file = new File(tmp, "big.dat");
        Files.write(file.toPath(), content);

        final var chunks = FileLocks.readChunksLocked(file, CHUNK);

        assertEquals(4, chunks.size());
        final var joined = new ByteArrayOutputStream();
        for (final var chunk : chunks) {
            assertTrue(chunk.length <= CHUNK, "a chunk of " + chunk.length + " bytes");
            joined.writeBytes(chunk);
        }
        assertArrayEquals(content, joined.toByteArray());
    }

    @Test
    public void test_an_empty_page_reads_as_no_chunk_and_no_document() throws IOException {
        writePage(new byte[0]);

        assertTrue(FileLocks.readChunksLocked(paths.collectionPage(DB, COLL, 0), CHUNK).isEmpty());
        assertTrue(read().isEmpty());
    }

    @Test
    public void test_a_torn_last_line_is_skipped_and_an_unterminated_complete_one_is_kept() throws IOException {
        final var spanning = recordOfLength("first", CHUNK + 10);
        writePage(bytes(spanning, "\n", record("second", 3), "\n", "{\"_id\":\"torn\",\"pay"));
        assertEquals(List.of("first", "second"), read().keySet().stream().sorted().toList());

        writePage(bytes(record("first", 3), "\n", recordOfLength("unterminated", CHUNK)));
        assertEquals(List.of("first", "unterminated"), read().keySet().stream().sorted().toList());
    }

    @Test
    public void test_a_missing_page_reads_as_empty() throws IOException {
        assertTrue(read().isEmpty());
    }

    @Test
    public void test_a_page_cut_shorter_than_its_measured_length_reads_what_is_left() throws IOException {
        final var content = bytes(record("a", 10), "\n", record("b", CHUNK), "\n");
        writePage(content);

        try (var reader = new RandomAccessFile(paths.collectionPage(DB, COLL, 0), "r")) {
            final var chunks = FileLocks.readChunks(reader, content.length + 1000L, CHUNK);

            final var read = new ByteArrayOutputStream();
            chunks.forEach(read::writeBytes);
            assertArrayEquals(content, read.toByteArray());
        }
    }
}

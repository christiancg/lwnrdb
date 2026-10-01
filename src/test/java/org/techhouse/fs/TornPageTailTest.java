package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.data.PkIndexEntry;

public class TornPageTailTest {
    private static final String COMPLETE = "{\"_id\":\"a\"}\n";

    private static File page(File dir, String content) throws IOException {
        final var file = new File(dir, "coll-0.dat");
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        return file;
    }

    private static PkIndexEntry entryAtStart(String id, long length, long page) {
        return new PkIndexEntry("db", "coll", id, 0L, length, page, 1L);
    }

    @Test
    public void test_a_torn_tail_is_truncated_to_the_last_newline(@TempDir File tmp) throws IOException {
        final var file = page(tmp, COMPLETE + "{\"_id\":\"b\",\"va");

        assertTrue(TornPageTail.heal(file, 0, List.of(entryAtStart("a", COMPLETE.length(), 0))));

        assertEquals(COMPLETE, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_page_holding_only_torn_bytes_truncates_to_empty(@TempDir File tmp) throws IOException {
        final var file = page(tmp, "{\"_id\":\"b\",\"va");

        assertTrue(TornPageTail.heal(file, 0, List.of()));

        assertEquals(0, file.length());
    }

    @Test
    public void test_a_page_ending_in_a_newline_is_left_alone(@TempDir File tmp) throws IOException {
        final var file = page(tmp, COMPLETE);

        assertFalse(TornPageTail.heal(file, 0, List.of()));

        assertEquals(COMPLETE, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_an_empty_page_is_left_alone(@TempDir File tmp) throws IOException {
        final var file = page(tmp, "");

        assertFalse(TornPageTail.heal(file, 0, List.of()));
    }

    @Test
    public void test_a_tail_an_index_entry_covers_is_left_alone(@TempDir File tmp) throws IOException {
        final var unterminated = "{\"_id\":\"a\"}";
        final var file = page(tmp, unterminated);

        assertFalse(TornPageTail.heal(file, 0, List.of(entryAtStart("a", unterminated.length(), 0))),
                "bytes an index entry points at are a record, not a torn write");

        assertEquals(unterminated, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_an_entry_on_another_page_does_not_protect_the_tail(@TempDir File tmp) throws IOException {
        final var file = page(tmp, COMPLETE + "{\"torn");

        assertTrue(TornPageTail.heal(file, 0, List.of(entryAtStart("other", 500, 1))));

        assertEquals(COMPLETE, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_torn_tail_longer_than_one_scan_chunk_is_found(@TempDir File tmp) throws IOException {
        final var file = page(tmp, COMPLETE + "x".repeat(20_000));

        assertTrue(TornPageTail.heal(file, 0, List.of()));

        assertEquals(COMPLETE, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_crlf_page_is_cut_back_to_its_last_complete_record(@TempDir File tmp) throws IOException {
        final var crlfRecord = "{\"_id\":\"a\"}\r\n";
        final var file = page(tmp, crlfRecord + "{\"_id\":\"b\",\"va");

        assertTrue(TornPageTail.heal(file, 0, List.of(entryAtStart("a", crlfRecord.length(), 0))));

        assertEquals(crlfRecord, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_crlf_record_torn_between_its_two_terminator_bytes_is_dropped(@TempDir File tmp)
            throws IOException {
        final var crlfRecord = "{\"_id\":\"a\"}\r\n";
        final var file = page(tmp, crlfRecord + "{\"_id\":\"b\"}\r");

        assertTrue(TornPageTail.heal(file, 0, List.of(entryAtStart("a", crlfRecord.length(), 0))),
                "a record missing its final terminator byte never reached pk.idx, so it was never acknowledged");

        assertEquals(crlfRecord, Files.readString(file.toPath(), StandardCharsets.UTF_8));
    }

    @Test
    public void test_a_complete_crlf_page_is_left_alone(@TempDir File tmp) throws IOException {
        final var crlfRecord = "{\"_id\":\"a\"}\r\n";
        final var file = page(tmp, crlfRecord);

        assertFalse(TornPageTail.heal(file, 0, List.of(entryAtStart("a", crlfRecord.length(), 0))));
    }
}

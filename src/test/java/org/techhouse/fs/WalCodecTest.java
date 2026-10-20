package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

public class WalCodecTest {
    private static final byte[] NEWLINE_AND_SEPARATOR = "{\"_id\":\"a\u001fb\"}\n".getBytes(StandardCharsets.UTF_8);

    private static final byte[] PK_ROW = "a\u001f7\u001f12\u001f3\u001f9\n".getBytes(StandardCharsets.UTF_8);

    private static WalCheckpoint checkpoint() {
        return new WalCheckpoint(1L, "walDb", "walColl", 42L, new TreeMap<>(Map.of(0L, 100L, 3L, 7L)));
    }

    private static WalRecord record(long seq) {
        return new WalRecord(seq,
                List.of(new WalChange(WalTarget.PAGE_WRITE, 3L, 7L, NEWLINE_AND_SEPARATOR.length,
                        NEWLINE_AND_SEPARATOR), new WalChange(WalTarget.PK_WRITE, -1L, 42L, PK_ROW.length, PK_ROW),
                        new WalChange(WalTarget.PAGE_BLANK, 0L, 10L, 25L, new byte[0])));
    }

    private static byte[] log(byte[]... frames) {
        final var out = new ByteArrayOutputStream();
        Arrays.stream(frames).forEach(out::writeBytes);
        return out.toByteArray();
    }

    private static void assertSameRecord(WalRecord expected, WalRecord actual) {
        assertEquals(expected.seq(), actual.seq());
        assertEquals(expected.changes().size(), actual.changes().size());
        for (var index = 0; index < expected.changes().size(); index++) {
            final var want = expected.changes().get(index);
            final var got = actual.changes().get(index);
            assertEquals(want.target(), got.target());
            assertEquals(want.page(), got.page());
            assertEquals(want.offset(), got.offset());
            assertEquals(want.length(), got.length());
            assertArrayEquals(want.bytes(), got.bytes());
        }
    }

    @Test
    public void test_every_kind_round_trips() {
        final var bytes = log(WalCodec.encode(checkpoint()), WalCodec.encode(record(2L)));

        final var decoded = WalCodec.decodeAll(bytes);

        assertEquals(checkpoint(), decoded.checkpoint());
        assertEquals(1, decoded.records().size());
        assertSameRecord(record(2L), decoded.records().getFirst());
        assertEquals(bytes.length, decoded.cleanLength());
        assertEquals(2L, decoded.lastSeq());
        assertEquals(1, decoded.framesSinceCheckpoint());
    }

    @Test
    public void test_a_cut_anywhere_in_the_last_frame_leaves_the_earlier_records() {
        final var head = log(WalCodec.encode(checkpoint()), WalCodec.encode(record(2L)));
        final var whole = log(head, WalCodec.encode(record(3L)));

        for (var cut = head.length; cut < whole.length; cut++) {
            final var decoded = WalCodec.decodeAll(Arrays.copyOf(whole, cut));

            assertEquals(1, decoded.records().size(), "cut at " + cut);
            assertEquals(head.length, decoded.cleanLength(), "cut at " + cut);
        }
    }

    @Test
    public void test_a_flipped_byte_fails_the_crc_and_ends_the_read() {
        final var head = log(WalCodec.encode(checkpoint()));
        final var bytes = log(head, WalCodec.encode(record(2L)), WalCodec.encode(record(3L)));
        bytes[head.length + WalCodec.HEADER_BYTES + 5] ^= 0x01;

        final var decoded = WalCodec.decodeAll(bytes);

        assertEquals(List.of(), decoded.records());
        assertEquals(head.length, decoded.cleanLength());
    }

    @Test
    public void test_a_length_past_the_end_is_torn() {
        final var head = log(WalCodec.encode(checkpoint()));
        final var frame = WalCodec.encode(record(2L));
        frame[1 + 1 + Long.BYTES] = 0x7F;

        final var decoded = WalCodec.decodeAll(log(head, frame));

        assertEquals(List.of(), decoded.records());
        assertEquals(head.length, decoded.cleanLength());
    }

    @Test
    public void test_an_abort_removes_the_record_it_names() {
        final var decoded = WalCodec.decodeAll(log(WalCodec.encode(checkpoint()), WalCodec.encode(record(2L)),
                WalCodec.encode(record(3L)), WalCodec.encodeAbort(4L, 2L)));

        assertEquals(List.of(3L), decoded.records().stream().map(WalRecord::seq).toList());
        assertEquals(4L, decoded.lastSeq());
        assertEquals(3, decoded.framesSinceCheckpoint());
    }

    @Test
    public void test_a_later_checkpoint_drops_the_records_before_it() {
        final var later = new WalCheckpoint(5L, "walDb", "walColl", 50L, Map.of());

        final var decoded = WalCodec
                .decodeAll(log(WalCodec.encode(checkpoint()), WalCodec.encode(record(2L)), WalCodec.encode(later)));

        assertEquals(later, decoded.checkpoint());
        assertEquals(List.of(), decoded.records());
        assertEquals(0, decoded.framesSinceCheckpoint());
    }

    @Test
    public void test_records_without_a_checkpoint_have_none() {
        final var decoded = WalCodec.decodeAll(WalCodec.encode(record(1L)));

        assertNull(decoded.checkpoint());
    }
}

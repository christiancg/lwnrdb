package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.config.Globals;
import org.techhouse.fs.io.ChunkedChannelWrites;

public class WalPreallocationTest {
    private static final String DB = "preallocDb";
    private static final String COLL = "preallocColl";
    private static final long MIN = Globals.WAL_PREALLOCATE_MIN_BYTES;
    private static final long MAX = Globals.WAL_PREALLOCATE_MAX_BYTES;

    @TempDir
    File tmp;

    private ReplacerFixture rf;

    @BeforeEach
    public void setUp() throws Exception {
        rf = new ReplacerFixture(tmp, DB, COLL);
        rf.fx.seed(0, "a", "b", "c");
    }

    private File log() {
        return rf.fx.paths.writeAheadLog(DB, COLL);
    }

    private byte[] logBytes() throws IOException {
        return Files.readAllBytes(log().toPath());
    }

    private WalCodec.Decoded decoded() throws IOException {
        return WalCodec.decodeAll(logBytes());
    }

    private static void assertZeroPast(byte[] bytes, long end) {
        assertEquals(end, WalWriteGroup.writtenEnd(bytes, end), "every byte past the log's end is zero");
    }

    @Test
    public void test_records_land_inside_a_preallocated_log() throws Exception {
        final var bytes = logBytes();
        final var end = decoded().cleanLength();

        assertTrue(bytes.length >= MIN, "the log is extended ahead of its records");
        assertTrue(end < bytes.length);
        assertZeroPast(bytes, end);
        assertEquals(3, decoded().records().size());
    }

    @Test
    public void test_preallocation_doubles_until_it_reaches_its_cap() throws Exception {
        final var file = new File(tmp, "growth.wal");
        final var header = new byte[]{'W'};
        try (var channel = WalWriteGroup.openWritable(file)) {
            assertEquals(MIN, WalWriteGroup.writeFrame(channel, file, 0L, 0L, header, header.length, false));
            assertEquals(2 * MIN, WalWriteGroup.writeFrame(channel, file, MIN, MIN, header, header.length, false));
            assertEquals(2 * MAX, WalWriteGroup.writeFrame(channel, file, MAX, MAX, header, header.length, false));
            assertEquals(3 * MAX,
                    WalWriteGroup.writeFrame(channel, file, 2 * MAX, 2 * MAX, header, header.length, false));
            assertEquals(2 * MAX + 10,
                    WalWriteGroup.writeFrame(channel, file, 10L, 2 * MAX + 10, header, header.length, false),
                    "a frame that fits needs no room");
        }
        assertEquals(3 * MAX, file.length());
    }

    @Test
    public void test_a_frame_larger_than_the_growth_gets_room_for_all_of_it() throws Exception {
        final var file = new File(tmp, "large.wal");
        final var payload = new byte[(int) MIN * 3];
        try (var channel = WalWriteGroup.openWritable(file)) {
            final var room = WalWriteGroup.writeFrame(channel, file, 0L, 0L, payload, payload.length, false);

            assertEquals(payload.length, room);
        }
    }

    @Test
    public void test_a_taken_back_group_is_zeroed_in_place() throws Exception {
        final var endBefore = decoded().cleanLength();
        final var recordsBefore = decoded().records().size();
        final var lengthBefore = log().length();
        final var walForces = new AtomicInteger();
        try (var recorder = RecordingForcer.install(true)) {
            recorder.refusingWhen(
                    (_, name) -> name.endsWith(Globals.WAL_EXTENSION) && walForces.getAndIncrement() == 1);
            final var wal = new WriteAheadLog(rf.fx.paths);
            wal.openGroup(DB, COLL);
            rf.replace("b", "new-b", 0, PagePlacement.APPEND);
            rf.replacer.delete(rf.fx.indexed("c"));

            assertFalse(wal.closeGroup(DB, COLL));
        }

        final var bytes = logBytes();
        final var decoded = WalCodec.decodeAll(bytes);
        assertEquals(lengthBefore, bytes.length, "the log is not cut");
        assertEquals(recordsBefore, decoded.records().size());
        assertTrue(decoded.cleanLength() >= endBefore);
        assertZeroPast(bytes, decoded.cleanLength());
        assertEquals(StorageFixture.record("b", "old-b"), rf.fx.readAt(rf.fx.indexed("b")));
        assertEquals(StorageFixture.record("c", "old-c"), rf.fx.readAt(rf.fx.indexed("c")));
    }

    @Test
    public void test_a_valid_frame_past_a_torn_one_is_erased_before_the_next_append() throws Exception {
        final var end = OutsideWrites.logEnd(log());
        final var stale = WalCodec
                .encode(new WalRecord(900L, List.of(new WalChange(WalTarget.PAGE_BLANK, 0L, 0L, 1L, new byte[0]))));
        try (var channel = FileChannel.open(log().toPath(), StandardOpenOption.WRITE)) {
            ChunkedChannelWrites.writeAt(channel, end, new byte[]{'W', 'R', 0}, 0, 3);
            ChunkedChannelWrites.writeAt(channel, end + 4096, stale, 0, stale.length);
        }
        OutsideWrites.madeTo(log());

        rf.replace("b", "new-b", 0, PagePlacement.APPEND);

        final var bytes = logBytes();
        final var decoded = WalCodec.decodeAll(bytes);
        assertZeroPast(bytes, decoded.cleanLength());
        assertTrue(decoded.records().stream().noneMatch(record -> record.seq() == 900L));
        assertEquals(StorageFixture.record("b", "new-b"), rf.fx.readAt(rf.fx.indexed("b")));
    }

    @Test
    public void test_erasing_a_missing_log_or_an_empty_range_is_nothing_to_do() {
        assertTrue(WalWriteGroup.erase(new File(tmp, "absent.wal"), 0L, 10L, "unused"));
        assertTrue(WalWriteGroup.erase(log(), 10L, 10L, "unused"));
    }

    @Test
    public void test_a_failed_erase_is_reported() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            recorder.failingFor(Globals.WAL_EXTENSION);

            assertFalse(WalWriteGroup.erase(log(), 0L, 1L, "expected in this test"));
        }
    }
}

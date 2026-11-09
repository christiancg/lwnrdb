package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.techhouse.fs.io.ChunkedChannelWrites;

public class WalFrameHeadroomTest {
    private static final byte[] FIRST = frame(1L);
    private static final byte[] SECOND = frame(2L);

    @TempDir
    File tmp;

    private File log;

    private static byte[] frame(long seq) {
        return WalCodec
                .encode(new WalRecord(seq, List.of(new WalChange(WalTarget.PAGE_BLANK, 0L, 0L, 1L, new byte[0]))));
    }

    @BeforeEach
    public void setUp() throws IOException {
        log = new File(tmp, "headroom.wal");
        try (var channel = WalWriteGroup.openWritable(log)) {
            ChunkedChannelWrites.writeAt(channel, 0L, FIRST, 0, FIRST.length);
        }
    }

    private WalCodec.Decoded decoded() throws IOException {
        return WalCodec.decodeAll(Files.readAllBytes(log.toPath()));
    }

    private void appendWithRoom(long room) throws IOException {
        final var at = (long) FIRST.length;
        try (var channel = new RoomLimitedChannel(WalWriteGroup.openWritable(log), at + room)) {
            WalWriteGroup.writeFrame(channel, log, at, at, SECOND, SECOND.length, true);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, WalCodec.HEADER_BYTES, WalCodec.HEADER_BYTES + 1})
    public void aFrameWithoutRoomFailsBeforeAnyOfItsBytesLand(int room) throws IOException {
        final var failure = assertThrows(IOException.class, () -> appendWithRoom(room));

        assertEquals(RoomLimitedChannel.NO_SPACE, failure.getMessage());
        final var bytes = Files.readAllBytes(log.toPath());
        final var decoded = WalCodec.decodeAll(bytes);
        assertEquals(List.of(1L), decoded.records().stream().map(WalRecord::seq).toList());
        assertEquals(FIRST.length, decoded.cleanLength());
        assertEquals(FIRST.length, WalWriteGroup.writtenEnd(bytes, FIRST.length),
                "only the preallocation's zeros reached the room, never a frame header");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, WalCodec.HEADER_BYTES})
    public void theAppendLandsOnceSpaceIsBack(int room) throws IOException {
        assertThrows(IOException.class, () -> appendWithRoom(room));
        final var at = (long) FIRST.length;

        try (var channel = WalWriteGroup.openWritable(log)) {
            WalWriteGroup.writeFrame(channel, log, at, Math.max(at, log.length()), SECOND, SECOND.length, true);
        }

        assertEquals(List.of(1L, 2L), decoded().records().stream().map(WalRecord::seq).toList());
        assertEquals(FIRST.length + SECOND.length, decoded().cleanLength());
    }
}

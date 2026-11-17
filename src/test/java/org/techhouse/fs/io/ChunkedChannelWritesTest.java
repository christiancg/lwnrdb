package org.techhouse.fs.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class ChunkedChannelWritesTest {
    private static final int CHUNK = ChunkedChannelWrites.CHUNK_BYTES;
    private static final int UNCAPPED = Integer.MAX_VALUE;

    private record Call(long offered) {
    }

    private static final class Recording {
        private final int maxPerCall;
        private final List<Call> calls = new ArrayList<>();
        private byte[] positioned = new byte[0];
        private final FileChannel channel = Mockito.mock(FileChannel.class);

        private Recording(int maxPerCall) throws IOException {
            this.maxPerCall = maxPerCall;
            Mockito.when(channel.write(Mockito.any(ByteBuffer.class), Mockito.anyLong()))
                    .thenAnswer(call -> writeAt(call.getArgument(0), call.getArgument(1)));
        }

        private int writeAt(ByteBuffer buffer, long position) {
            calls.add(new Call(buffer.remaining()));
            final var bytes = take(buffer, maxPerCall);
            final var end = (int) position + bytes.length;
            if (positioned.length < end) {
                positioned = Arrays.copyOf(positioned, end);
            }
            System.arraycopy(bytes, 0, positioned, (int) position, bytes.length);
            return bytes.length;
        }

        private static byte[] take(ByteBuffer buffer, int limit) {
            final var bytes = new byte[Math.min(buffer.remaining(), limit)];
            buffer.get(bytes);
            return bytes;
        }

        private long largestOffer() {
            return calls.stream().mapToLong(Call::offered).max().orElse(0L);
        }
    }

    private static byte[] patternedBytes(int length) {
        final var bytes = new byte[length];
        for (var index = 0; index < length; index++) {
            bytes[index] = (byte) (index * 31 + index / 251 + length);
        }
        return bytes;
    }

    @Test
    public void test_a_large_positioned_write_never_hands_the_channel_more_than_one_chunk() throws IOException {
        final var recording = new Recording(UNCAPPED);
        final var bytes = patternedBytes(4 * 1024 * 1024 + 7);

        ChunkedChannelWrites.writeAt(recording.channel, 0L, bytes, 0, bytes.length);

        assertArrayEquals(bytes, recording.positioned);
        assertEquals(CHUNK, recording.largestOffer(), "a 4 MB slice is handed over at most one chunk at a time");
        assertEquals(33, recording.calls.size(), "32 whole chunks and the 7-byte tail");
    }

    @Test
    public void test_a_positioned_write_lands_each_chunk_after_the_previous_one() throws IOException {
        final var recording = new Recording(UNCAPPED);
        final var bytes = patternedBytes(3 * CHUNK);

        ChunkedChannelWrites.writeAt(recording.channel, 100L, bytes, CHUNK / 2, 2 * CHUNK);

        assertEquals(100 + 2 * CHUNK, recording.positioned.length);
        assertArrayEquals(Arrays.copyOfRange(bytes, CHUNK / 2, CHUNK / 2 + 2 * CHUNK),
                Arrays.copyOfRange(recording.positioned, 100, recording.positioned.length));
    }

    @Test
    public void test_a_short_positioned_write_is_retried_until_every_byte_lands() throws IOException {
        final var recording = new Recording(1000);
        final var bytes = patternedBytes(CHUNK + 12_345);

        ChunkedChannelWrites.writeAt(recording.channel, 0L, bytes, 0, bytes.length);

        assertArrayEquals(bytes, recording.positioned);
        assertTrue(recording.largestOffer() <= CHUNK);
    }

    @Test
    public void test_an_empty_positioned_write_touches_nothing() throws IOException {
        final var recording = new Recording(UNCAPPED);

        ChunkedChannelWrites.writeAt(recording.channel, 0L, new byte[0], 0, 0);

        assertTrue(recording.calls.isEmpty());
    }
}

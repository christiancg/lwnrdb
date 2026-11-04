package org.techhouse.fs.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

public class ChunkedChannelWritesTest {
    private static final int CHUNK = ChunkedChannelWrites.CHUNK_BYTES;
    private static final int UNCAPPED = Integer.MAX_VALUE;

    @TempDir
    Path tmp;

    private enum Kind {
        POSITIONED, APPENDING, GATHERING
    }

    private record Call(Kind kind, long offered) {
    }

    private static final class Recording {
        private final int maxPerCall;
        private final List<Call> calls = new ArrayList<>();
        private final ByteArrayOutputStream appended = new ByteArrayOutputStream();
        private byte[] positioned = new byte[0];
        private final FileChannel channel = Mockito.mock(FileChannel.class);

        private Recording(int maxPerCall) throws IOException {
            this.maxPerCall = maxPerCall;
            Mockito.when(channel.write(Mockito.any(ByteBuffer.class), Mockito.anyLong()))
                    .thenAnswer(call -> writeAt(call.getArgument(0), call.getArgument(1)));
            Mockito.when(channel.write(Mockito.any(ByteBuffer.class))).thenAnswer(call -> append(call.getArgument(0)));
            Mockito.when(channel.write(Mockito.any(ByteBuffer[].class)))
                    .thenAnswer(call -> gather(call.getArgument(0)));
        }

        private int writeAt(ByteBuffer buffer, long position) {
            calls.add(new Call(Kind.POSITIONED, buffer.remaining()));
            final var bytes = take(buffer, maxPerCall);
            final var end = (int) position + bytes.length;
            if (positioned.length < end) {
                positioned = Arrays.copyOf(positioned, end);
            }
            System.arraycopy(bytes, 0, positioned, (int) position, bytes.length);
            return bytes.length;
        }

        private int append(ByteBuffer buffer) {
            calls.add(new Call(Kind.APPENDING, buffer.remaining()));
            final var bytes = take(buffer, maxPerCall);
            appended.writeBytes(bytes);
            return bytes.length;
        }

        private long gather(ByteBuffer[] buffers) {
            calls.add(new Call(Kind.GATHERING, Arrays.stream(buffers).mapToLong(ByteBuffer::remaining).sum()));
            var budget = maxPerCall;
            var written = 0L;
            for (final var buffer : buffers) {
                final var bytes = take(buffer, budget);
                appended.writeBytes(bytes);
                budget -= bytes.length;
                written += bytes.length;
            }
            return written;
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

    @Test
    public void test_header_and_first_chunk_go_in_one_gathering_write() throws IOException {
        final var recording = new Recording(UNCAPPED);
        final var header = patternedBytes(20);
        final var payload = patternedBytes(300 * 1024);

        ChunkedChannelWrites.append(recording.channel, header, payload, payload.length);

        assertEquals(new Call(Kind.GATHERING, CHUNK), recording.calls.getFirst());
        assertTrue(recording.calls.stream().skip(1).allMatch(call -> call.kind() == Kind.APPENDING));
        assertEquals(CHUNK, recording.largestOffer());
        final var expected = new ByteArrayOutputStream();
        expected.writeBytes(header);
        expected.writeBytes(payload);
        assertArrayEquals(expected.toByteArray(), recording.appended.toByteArray());
    }

    @Test
    public void test_a_record_of_exactly_one_chunk_is_one_gathering_write_and_one_byte_more_adds_one_write()
            throws IOException {
        final var header = patternedBytes(16);
        final var exact = new Recording(UNCAPPED);
        ChunkedChannelWrites.append(exact.channel, header, patternedBytes(CHUNK - 16), CHUNK - 16);
        assertEquals(List.of(new Call(Kind.GATHERING, CHUNK)), exact.calls);

        final var oneMore = new Recording(UNCAPPED);
        ChunkedChannelWrites.append(oneMore.channel, header, patternedBytes(CHUNK - 15), CHUNK - 15);
        assertEquals(List.of(new Call(Kind.GATHERING, CHUNK), new Call(Kind.APPENDING, 1)), oneMore.calls);
    }

    @Test
    public void test_only_the_stated_payload_length_is_appended() throws IOException {
        final var recording = new Recording(UNCAPPED);
        final var payload = patternedBytes(CHUNK * 2);

        ChunkedChannelWrites.append(recording.channel, new byte[]{1, 2}, payload, CHUNK + 3);

        assertEquals(2 + CHUNK + 3, recording.appended.size());
        assertArrayEquals(Arrays.copyOf(payload, CHUNK + 3),
                Arrays.copyOfRange(recording.appended.toByteArray(), 2, recording.appended.size()));
    }

    @Test
    public void test_an_empty_payload_writes_the_header_alone() throws IOException {
        final var recording = new Recording(UNCAPPED);
        final var header = patternedBytes(24);

        ChunkedChannelWrites.append(recording.channel, header, new byte[0], 0);

        assertEquals(List.of(new Call(Kind.GATHERING, 24)), recording.calls);
        assertArrayEquals(header, recording.appended.toByteArray());
    }

    @Test
    public void test_short_appending_writes_are_retried_until_header_and_payload_land() throws IOException {
        final var recording = new Recording(777);
        final var header = patternedBytes(40);
        final var payload = patternedBytes(2 * CHUNK + 99);

        ChunkedChannelWrites.append(recording.channel, header, payload, payload.length);

        final var expected = new ByteArrayOutputStream();
        expected.writeBytes(header);
        expected.writeBytes(payload);
        assertArrayEquals(expected.toByteArray(), recording.appended.toByteArray());
        assertTrue(recording.largestOffer() <= CHUNK);
    }

    @Test
    public void test_a_real_file_receives_both_writes_intact() throws IOException {
        final var file = tmp.resolve("chunked.bin");
        final var header = patternedBytes(10);
        final var payload = patternedBytes(CHUNK * 3 + 5);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ChunkedChannelWrites.append(channel, header, payload, payload.length);
        }
        final var patch = patternedBytes(CHUNK + 1);
        try (var channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ChunkedChannelWrites.writeAt(channel, 10L, patch, 0, patch.length);
        }

        final var expected = new ByteArrayOutputStream();
        expected.writeBytes(header);
        expected.writeBytes(patch);
        expected.writeBytes(Arrays.copyOfRange(payload, patch.length, payload.length));
        assertArrayEquals(expected.toByteArray(), Files.readAllBytes(file));
    }
}

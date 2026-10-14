package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class FileLocksDecodeTest {
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final String LINE_SEPARATOR = String.valueOf((char) 0x2028);
    private static final String PARAGRAPH_SEPARATOR = String.valueOf((char) 0x2029);
    private static final String NEXT_LINE = String.valueOf((char) 0x85);

    private static final byte[] ALPHABET = {'a', 'b', '{', '"', '|', ' ', '\n', '\r', '\r', '\n', (byte) 0xC3,
            (byte) 0xA9, (byte) 0xE2, (byte) 0x82, (byte) 0xAC, (byte) 0xF0, (byte) 0x9F, (byte) 0x98, (byte) 0x80,
            (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, (byte) 0xFF, (byte) 0x80, (byte) 0xC0, (byte) 0xED, (byte) 0xA0};

    private static void assertSameAsLegacy(byte[] bytes) throws IOException {
        assertEquals(LegacyStorageOracle.decodeLines(bytes), FileLocks.decodeLines(bytes));
    }

    private static void assertSameAsLegacy(String text) throws IOException {
        assertSameAsLegacy(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void decodeLinesMatchesBufferedReaderSemantics() throws IOException {
        for (final var text : List.of("", "a", "a\n", "a\r\n", "a\r", "a\nb", "a\r\nb", "a\rb", "a\n\nb", "\n", "\r",
                "\r\n", "\n\n", "\r\r", "\r\n\r\n", "\n\r", "a\r\r\nb", BOM + "a\nb",
                LINE_SEPARATOR + "a" + PARAGRAPH_SEPARATOR + "\nb" + NEXT_LINE + "c", "é\n€\r😀\r\n")) {
            assertSameAsLegacy(text);
        }
    }

    @Test
    public void malformedUtf8IsReplacedLikeTheWholeBufferDecode() throws IOException {
        assertSameAsLegacy(new byte[]{'a', (byte) 0xE2, '\n', 'b'});
        assertSameAsLegacy(new byte[]{'a', (byte) 0xE2, (byte) 0x82, '\n', 'b'});
        assertSameAsLegacy(new byte[]{(byte) 0xF0, (byte) 0x9F, (byte) 0x98, '\r', '\n', (byte) 0x80});
        assertSameAsLegacy(new byte[]{(byte) 0xED, (byte) 0xA0, (byte) 0x80, '\n'});
        assertSameAsLegacy(new byte[]{(byte) 0xC0, (byte) 0xAF, '\r'});
        assertSameAsLegacy(new byte[]{(byte) 0xE2, (byte) 0x82});
    }

    private static byte[] randomBytes(Random random) {
        final var bytes = new byte[random.nextInt(40)];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = random.nextInt(4) == 0 ? (byte) random.nextInt(256) : ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return bytes;
    }

    @Test
    public void randomByteSequencesDecodeLikeTheLegacyReader() throws IOException {
        final var random = new Random(20261013L);
        for (var round = 0; round < 20_000; round++) {
            assertSameAsLegacy(randomBytes(random));
        }
    }

    @Test
    public void decodeLinesAllocatesOneCopy() {
        final var line = "{\"_id\":\"0123456789\",\"name\":\"" + "x".repeat(100) + "\"}\n";
        final var bytes = line.repeat(10_000).getBytes(StandardCharsets.UTF_8);
        final var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        final var threadId = Thread.currentThread().threadId();
        FileLocks.decodeLines(bytes);
        final var before = threads.getThreadAllocatedBytes(threadId);
        final var lines = FileLocks.decodeLines(bytes);
        final var allocated = threads.getThreadAllocatedBytes(threadId) - before;
        assertEquals(10_000, lines.size());
        assertTrue(allocated <= bytes.length * 2.2,
                "decoding " + bytes.length + " bytes allocated " + allocated + " bytes");
    }
}

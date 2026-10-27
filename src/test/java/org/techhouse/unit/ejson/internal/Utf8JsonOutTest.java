package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.internal.Utf8Encoder;
import org.techhouse.ejson.internal.Utf8JsonOut;
import org.techhouse.test.TestUtils;

public class Utf8JsonOutTest {
    private static byte[] encoded(String text) {
        final var out = Utf8JsonOut.acquire(16);
        try {
            out.append(text);
            return out.toByteArray();
        } finally {
            out.release();
        }
    }

    @Test
    public void everyEncodingWidthMatchesTheJdkEncoder() {
        for (final var text : new String[]{"plain", "é", "ü€", "中文", "😀", "a😀b€c", "", "\u0000\u007f\u0080߿ࠀ"}) {
            assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), encoded(text), text);
        }
    }

    @Test
    public void loneSurrogatesBecomeTheReplacementByteLikeTheOldEncoder() {
        for (final var text : new String[]{"\ud800", "x\udc00y", "\ud800\ud800", "😀\ud800"}) {
            assertArrayEquals(Utf8Encoder.encode(text), encoded(text), text);
        }
    }

    @Test
    public void aSurrogateCharAppendedAloneIsReplaced() {
        final var out = Utf8JsonOut.acquire(16);
        try {
            out.append('\ud83d').append('a').append('é');
            assertArrayEquals("?aé".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        } finally {
            out.release();
        }
    }

    @Test
    public void aRangeAppendsOnlyItsSlice() {
        final var out = Utf8JsonOut.acquire(16);
        try {
            out.append("xx€😀yy", 2, 5);
            assertArrayEquals("€😀".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        } finally {
            out.release();
        }
    }

    @Test
    public void longsRenderLikeLongToString() {
        for (final var value : new long[]{0, 7, -7, 10, 1234567890123L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            final var out = Utf8JsonOut.acquire(16);
            try {
                out.appendLong(value);
                assertEquals(Long.toString(value), new String(out.toByteArray(), StandardCharsets.US_ASCII));
            } finally {
                out.release();
            }
        }
    }

    @Test
    public void resetRewindsPastAMultibyteCharacter() {
        final var out = Utf8JsonOut.acquire(16);
        try {
            out.append("ok:");
            final var mark = out.mark();
            out.append("中😀");
            out.reset(mark);
            out.append("null");
            assertArrayEquals("ok:null".getBytes(StandardCharsets.UTF_8), out.toByteArray());
        } finally {
            out.release();
        }
    }

    @Test
    public void growthPastThePresizeKeepsEveryByte() {
        final var text = "abc€😀".repeat(50_000);
        assertArrayEquals(text.getBytes(StandardCharsets.UTF_8), encoded(text));
    }

    @Test
    public void aBufferAboveThePoolCapIsNotReturnedToThePool() throws Exception {
        final var cap = TestUtils.getPrivateStaticField(Utf8JsonOut.class, "MAX_POOLED_BYTES", Integer.class);
        final var big = Utf8JsonOut.acquire(cap + 1);
        final var oversized = TestUtils.getPrivateField(big, "buffer", byte[].class);
        big.release();

        final var drained = new ArrayList<Utf8JsonOut>();
        for (var i = 0; i < 4 * Runtime.getRuntime().availableProcessors(); i++) {
            final var next = Utf8JsonOut.acquire(16);
            assertNotSame(oversized, TestUtils.getPrivateField(next, "buffer", byte[].class));
            drained.add(next);
        }
        drained.forEach(Utf8JsonOut::release);
    }

    @Test
    public void concurrentWritersOnVirtualThreadsNeverShareABuffer() throws Exception {
        final var failures = new ConcurrentLinkedQueue<String>();
        final var threads = new ArrayList<Thread>();
        for (var t = 0; t < 64; t++) {
            final var text = "writer-" + t + "-€".repeat(t);
            threads.add(Thread.ofVirtual().start(() -> {
                for (var round = 0; round < 200; round++) {
                    final var bytes = encoded(text);
                    if (!new String(bytes, StandardCharsets.UTF_8).equals(text)) {
                        failures.add(text);
                    }
                }
            }));
        }
        for (final var thread : threads) {
            thread.join();
        }
        assertEquals(0, failures.size(), failures.toString());
    }

    @Test
    public void aBufferWithLittleSlackIsHandedOverWithoutACopy() throws Exception {
        TestUtils.getPrivateStaticField(Utf8JsonOut.class, "POOL", ArrayBlockingQueue.class).clear();
        final var out = Utf8JsonOut.acquire(400);
        out.append("x".repeat(380));
        final var buffer = TestUtils.getPrivateField(out, "buffer", byte[].class);

        final var frame = out.handOver();

        assertSame(buffer, frame.bytes());
        assertEquals(380, frame.length());
        out.release();
    }

    @Test
    public void aBufferWithMuchSlackIsCopiedToItsExactLength() throws Exception {
        final var out = Utf8JsonOut.acquire(4000);
        out.append("x".repeat(100));
        final var buffer = TestUtils.getPrivateField(out, "buffer", byte[].class);

        final var frame = out.handOver();

        assertNotSame(buffer, frame.bytes());
        assertEquals(100, frame.bytes().length);
        assertEquals(100, frame.length());
        out.release();
    }

    @Test
    public void anExactlyFilledBufferIsTakenWithoutACopy() throws Exception {
        final var pool = TestUtils.getPrivateStaticField(Utf8JsonOut.class, "POOL", ArrayBlockingQueue.class);
        pool.clear();
        final var out = Utf8JsonOut.acquire(400);
        out.append("x".repeat(400));
        final var buffer = TestUtils.getPrivateField(out, "buffer", byte[].class);

        final var taken = out.takeExact();

        assertSame(buffer, taken);
        assertFalse(out.release());
        assertFalse(pool.contains(buffer));
    }

    @Test
    public void aBufferWithSlackIsCopiedByTakeExact() throws Exception {
        final var out = Utf8JsonOut.acquire(4000);
        out.append("é".repeat(10));
        final var buffer = TestUtils.getPrivateField(out, "buffer", byte[].class);

        final var taken = out.takeExact();

        assertNotSame(buffer, taken);
        assertArrayEquals("é".repeat(10).getBytes(StandardCharsets.UTF_8), taken);
        out.release();
    }

    @Test
    public void digestIntoHashesOnlyTheGivenSlice() throws Exception {
        final var out = Utf8JsonOut.acquire(64);
        try {
            out.append("[{\"a\":1},{\"b\":\"中\"}]");
            final var digest = MessageDigest.getInstance("SHA-256");
            out.digestInto(digest, 1, 8);

            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest("{\"a\":1}".getBytes(StandardCharsets.UTF_8)),
                    digest.digest());
        } finally {
            out.release();
        }
    }
}

package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

public class TombstoneChunksTest {
    private static final String[] ALPHABET = {"a", "b", "Z", "0", "-", "_", "é", "中", "\n", "\\"};

    private static String randomId(Random random) {
        final var length = 1 + random.nextInt(12);
        final var id = new StringBuilder(length);
        for (var i = 0; i < length; i++) {
            id.append(random.nextInt(10) < 8 ? ALPHABET[random.nextInt(6)] : ALPHABET[6 + random.nextInt(4)]);
        }
        return id.toString();
    }

    private static void assertMatches(Map<String, Long> model, TombstoneChunks chunks, Random random) {
        assertEquals(model.size(), chunks.size());
        model.forEach((id, version) -> assertEquals(version, chunks.versionOf(id), id));
        final var visited = new HashMap<String, Long>();
        chunks.forEach(visited::put);
        assertEquals(model, visited);
        for (var probe = 0; probe < 200; probe++) {
            final var id = randomId(random) + "~";
            assertEquals(TombstoneChunks.ABSENT, chunks.versionOf(id), id);
        }
    }

    @Test
    public void test_merged_buffers_answer_like_a_map() {
        final var random = new Random(42);
        final var model = new HashMap<String, Long>();
        var chunks = TombstoneChunks.EMPTY;
        for (var round = 0; round < 12; round++) {
            final var buffer = new HashMap<String, Long>();
            final var appends = round == 3 ? 5000 : 1 + random.nextInt(1500);
            for (var i = 0; i < appends; i++) {
                final var id = random.nextInt(4) == 0 && !model.isEmpty()
                        ? model.keySet().iterator().next()
                        : randomId(random) + random.nextInt(100_000);
                final long version = 1 + random.nextInt(1_000_000);
                buffer.merge(id, version, Math::max);
                model.merge(id, version, Math::max);
            }
            chunks = chunks.merged(buffer);
            assertMatches(model, chunks, random);
        }
    }

    @Test
    public void test_a_builder_keeps_the_highest_version_of_a_repeated_id() {
        final var builder = new TombstoneChunks.Builder(4);
        builder.add("x", 5L);
        builder.add("x", 9L);
        builder.add("x", 7L);
        builder.add("y", 1L);

        final var built = builder.build();

        assertEquals(2, built.size());
        assertEquals(9L, built.versionOf("x"));
        assertEquals(TombstoneChunks.ABSENT, built.versionOf("w"));
    }

    @Test
    public void test_retaining_drops_only_old_versions_and_repacks() {
        final var buffer = new HashMap<String, Long>();
        for (var i = 0; i < 3000; i++) {
            buffer.put("id-" + i, (long) i);
        }
        final var kept = TombstoneChunks.EMPTY.merged(buffer).retainedFrom(2000L);

        assertEquals(1000, kept.size());
        assertEquals(2500L, kept.versionOf("id-2500"));
        assertEquals(TombstoneChunks.ABSENT, kept.versionOf("id-1999"));
        final var range = kept.versionRange();
        assertEquals(2000L, range[0]);
        assertEquals(2999L, range[1]);
        assertTrue(kept.bytes() < 1000L * 40, "bytes " + kept.bytes());
    }

    @Test
    public void test_keys_round_trip_and_order_like_their_encoding() {
        final var random = new Random(7);
        for (var i = 0; i < 2000; i++) {
            final var left = randomId(random);
            final var right = randomId(random);
            final var encodedLeft = TombstoneKeys.encode(left);
            final var encodedRight = TombstoneKeys.encode(right);
            assertEquals(left, TombstoneKeys.decode(encodedLeft, 0, encodedLeft.length));
            assertEquals(
                    Integer.signum(TombstoneKeys.compare(encodedLeft, 0, encodedLeft.length, encodedRight, 0,
                            encodedRight.length)),
                    Integer.signum(TombstoneKeys.compare(left, encodedRight, 0, encodedRight.length)),
                    left + " vs " + right);
        }
    }
}

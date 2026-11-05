package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.SaltedKey;

public class SaltedKeyTest {
    private static final int LONGEST = 17;
    private static final int BLOCKS = 15;
    private static final int CHARS_PER_ROUND = 4;

    private static long seededHash(long seed, long wordKey, String value) throws ReflectiveOperationException {
        final Method hash = SaltedKey.class.getDeclaredMethod("seededStringHash", long.class, long.class, String.class);
        hash.setAccessible(true);
        return (long) hash.invoke(null, seed, wordKey, value);
    }

    private static long seededHash(long seed, String value) throws ReflectiveOperationException {
        return seededHash(seed, processWordKey(), value);
    }

    private static long processSeed() throws ReflectiveOperationException {
        return TestUtils.getPrivateStaticField(SaltedKey.class, "SEED", Long.class);
    }

    private static long processWordKey() throws ReflectiveOperationException {
        return TestUtils.getPrivateStaticField(SaltedKey.class, "WORD_KEY", Long.class);
    }

    private static String textOf(Random random, int length) {
        final var chars = new char[length];
        for (var i = 0; i < length; i++) {
            chars[i] = (char) random.nextInt(Character.MAX_VALUE + 1);
        }
        return new String(chars);
    }

    @Test
    public void test_equal_strings_hash_equally() {
        final var random = new Random(2026);
        for (var length = 0; length <= LONGEST; length++) {
            final var text = textOf(random, length);
            final var first = new SaltedKey(new JsonString(text));
            final var second = new SaltedKey(new JsonString(new String(text.toCharArray())));

            assertEquals(first, second, text);
            assertEquals(first.hashCode(), second.hashCode(), text);
        }
    }

    @Test
    public void test_lengths_not_a_multiple_of_four_hash_all_characters() throws ReflectiveOperationException {
        final var random = new Random(7);
        final var seed = processSeed();
        for (var length = 1; length <= LONGEST; length++) {
            final var text = textOf(random, length);
            final var original = seededHash(seed, text);
            for (var position = 0; position < length; position++) {
                final var chars = text.toCharArray();
                chars[position] ^= (char) (1 + random.nextInt(Character.MAX_VALUE));
                assertNotEquals(original, seededHash(seed, new String(chars)), length + "@" + position);
            }
            assertNotEquals(original, seededHash(seed, text + '\u0000'), "appended at " + length);
            assertNotEquals(original, seededHash(seed, text.substring(0, length - 1)), "truncated at " + length);
        }
    }

    @Test
    public void test_the_seed_changes_the_hash() throws ReflectiveOperationException {
        final var random = new Random(11);
        final var seed = processSeed();
        for (var length = 0; length <= LONGEST; length++) {
            final var text = textOf(random, length);
            final var key = new SaltedKey(new JsonString(text));

            assertEquals(Long.hashCode(seededHash(seed, text)), key.hashCode(), text);
            assertNotEquals(seededHash(seed, text), seededHash(seed ^ (1L << random.nextInt(Long.SIZE)), text));
            assertNotEquals(seededHash(seed, text), seededHash(random.nextLong(), text));
            if (length >= CHARS_PER_ROUND) {
                assertNotEquals(seededHash(seed, text), seededHash(seed, random.nextLong() | 1L, text), text);
            }
        }
    }

    @Test
    public void test_strings_colliding_in_the_java_hash_stay_apart() {
        final var hashes = new HashSet<Integer>();
        final var keys = 1 << BLOCKS;
        for (var mask = 0; mask < keys; mask++) {
            final var text = new StringBuilder();
            for (var block = 0; block < BLOCKS; block++) {
                text.append((mask >>> block & 1) == 0 ? "Aa" : "BB");
            }
            hashes.add(new SaltedKey(new JsonString(text.toString())).hashCode());
        }

        assertTrue(hashes.size() >= keys - 16, () -> hashes.size() + " distinct hashes");
    }

    @Test
    public void test_equality_still_compares_the_element() {
        assertNotEquals(new SaltedKey(new JsonString("Aa")), new SaltedKey(new JsonString("BB")));
        assertNotEquals(new SaltedKey(new JsonString("abcd")), new SaltedKey(new JsonString("ABCD")));
        assertNotEquals(new SaltedKey(new JsonString("1")), new SaltedKey(new JsonNumber(1)));
        assertEquals(new SaltedKey(new JsonString("abcdefg")), new SaltedKey(new JsonString("abcdefg")));
    }
}

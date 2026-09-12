package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.utils.VectorUtils;

public class VectorUtilsSimHashFormatTest {
    private static final long SIMHASH_SEED = 0x9E3779B97F4A7C15L;

    private static String referenceSimHash(double[] vector, int bits) {
        final var random = new Random(SIMHASH_SEED);
        final var sb = new StringBuilder(bits);
        for (var plane = 0; plane < bits; plane++) {
            var dot = 0.0;
            for (double v : vector) {
                dot += v * random.nextGaussian();
            }
            sb.append(dot >= 0 ? '1' : '0');
        }
        return sb.toString();
    }

    private static double[] vectorOfLength(int dimension, long seed) {
        final var vector = new double[dimension];
        var state = seed * 6364136223846793005L + 1442695040888963407L;
        for (var i = 0; i < dimension; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            vector[i] = ((state >>> 11) / (double) (1L << 53)) * 2 - 1;
        }
        return vector;
    }

    @Test
    public void test_matches_the_reference_implementation_across_dimensions() {
        for (final var dimension : new int[]{1, 2, 3, 4, 8, 64, 128, 384, 768, 1536}) {
            final var vector = vectorOfLength(dimension, dimension * 31L);
            assertEquals(referenceSimHash(vector, 16), VectorUtils.simHash(vector, 16),
                    "signature must be byte-identical at dimension " + dimension);
        }
    }

    @Test
    public void test_matches_the_reference_implementation_across_bit_widths() {
        final var vector = vectorOfLength(32, 7L);
        for (final var bits : new int[]{1, 2, 8, 16, 24, 32, 64}) {
            assertEquals(referenceSimHash(vector, bits), VectorUtils.simHash(vector, bits),
                    "signature must be byte-identical at " + bits + " bits");
        }
    }

    @Test
    public void test_planes_are_not_shared_between_dimensions() {
        final var narrow = vectorOfLength(8, 11L);
        final var wide = vectorOfLength(64, 11L);

        final var narrowFirst = VectorUtils.simHash(narrow, 16);
        final var wideFirst = VectorUtils.simHash(wide, 16);
        final var narrowAgain = VectorUtils.simHash(narrow, 16);
        final var wideAgain = VectorUtils.simHash(wide, 16);

        assertEquals(referenceSimHash(narrow, 16), narrowFirst);
        assertEquals(referenceSimHash(wide, 16), wideFirst);
        assertEquals(narrowFirst, narrowAgain);
        assertEquals(wideFirst, wideAgain);
    }

    @Test
    public void test_repeated_calls_are_stable_under_interleaving() {
        final var a = vectorOfLength(16, 1L);
        final var b = vectorOfLength(16, 2L);
        final var expectedA = referenceSimHash(a, 16);
        final var expectedB = referenceSimHash(b, 16);

        for (var i = 0; i < 20; i++) {
            assertEquals(expectedA, VectorUtils.simHash(a, 16));
            assertEquals(expectedB, VectorUtils.simHash(b, 16));
        }
    }

    @Test
    public void test_empty_vector_matches_the_reference() {
        final var empty = new double[0];
        assertEquals(referenceSimHash(empty, 16), VectorUtils.simHash(empty, 16));
    }

    @Test
    public void test_zero_bits_produces_an_empty_signature() {
        assertEquals("", VectorUtils.simHash(vectorOfLength(4, 3L), 0));
    }

    @Test
    public void test_many_distinct_dimensions_stay_correct_past_the_cache_bound() {
        for (var dimension = 1; dimension <= 24; dimension++) {
            final var vector = vectorOfLength(dimension, 99L);
            assertEquals(referenceSimHash(vector, 8), VectorUtils.simHash(vector, 8),
                    "dimension " + dimension + " must be correct whether or not its planes are cached");
        }
    }
}

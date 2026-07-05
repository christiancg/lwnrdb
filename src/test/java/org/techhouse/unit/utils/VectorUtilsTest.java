package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.utils.VectorUtils;

public class VectorUtilsTest {
    @Test
    public void test_cosine_identical_is_one() {
        assertEquals(1.0, VectorUtils.cosineSimilarity(new double[]{1.0, 2.0, 3.0}, new double[]{1.0, 2.0, 3.0}), 1e-9);
    }

    @Test
    public void test_cosine_orthogonal_is_zero() {
        assertEquals(0.0, VectorUtils.cosineSimilarity(new double[]{1.0, 0.0}, new double[]{0.0, 1.0}), 1e-9);
    }

    @Test
    public void test_cosine_opposite_is_minus_one() {
        assertEquals(-1.0, VectorUtils.cosineSimilarity(new double[]{1.0, 1.0}, new double[]{-1.0, -1.0}), 1e-9);
    }

    @Test
    public void test_cosine_zero_vector_is_nan() {
        assertTrue(Double.isNaN(VectorUtils.cosineSimilarity(new double[]{0.0, 0.0}, new double[]{1.0, 1.0})));
    }

    @Test
    public void test_cosine_mismatched_length_is_nan() {
        assertTrue(Double.isNaN(VectorUtils.cosineSimilarity(new double[]{1.0, 2.0}, new double[]{1.0, 2.0, 3.0})));
    }

    @Test
    public void test_simhash_is_deterministic_and_sized() {
        final var vector = new double[]{0.3, -0.7, 1.2, 0.0};

        final var first = VectorUtils.simHash(vector, 16);
        final var second = VectorUtils.simHash(vector, 16);

        assertEquals(first, second);
        assertEquals(16, first.length());
        assertTrue(first.chars().allMatch(c -> c == '0' || c == '1'));
    }

    // Positive scaling keeps the direction, so the signature is unchanged.
    @Test
    public void test_simhash_is_scale_invariant() {
        assertEquals(VectorUtils.simHash(new double[]{1.0, 2.0, 3.0}, 16),
                VectorUtils.simHash(new double[]{5.0, 10.0, 15.0}, 16));
    }
}

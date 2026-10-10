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

    @Test
    public void test_simhash_is_scale_invariant() {
        assertEquals(VectorUtils.simHash(new double[]{1.0, 2.0, 3.0}, 16),
                VectorUtils.simHash(new double[]{5.0, 10.0, 15.0}, 16));
    }

    private static double textbookCosine(double[] a, double[] b) {
        var dot = 0.0;
        var normA = 0.0;
        var normB = 0.0;
        for (var i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    @Test
    public void test_cosine_of_huge_components_is_their_true_cosine() {
        assertEquals(1.0, VectorUtils.cosineSimilarity(new double[]{1e200, 1e200}, new double[]{1.0, 1.0}), 1e-12);
    }

    @Test
    public void test_cosine_of_a_huge_and_an_ordinary_component_is_not_flattened_to_zero() {
        assertEquals(1.0, VectorUtils.cosineSimilarity(new double[]{1e200, 1.0}, new double[]{1.0, 0.0}), 1e-12);
    }

    @Test
    public void test_cosine_of_tiny_components_is_their_true_cosine() {
        assertEquals(1.0, VectorUtils.cosineSimilarity(new double[]{1e-200, 1e-200}, new double[]{1.0, 1.0}), 1e-12);
    }

    @Test
    public void test_cosine_with_a_non_finite_component_is_nan() {
        assertTrue(Double.isNaN(
                VectorUtils.cosineSimilarity(new double[]{Double.POSITIVE_INFINITY, 1.0}, new double[]{1.0, 1.0})));
        assertTrue(Double.isNaN(VectorUtils.cosineSimilarity(new double[]{1.0, 1.0}, new double[]{Double.NaN, 1.0})));
    }

    @Test
    public void test_cosine_of_ordinary_vectors_matches_the_textbook_formula() {
        for (var round = 0; round < 50; round++) {
            final var a = new double[8];
            final var b = new double[8];
            for (var i = 0; i < a.length; i++) {
                a[i] = Math.sin(round * 8.0 + i) * (i + 1);
                b[i] = Math.cos(round * 3.7 + i * 1.3) * (8 - i);
            }
            assertEquals(textbookCosine(a, b), VectorUtils.cosineSimilarity(a, b), 1e-12);
        }
    }
}

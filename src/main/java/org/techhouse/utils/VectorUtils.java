package org.techhouse.utils;

import java.util.Random;

// Cosine similarity and the SimHash locality-sensitive hashing behind the vector similarity index (the
// SimHash signature plays the role for vectors that the geohash plays for geo points).
public final class VectorUtils {
    private VectorUtils() {
    }

    // Fixed seed so the hyperplanes are reproducible without persisting any state: same vector, same signature.
    private static final long SIMHASH_SEED = 0x9E3779B97F4A7C15L;

    // NaN when the similarity is undefined (mismatched dimensions or a zero vector), not 0.0 — 0.0 is a
    // valid cosine (orthogonal), so the caller must not confuse "cannot compare" with "not similar".
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            return Double.NaN;
        }
        var dot = 0.0;
        var normA = 0.0;
        var normB = 0.0;
        for (var i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // One bit per hyperplane (sign of the dot product). Drawing all planes from one fixed-seed generator
    // in a fixed order keeps signatures comparable across vectors of the same dimension.
    public static String simHash(double[] vector, int bits) {
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
}

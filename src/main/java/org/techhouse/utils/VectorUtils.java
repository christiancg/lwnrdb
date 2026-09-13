package org.techhouse.utils;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public final class VectorUtils {
    private VectorUtils() {
    }

    // Fixed seed so the hyperplanes are reproducible without persisting any state: same vector, same signature.
    private static final long SIMHASH_SEED = 0x9E3779B97F4A7C15L;

    private static final Map<Long, double[]> PLANES = new ConcurrentHashMap<>();
    private static final int MAX_CACHED_PLANE_SHAPES = 8;

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

    public static String simHash(double[] vector, int bits) {
        final var dimension = vector.length;
        final var planes = planesFor(bits, dimension);
        final var signature = new char[bits];
        var offset = 0;
        for (var plane = 0; plane < bits; plane++) {
            var dot = 0.0;
            for (var i = 0; i < dimension; i++) {
                dot += vector[i] * planes[offset + i];
            }
            signature[plane] = dot >= 0 ? '1' : '0';
            offset += dimension;
        }
        return new String(signature);
    }

    private static double[] planesFor(int bits, int dimension) {
        final var key = ((long) bits << 32) | (dimension & 0xFFFFFFFFL);
        final var cached = PLANES.get(key);
        if (cached != null) {
            return cached;
        }
        final var planes = drawPlanes(bits, dimension);
        if (PLANES.size() >= MAX_CACHED_PLANE_SHAPES) {
            return planes;
        }
        final var raced = PLANES.putIfAbsent(key, planes);
        return raced != null ? raced : planes;
    }

    private static double[] drawPlanes(int bits, int dimension) {
        final var random = new Random(SIMHASH_SEED);
        final var planes = new double[bits * dimension];
        for (var i = 0; i < planes.length; i++) {
            planes[i] = random.nextGaussian();
        }
        return planes;
    }
}

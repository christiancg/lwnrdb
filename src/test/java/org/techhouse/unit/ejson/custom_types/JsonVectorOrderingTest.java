package org.techhouse.unit.ejson.custom_types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonVector;

public class JsonVectorOrderingTest {
    private static JsonVector vector(double... components) {
        final var text = new StringBuilder("#vector(");
        for (var i = 0; i < components.length; i++) {
            if (i > 0) {
                text.append(',');
            }
            text.append(components[i]);
        }
        return new JsonVector(text.append(')').toString());
    }

    private static List<JsonVector> sample() {
        final var vectors = new ArrayList<JsonVector>();
        var seed = 987654321L;
        for (var i = 0; i < 60; i++) {
            final var components = new double[8];
            for (var j = 0; j < components.length; j++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                components[j] = ((seed >>> 20) % 2000) / 100.0 - 10.0;
            }
            vectors.add(vector(components));
        }
        return vectors;
    }

    @Test
    public void test_compareToCustom_agrees_with_compare_on_the_raw_vector() {
        final var vectors = sample();
        for (final var a : vectors) {
            for (final var b : vectors) {
                assertEquals(Integer.signum(a.compare(b.vector())), Integer.signum(a.compareToCustom(b)),
                        "comparing two JsonVector values must match comparing against the raw components");
            }
        }
    }

    @Test
    public void test_compareToCustom_is_antisymmetric() {
        final var vectors = sample();
        for (final var a : vectors) {
            for (final var b : vectors) {
                assertEquals(Integer.signum(a.compareToCustom(b)), -Integer.signum(b.compareToCustom(a)));
            }
        }
    }

    @Test
    public void test_compareToCustom_is_zero_only_for_an_equal_vector() {
        final var a = vector(1.0, 2.0, 3.0);
        assertEquals(0, a.compareToCustom(vector(1.0, 2.0, 3.0)));
        assertNotEquals(0, a.compareToCustom(vector(1.0, 2.0, 3.5)));
    }

    @Test
    public void test_a_longer_vector_sharing_a_prefix_is_distinguished() {
        final var shorter = vector(1.0, 2.0);
        final var longer = vector(1.0, 2.0, 3.0);

        assertNotEquals(0, shorter.compareToCustom(longer));
        assertEquals(Integer.signum(shorter.compare(longer.vector())), Integer.signum(shorter.compareToCustom(longer)));
    }

    @Test
    public void test_sorting_with_compareToCustom_matches_sorting_with_compare() {
        final var viaCustom = new ArrayList<>(sample());
        final var viaRaw = new ArrayList<>(sample());
        viaCustom.sort(JsonVector::compareToCustom);
        viaRaw.sort((a, b) -> a.compare(b.vector()));

        assertEquals(viaRaw.stream().map(JsonVector::getValue).toList(),
                viaCustom.stream().map(JsonVector::getValue).toList(),
                "the memoized both-sides comparison must produce the same order as the original");
    }

    @Test
    public void test_equal_vectors_share_a_signature() {
        assertEquals(vector(1.5, -2.5, 3.5).simHash(), vector(1.5, -2.5, 3.5).simHash());
    }
}

package org.techhouse.unit.ejson.custom_types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

public class JsonVectorTest {
    // Parses a valid "#vector(...)" string into its component array.
    @Test
    public void test_parse_valid_vector() {
        final var vector = new JsonVector("#vector(0.1,0.2,0.3)");

        assertArrayEquals(new double[]{0.1, 0.2, 0.3}, vector.vector());
        assertEquals("0.1,0.2,0.3", vector.stringDataValue());
        assertEquals("vector", vector.getCustomTypeName());
    }

    // Builds the wire value from a component array.
    @Test
    public void test_construct_from_array() {
        final var vector = new JsonVector(new double[]{1.5, 2.5, 3.5});

        assertEquals("#vector(1.5,2.5,3.5)", vector.getValue());
    }

    // An empty vector is rejected.
    @Test
    public void test_parse_empty_throws() {
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonVector("#vector()"));
    }

    // A non-numeric component is rejected.
    @Test
    public void test_parse_invalid_number_throws() {
        assertThrows(WrongFormatCustomTypeException.class, () -> new JsonVector("#vector(1.0,abc)"));
    }

    // Default constructor yields an empty, null-valued instance (used by the factory for reflection).
    @Test
    public void test_default_constructor_is_empty() {
        final var vector = new JsonVector();

        assertEquals("", vector.getValue());
        assertNull(vector.getCustomValue());
    }

    // compare == 0 exactly for equal vectors and non-zero otherwise.
    @Test
    public void test_compare_equality_and_ordering() {
        final var a = new JsonVector(new double[]{1.0, 2.0, 3.0});

        assertEquals(0, a.compare(new double[]{1.0, 2.0, 3.0}));
        assertNotEquals(0, a.compare(new double[]{3.0, 2.0, 1.0}));
        assertNotEquals(0, a.compare(new double[]{1.0, 2.0, 3.0, 4.0}));
    }

    // A vector and any positive scaling of it point the same way, so they share the SimHash signature
    // (the clustering the index relies on); an opposite vector produces a different signature.
    @Test
    public void test_simhash_clusters_by_direction() {
        final var base = new JsonVector(new double[]{1.0, 2.0, 3.0}).simHash();
        final var scaled = new JsonVector(new double[]{2.0, 4.0, 6.0}).simHash();
        final var opposite = new JsonVector(new double[]{-1.0, -2.0, -3.0}).simHash();

        assertEquals(base, scaled);
        assertNotEquals(base, opposite);
    }

    @Test
    public void test_ranking_operator_names() {
        final var vector = new JsonVector(new double[]{0.0, 0.0});

        assertEquals(Set.of("nearest"), vector.customRankingOperatorNames());
        assertTrue(vector.customOperatorNames().isEmpty());
    }

    // nearest scores by cosine similarity: identical direction -> 1, orthogonal -> 0.
    @Test
    public void test_nearest_scores_cosine() {
        final var vector = new JsonVector(new double[]{1.0, 0.0});

        assertEquals(1.0,
                vector.applyCustomRankingOperator("nearest", nearestArgs(new JsonVector(new double[]{2.0, 0.0}))),
                1e-9);
        assertEquals(0.0,
                vector.applyCustomRankingOperator("nearest", nearestArgs(new JsonVector(new double[]{0.0, 5.0}))),
                1e-9);
    }

    // The nearest query vector may also arrive as a raw "#vector(...)" string.
    @Test
    public void test_nearest_target_as_string() {
        final var vector = new JsonVector(new double[]{1.0, 0.0});
        final var args = nearestArgs(new JsonString("#vector(1.0,0.0)"));

        assertEquals(1.0, vector.applyCustomRankingOperator("nearest", args), 1e-9);
    }

    @Test
    public void test_nearest_bad_target_throws() {
        final var vector = new JsonVector(new double[]{1.0, 0.0});
        final var args = nearestArgs(new JsonNumber(5));

        assertThrows(WrongFormatCustomTypeException.class, () -> vector.applyCustomRankingOperator("nearest", args));
    }

    @Test
    public void test_unknown_ranking_operator_throws() {
        final var vector = new JsonVector(new double[]{1.0, 0.0});

        assertThrows(UnsupportedOperationException.class, () -> vector.applyCustomRankingOperator("nope", Map.of()));
    }

    // The vector type declares no predicate operators, so applyCustomOperator is unsupported.
    @Test
    public void test_predicate_operator_unsupported() {
        final var vector = new JsonVector(new double[]{1.0, 0.0});

        assertThrows(UnsupportedOperationException.class, () -> vector.applyCustomOperator("nearest", Map.of()));
    }

    private static Map<String, JsonBaseElement> nearestArgs(JsonBaseElement target) {
        return Map.of("value", target);
    }
}

package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.data.FieldIndexEntry;

public class FieldIndexZeroKeyTest {
    @Test
    public void test_negative_zero_and_positive_zero_share_a_file_key() {
        assertEquals(FieldIndexEntry.indexKeyOf(0.0), FieldIndexEntry.indexKeyOf(-0.0));
    }

    @Test
    public void test_negative_zero_and_positive_zero_compare_equal() {
        assertTrue(FieldIndexEntry.sameIndexedValue(0.0, -0.0),
                "the on-disk key is the same for both, so an equality that disagreed made a -0.0 write create a"
                        + " fresh entry that then overwrote the shared line and discarded every id in it");
        assertTrue(FieldIndexEntry.sameIndexedValue(-0.0, 0.0));
    }

    @Test
    public void test_zero_normalisation_leaves_other_values_alone() {
        assertEquals(0, FieldIndexEntry.compareIndexedNumbers(2.5, 2.5));
        assertTrue(FieldIndexEntry.compareIndexedNumbers(-1.0, 1.0) < 0);
        assertTrue(FieldIndexEntry.compareIndexedNumbers(1.0, -1.0) > 0);
        assertEquals(0, FieldIndexEntry.compareIndexedNumbers(Double.NaN, Double.NaN));
    }

    @Test
    public void test_an_integer_and_an_equal_double_compare_equal() {
        assertTrue(FieldIndexEntry.sameIndexedValue(2, 2.0));
    }
}

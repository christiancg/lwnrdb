package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public class JsonUtilsCustomOrderTest {
    private static final int TIMSORT_THRESHOLD = 64;

    private static List<String> ascendingValues(JsonBaseElement... elements) {
        final var sorted = new ArrayList<>(List.of(elements));
        sorted.sort(JsonUtils::compareSortKeysAscending);
        return sorted.stream().map(element -> element.asJsonString().getValue()).toList();
    }

    @Test
    public void test_geo_sort_order_matches_the_type_comparator() {
        final var ten = new JsonGeo("#geo(10.0,5.0)");
        final var nine = new JsonGeo("#geo(9.0,5.0)");
        final var eight = new JsonGeo("#geo(8.0,5.0)");

        assertEquals(List.of("#geo(8.0,5.0)", "#geo(9.0,5.0)", "#geo(10.0,5.0)"), ascendingValues(ten, nine, eight),
                "SORT must use the geohash order the range operators use, not the wire text order");
        assertEquals(Integer.signum(ten.compare(nine.getCustomValue())),
                Integer.signum(JsonUtils.compareSortKeysAscending(ten, nine)));
    }

    @Test
    public void test_vector_sort_order_matches_the_type_comparator() {
        final var first = new JsonVector("#vector(1.0,0.0)");
        final var second = new JsonVector("#vector(0.5,0.9)");

        assertEquals(Integer.signum(first.compare(second.getCustomValue())),
                Integer.signum(JsonUtils.compareSortKeysAscending(first, second)),
                "SORT must use the SimHash order the vector index is built in");
    }

    @Test
    public void test_equal_instants_with_different_spellings_tie() {
        final var withSeconds = new JsonDateTime("#datetime(2024-01-01T10:00:00)");
        final var withoutSeconds = new JsonDateTime("#datetime(2024-01-01T10:00)");

        assertEquals(0, JsonUtils.compareSortKeysAscending(withSeconds, withoutSeconds),
                "values the engine reports as equal must tie so SORT can break on _id");
        assertEquals(0, JsonUtils.compareSortKeysDescending(withSeconds, withoutSeconds));
        assertEquals(0,
                JsonUtils.compareSortKeysAscending(new JsonTime("#time(10:00:00)"), new JsonTime("#time(10:00)")));
    }

    @Test
    public void test_descending_is_the_exact_inverse_for_customs() {
        final var later = new JsonDateTime("#datetime(2024-06-01T10:00:00)");
        final var earlier = new JsonDateTime("#datetime(2024-01-01T10:00:00)");

        assertEquals(-Integer.signum(JsonUtils.compareSortKeysAscending(later, earlier)),
                Integer.signum(JsonUtils.compareSortKeysDescending(later, earlier)));
    }

    @Test
    public void test_a_custom_value_sorts_after_every_plain_string() {
        final var geo = new JsonGeo("#geo(8.0,5.0)");

        assertTrue(JsonUtils.compareSortKeysAscending(new JsonString("zzz"), geo) < 0);
        assertTrue(JsonUtils.compareSortKeysAscending(new JsonString("#geo(1.0,1.0)"), geo) < 0,
                "a custom-shaped plain string must not interleave with real custom values");
        assertTrue(JsonUtils.compareSortKeysAscending(geo, new JsonString("")) > 0);
    }

    @Test
    public void test_different_custom_types_order_deterministically() {
        final var geo = new JsonGeo("#geo(8.0,5.0)");
        final var time = new JsonTime("#time(10:00)");

        final var forward = JsonUtils.compareSortKeysAscending(geo, time);
        assertTrue(forward != 0, "two custom types are never the same value");
        assertEquals(forward, JsonUtils.compareSortKeysAscending(geo, time));
        assertEquals(-Integer.signum(forward), Integer.signum(JsonUtils.compareSortKeysAscending(time, geo)));
    }

    @Test
    public void test_mixed_values_sort_without_violating_the_comparator_contract() {
        final var mixed = new ArrayList<JsonBaseElement>();
        for (var i = 0; i < TIMSORT_THRESHOLD; i++) {
            mixed.add(new JsonGeo("#geo(" + (i % 80 - 40) + ".5,5.0)"));
            mixed.add(new JsonDateTime("#datetime(2024-01-01T" + String.format("%02d", i % 24) + ":00:00)"));
            mixed.add(new JsonTime("#time(" + String.format("%02d", i % 24) + ":30)"));
            mixed.add(new JsonString("#geo(" + i + ".0,0.0)"));
            mixed.add(new JsonString("value-" + i));
            mixed.add(new JsonNumber(i));
            mixed.add(new JsonBoolean(i % 2 == 0));
        }
        final var array = mixed.toArray(JsonBaseElement[]::new);

        Arrays.sort(array, JsonUtils::compareSortKeysAscending);

        for (var i = 1; i < array.length; i++) {
            assertTrue(JsonUtils.compareSortKeysAscending(array[i - 1], array[i]) <= 0,
                    "the sorted sequence must be non-decreasing under the comparator that produced it");
        }
    }
}

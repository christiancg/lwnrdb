package org.techhouse.unit.cache.heap;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.techhouse.cache.heap.HeapLayout;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.GeoPoint;

public class HeapLayoutTest {
    private static final HeapLayout G1_COMPACT = new HeapLayout(8, 12, 4);
    private static final HeapLayout G1_LEGACY = new HeapLayout(12, 16, 4);
    private static final HeapLayout ZGC_COMPACT = new HeapLayout(8, 12, 8);
    private static final HeapLayout ZGC_LEGACY = new HeapLayout(12, 16, 8);
    private static final long ONE_GB = 1L << 30;
    private static final long THIRTY_TWO_GB = 32L << 30;

    private static final Map<Class<?>, long[]> MEASURED_SIZES = measuredSizes();

    private static Map<Class<?>, long[]> measuredSizes() {
        final var sizes = new LinkedHashMap<Class<?>, long[]>();
        sizes.put(String.class, new long[]{24, 24, 24, 32});
        sizes.put(HeapLayout.jdkClass("java.util.LinkedHashMap$Entry"), new long[]{32, 40, 56, 56});
        sizes.put(LinkedHashMap.class, new long[]{56, 64, 80, 88});
        sizes.put(HashMap.class, new long[]{40, 48, 56, 64});
        sizes.put(HeapLayout.jdkClass("java.util.HashMap$Node"), new long[]{24, 32, 40, 40});
        sizes.put(HashSet.class, new long[]{16, 16, 16, 24});
        sizes.put(ArrayList.class, new long[]{24, 24, 24, 32});
        sizes.put(JsonObject.class, new long[]{16, 16, 16, 24});
        sizes.put(JsonString.class, new long[]{16, 16, 16, 24});
        sizes.put(JsonBoolean.class, new long[]{16, 16, 16, 24});
        sizes.put(JsonArray.class, new long[]{16, 16, 16, 24});
        sizes.put(JsonNumber.class, new long[]{16, 24, 24, 24});
        sizes.put(JsonDateTime.class, new long[]{16, 24, 24, 32});
        sizes.put(JsonGeo.class, new long[]{24, 24, 32, 40});
        sizes.put(JsonVector.class, new long[]{24, 24, 32, 40});
        sizes.put(GeoPoint.class, new long[]{24, 32, 24, 32});
        sizes.put(LocalDateTime.class, new long[]{16, 24, 24, 32});
        sizes.put(LocalDate.class, new long[]{16, 24, 16, 24});
        sizes.put(Integer.class, new long[]{16, 16, 16, 16});
        sizes.put(Double.class, new long[]{16, 24, 16, 24});
        sizes.put(PkIndexEntry.class, new long[]{56, 56, 64, 72});
        return sizes;
    }

    private static Stream<Arguments> measuredLayouts() {
        return Stream.of(Arguments.of(G1_COMPACT, 0), Arguments.of(G1_LEGACY, 1), Arguments.of(ZGC_COMPACT, 2),
                Arguments.of(ZGC_LEGACY, 3));
    }

    @ParameterizedTest
    @MethodSource("measuredLayouts")
    public void test_shallow_sizes_match_the_measured_layouts(HeapLayout layout, int column) {
        for (final var measured : MEASURED_SIZES.entrySet()) {
            assertEquals(measured.getValue()[column], layout.shallowSize(measured.getKey()),
                    measured.getKey().getName() + " under " + layout);
        }
        assertEquals(48L, layout.arraySize(Double.BYTES, 4), "double[4] under " + layout);
    }

    @Test
    public void test_array_sizes_align_wide_elements() {
        assertEquals(24L, G1_COMPACT.arraySize(1, 8));
        assertEquals(24L, G1_LEGACY.arraySize(1, 8));
        assertEquals(48L, G1_COMPACT.arraySize(Double.BYTES, 4));
        assertEquals(48L, G1_LEGACY.arraySize(Double.BYTES, 4));
        assertEquals(56L, G1_COMPACT.referenceArraySize(10));
        assertEquals(96L, ZGC_COMPACT.referenceArraySize(10));
        assertEquals(16L, G1_COMPACT.arraySize(1, 0));
    }

    @Test
    public void test_string_size_counts_latin1_and_utf16() {
        final var shallow = G1_COMPACT.shallowSize(String.class);
        assertEquals(shallow + 16L, G1_COMPACT.stringSize("abc"));
        assertEquals(shallow + G1_COMPACT.arraySize(1, 1), G1_COMPACT.stringSize("ü"));
        assertEquals(shallow + G1_COMPACT.arraySize(1, 2), G1_COMPACT.stringSize("€"));
        assertEquals(shallow + G1_COMPACT.arraySize(1, 8), G1_COMPACT.stringSize("ab€cd"));
    }

    @Test
    public void test_hash_table_capacity() {
        assertEquals(0L, G1_COMPACT.hashTableSize(0));
        assertEquals(G1_COMPACT.referenceArraySize(16), G1_COMPACT.hashTableSize(1));
        assertEquals(G1_COMPACT.referenceArraySize(16), G1_COMPACT.hashTableSize(12));
        assertEquals(G1_COMPACT.referenceArraySize(32), G1_COMPACT.hashTableSize(13));
        assertEquals(G1_COMPACT.referenceArraySize(32768), G1_COMPACT.hashTableSize(20000));
    }

    @Test
    public void test_presized_hash_table_capacity_follows_new_hash_set() {
        assertEquals(0L, G1_COMPACT.presizedHashTableSize(0));
        assertEquals(G1_COMPACT.referenceArraySize(2), G1_COMPACT.presizedHashTableSize(1));
        assertEquals(G1_COMPACT.referenceArraySize(8), G1_COMPACT.presizedHashTableSize(5));
        assertEquals(G1_COMPACT.referenceArraySize(16), G1_COMPACT.presizedHashTableSize(12));
        assertEquals(G1_COMPACT.referenceArraySize(32), G1_COMPACT.presizedHashTableSize(13));
        assertEquals(G1_COMPACT.referenceArraySize(32768), G1_COMPACT.presizedHashTableSize(20000));
    }

    @Test
    public void test_array_list_capacity() {
        assertEquals(0L, G1_COMPACT.arrayListArraySize(0));
        assertEquals(G1_COMPACT.referenceArraySize(10), G1_COMPACT.arrayListArraySize(3));
        assertEquals(G1_COMPACT.referenceArraySize(10), G1_COMPACT.arrayListArraySize(10));
        assertEquals(G1_COMPACT.referenceArraySize(15), G1_COMPACT.arrayListArraySize(11));
        assertEquals(G1_COMPACT.referenceArraySize(22), G1_COMPACT.arrayListArraySize(16));
    }

    @Test
    public void test_from_detects_compact_headers_and_references() {
        final var g1 = List.of("G1 Young Generation", "G1 Old Generation");
        assertEquals(G1_COMPACT, HeapLayout.from(List.of(), g1, ONE_GB));
        assertEquals(G1_LEGACY, HeapLayout.from(List.of("-XX:-UseCompactObjectHeaders"), g1, ONE_GB));
        assertEquals(ZGC_COMPACT, HeapLayout.from(List.of(), List.of("ZGC Minor Cycles", "ZGC Major Cycles"), ONE_GB));
        assertEquals(ZGC_COMPACT, HeapLayout.from(List.of("-XX:-UseCompressedOops"), g1, ONE_GB));
        assertEquals(ZGC_COMPACT, HeapLayout.from(List.of(), g1, THIRTY_TWO_GB));
        assertEquals(G1_COMPACT, HeapLayout.from(List.of("-XX:+UseCompactObjectHeaders"), g1, THIRTY_TWO_GB - 1));
    }

    @Test
    public void test_current_matches_from_on_this_jvm() {
        final var collectorNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList();
        final var expected = HeapLayout.from(ManagementFactory.getRuntimeMXBean().getInputArguments(), collectorNames,
                Runtime.getRuntime().maxMemory());
        final var detected = HeapLayout.current();
        assertEquals(expected, detected);
        assertSame(detected, HeapLayout.current(), "the layout is detected once per JVM");
    }

    @Test
    public void test_an_unknown_class_name_is_refused() {
        assertThrows(IllegalStateException.class, () -> HeapLayout.jdkClass("java.util.NoSuchClass"));
    }
}

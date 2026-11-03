package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.ejson.custom_types.JsonVector;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;
import org.techhouse.test.TestGlobals;

public class VectorIndexKeyTest extends FilterResolutionSupport {
    private static final String FIELD = "embedding";
    private static final JsonVector FIRST = new JsonVector("#vector(0.25,1.0,-3.5)");
    private static final JsonVector SECOND = new JsonVector("#vector(0.25,1.0,-3.25)");

    private void seed() throws IOException, InterruptedException {
        insert("first", FIELD, new JsonVector(FIRST.getValue()));
        insert("second", FIELD, new JsonVector(SECOND.getValue()));
        insert("third", FIELD, new JsonVector("#vector(9.0,-2.0,0.5)"));
        indexField(FIELD);
    }

    @Test
    public void test_key_is_signature_and_hash() throws IOException, InterruptedException {
        seed();

        final var indexFile = Path.of(TestGlobals.PATH, TestGlobals.DB, TestGlobals.COLL,
                TestGlobals.COLL + "-" + FIELD + "-JsonVector.idx");
        final var lines = Files.readAllLines(indexFile, StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        for (final var line : lines) {
            final var key = line.substring(0, line.indexOf('\u001f'));
            assertEquals(JsonVector.INDEX_KEY_LENGTH, key.length(), line);
            assertTrue(key.matches("[0-9a-f]+"), line);
            assertFalse(line.contains("#vector"), line);
        }
        final var firstKey = FIRST.indexKey();
        assertEquals(String.format("%04x", FIRST.signature()), firstKey.substring(0, 4));
        assertEquals(String.format("%016x", FIRST.contentHash()), firstKey.substring(4));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(firstKey + '\u001f' + "first")));

        final var resident = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                JsonVector.class);
        assertEquals(3, resident.size());
        for (final var entry : resident) {
            assertTrue(entry.getValue().isIndexKey());
            assertNull(entry.getValue().vector());
        }
    }

    @Test
    public void test_equal_vectors_in_two_spellings_share_one_key() {
        assertEquals(new JsonVector("#vector(1,2)").indexKey(), new JsonVector("#vector(1.0,2.00)").indexKey());
        assertNotEquals(new JsonVector("#vector(0.0,2.0)").indexKey(), new JsonVector("#vector(-0.0,2.0)").indexKey());
        final var parsed = JsonVector.ofIndexKey(FIRST.indexKey());
        assertEquals(FIRST.signature(), parsed.signature());
        assertEquals(0, (int) parsed.compareToCustom(FIRST));
        assertEquals(0, (int) FIRST.compareToCustom(parsed));
    }

    @Test
    public void test_equals_retests_candidates() throws IOException, InterruptedException {
        seed();
        final var resident = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                JsonVector.class);
        for (final var entry : resident) {
            if (entry.getValue().indexKey().equals(FIRST.indexKey())) {
                entry.getIds().add("second");
            }
        }

        assertEquals(List.of("first"), ids(filter(FieldOperatorType.EQUALS, FIELD, FIRST)));
        assertEquals(1, countOf(filter(FieldOperatorType.EQUALS, FIELD, FIRST)));
        assertEquals(List.of("first"), ids(filter(FieldOperatorType.IN, FIELD, arrayOf(FIRST))));
        assertEquals(1, countOf(filter(FieldOperatorType.IN, FIELD, arrayOf(FIRST))));
        assertEquals(List.of("second", "third"), ids(filter(FieldOperatorType.NOT_EQUALS, FIELD, FIRST)));
        assertEquals(2, countOf(filter(FieldOperatorType.NOT_IN, FIELD, arrayOf(FIRST))));
    }

    @Test
    public void test_readers_that_need_values_decline_a_vector_index() throws IOException, InterruptedException {
        seed();

        assertNull(IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, FIELD, ReadObserver.NONE));
        assertNull(IndexHelper.getDistinctIndexValues(TestGlobals.DB, TestGlobals.COLL, FIELD, ReadObserver.NONE));
        assertNull(IndexHelper.getSortedIdsForField(TestGlobals.DB, TestGlobals.COLL, FIELD, (_, _) -> 0, 10,
                ReadObserver.NONE));
        final var distinct = new DistinctAggregationStep(FIELD);
        assertEquals(3, rows(distinct).size());
        final var greater = new ArrayList<String>();
        for (final var stored : cache.getEntriesByIds(TestGlobals.DB, TestGlobals.COLL,
                Set.of("first", "second", "third"))) {
            final var vector = new JsonVector(stored.getData().get(FIELD).asJsonString().getValue());
            if (FIRST.compareToCustom(vector) < 0) {
                greater.add(stored.get_id());
            }
        }
        greater.sort(String::compareTo);
        assertEquals(greater, ids(filter(FieldOperatorType.GREATER_THAN, FIELD, FIRST)));
    }
}

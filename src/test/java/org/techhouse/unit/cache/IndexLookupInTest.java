package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;

public class IndexLookupInTest {
    private static void injectRealLocking(UserCache mock) {
        try {
            final var rlField = UserCache.class.getDeclaredField("rl");
            rlField.setAccessible(true);
            rlField.set(mock, new ResourceLocking());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    @Test
    public void test_numeric_in_matches_a_wire_parsed_integer_operand() throws IOException {
        final var cache = mock(UserCache.class);
        injectRealLocking(cache);
        final var dbName = "db";
        final var collName = "coll";
        final var fieldName = "score";
        final var arr = new JsonArray();
        arr.add(new JsonNumber("10"));
        arr.add(new JsonNumber("20"));
        final var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        final List<FieldIndexEntry<Number>> idx = List.of(new FieldIndexEntry<>(dbName, collName, 10.0, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, 30.0, Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        final var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"),
                "the parser yields Integer for an integral operand while index entries are always Double, so boxed"
                        + " set membership matched nothing and the query silently returned no rows");
        assertFalse(result.contains("id2"));
    }

    @Test
    public void test_numeric_not_in_matches_a_wire_parsed_integer_operand() throws IOException {
        final var cache = mock(UserCache.class);
        injectRealLocking(cache);
        final var dbName = "db";
        final var collName = "coll";
        final var fieldName = "score";
        final var arr = new JsonArray();
        arr.add(new JsonNumber("10"));
        final var operator = new FieldOperator(FieldOperatorType.NOT_IN, fieldName, arr);
        final List<FieldIndexEntry<Number>> idx = List.of(new FieldIndexEntry<>(dbName, collName, 10.0, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, 30.0, Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(eq(dbName), eq(collName), eq(fieldName), any())).thenReturn(null);
        when(cache.getHashIndexAndLoadIfNecessary(eq(dbName), eq(collName), eq(fieldName), any(IndexKind.class)))
                .thenReturn(null);
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        final var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result, "with no other index on the field NOT_IN is complement-safe and must use the index");
        assertTrue(result.contains("id2"));
        assertFalse(result.contains("id1"));
    }

    @Test
    public void test_an_empty_index_of_another_type_still_disqualifies_not_in() throws IOException {
        final var cache = mock(UserCache.class);
        injectRealLocking(cache);
        final var dbName = "db";
        final var collName = "coll";
        final var fieldName = "score";
        final var arr = new JsonArray();
        arr.add(new JsonNumber("10"));
        final var operator = new FieldOperator(FieldOperatorType.NOT_IN, fieldName, arr);
        final List<FieldIndexEntry<Number>> idx = List.of(new FieldIndexEntry<>(dbName, collName, 10.0, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, 30.0, Set.of("id2")));
        when(cache.getFieldIndexAndLoadIfNecessary(eq(dbName), eq(collName), eq(fieldName), any())).thenReturn(null);
        when(cache.getHashIndexAndLoadIfNecessary(eq(dbName), eq(collName), eq(fieldName), any(IndexKind.class)))
                .thenReturn(null);
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class)).thenReturn(idx);
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Boolean.class)).thenReturn(List.of());
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        final var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNull(result, "a zero-byte index file loads as an empty list, so leaving one behind costs the index path"
                + " - which is why removing an index's last line deletes the file");
    }

    @Test
    public void test_a_mixed_integral_and_fractional_in_list_matches_both() throws IOException {
        final var cache = mock(UserCache.class);
        injectRealLocking(cache);
        final var dbName = "db";
        final var collName = "coll";
        final var fieldName = "score";
        final var arr = new JsonArray();
        arr.add(new JsonNumber("5"));
        arr.add(new JsonNumber("2.5"));
        final var operator = new FieldOperator(FieldOperatorType.IN, fieldName, arr);
        final List<FieldIndexEntry<Number>> idx = List.of(new FieldIndexEntry<>(dbName, collName, 2.5, Set.of("id2")),
                new FieldIndexEntry<>(dbName, collName, 5.0, Set.of("id1")),
                new FieldIndexEntry<>(dbName, collName, 9.0, Set.of("id3")));
        when(cache.getFieldIndexAndLoadIfNecessary(dbName, collName, fieldName, Number.class)).thenReturn(idx);
        when(cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr)).thenCallRealMethod();

        final var result = cache.getIdsFromIndex(dbName, collName, fieldName, operator, arr);

        assertNotNull(result);
        assertTrue(result.contains("id1"));
        assertTrue(result.contains("id2"));
        assertFalse(result.contains("id3"));
    }
}

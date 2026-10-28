package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.RangeBounds;

public class UserCacheIdLookupTest {
    private static final String DB = "db";
    private static final String COLL = "coll";
    private static final String FIELD = "price";

    private static void injectRealLocking(UserCache mock) {
        try {
            final var rlField = UserCache.class.getDeclaredField("rl");
            rlField.setAccessible(true);
            rlField.set(mock, new ResourceLocking());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static FieldIndexEntry<Number> entry(Number value, String... ids) {
        return new FieldIndexEntry<>(DB, COLL, value, new HashSet<>(Set.of(ids)));
    }

    private static List<FieldIndexEntry<Number>> priceIndex() {
        return List.of(entry(10, "a"), entry(20, "b", "c"), entry(30, "d"), entry(40, "e"));
    }

    private static UserCache cacheOver(List<FieldIndexEntry<Number>> index) throws IOException {
        final var cache = mock(UserCache.class);
        injectRealLocking(cache);
        when(cache.getFieldIndexAndLoadIfNecessary(DB, COLL, FIELD, Number.class)).thenReturn(index);
        when(cache.getIdsFromIndex(eq(DB), eq(COLL), eq(FIELD), any(), any())).thenCallRealMethod();
        return cache;
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
    public void test_get_ids_from_index_matches_the_entries_for_equals_range_and_in() throws IOException {
        final var cache = cacheOver(priceIndex());

        final var equal = cache.getIdsFromIndex(DB, COLL, FIELD,
                new FieldOperator(FieldOperatorType.EQUALS, FIELD, new JsonNumber(20)), (Number) 20);
        final var greater = cache.getIdsFromIndex(DB, COLL, FIELD,
                new FieldOperator(FieldOperatorType.GREATER_THAN, FIELD, new JsonNumber(20)), (Number) 20);
        final var members = new JsonArray();
        members.add(new JsonNumber(10));
        members.add(new JsonNumber(40));
        final var in = cache.getIdsFromIndex(DB, COLL, FIELD, new FieldOperator(FieldOperatorType.IN, FIELD, members),
                members);

        assertEquals(Set.of("b", "c"), equal);
        assertEquals(Set.of("d", "e"), greater);
        assertEquals(Set.of("a", "e"), in);
    }

    @Test
    public void test_get_ids_from_index_result_does_not_see_a_later_index_mutation() throws IOException {
        final var index = priceIndex();
        final var cache = cacheOver(index);

        final var result = cache.getIdsFromIndex(DB, COLL, FIELD,
                new FieldOperator(FieldOperatorType.EQUALS, FIELD, new JsonNumber(20)), (Number) 20);
        index.get(1).getIds().add("late");
        index.get(1).getIds().remove("b");

        assertEquals(Set.of("b", "c"), result);
    }

    @Test
    public void test_get_ids_from_index_result_can_be_mutated_without_touching_the_index() throws IOException {
        final var index = priceIndex();
        final var cache = cacheOver(index);

        final var result = cache.getIdsFromIndex(DB, COLL, FIELD,
                new FieldOperator(FieldOperatorType.EQUALS, FIELD, new JsonNumber(20)), (Number) 20);
        result.clear();

        assertEquals(Set.of("b", "c"), index.get(1).getIds());
    }

    @Test
    public void test_get_ids_from_index_returns_null_when_the_field_has_no_index() throws IOException {
        final var cache = cacheOver(null);

        final var result = cache.getIdsFromIndex(DB, COLL, FIELD,
                new FieldOperator(FieldOperatorType.EQUALS, FIELD, new JsonNumber(20)), (Number) 20);

        assertNull(result);
    }

    @Test
    public void test_get_ids_from_index_of_an_unmatched_value_is_empty() throws IOException {
        final var cache = cacheOver(priceIndex());

        final var result = cache.getIdsFromIndex(DB, COLL, FIELD,
                new FieldOperator(FieldOperatorType.EQUALS, FIELD, new JsonNumber(25)), (Number) 25);

        assertTrue(result.isEmpty());
    }

    @Test
    public void test_get_ids_in_range_matches_the_entries_and_ignores_a_later_index_mutation() throws Exception {
        final var index = priceIndex();
        final var userCache = mock(UserCache.class);
        when(userCache.getFieldIndexAndLoadIfNecessary(DB, COLL, FIELD, Number.class)).thenReturn(index);
        final var cache = IocContainer.get(Cache.class);
        final var original = TestUtils.getPrivateField(cache, "userCache", UserCache.class);
        TestUtils.setPrivateField(cache, "userCache", userCache);
        try {
            final var bounds = new RangeBounds(FIELD,
                    List.of(new RangeBounds.Bound(20, FieldOperatorType.GREATER_THAN_EQUALS),
                            new RangeBounds.Bound(30, FieldOperatorType.SMALLER_THAN_EQUALS)));

            final var result = cache.getIdsInRange(DB, COLL, bounds);
            index.get(1).getIds().add("late");

            assertEquals(Set.of("b", "c", "d"), result);
        } finally {
            TestUtils.setPrivateField(cache, "userCache", original);
        }
    }

    @Test
    public void test_get_ids_in_range_returns_null_when_the_field_has_no_index() throws Exception {
        final var userCache = mock(UserCache.class);
        when(userCache.getFieldIndexAndLoadIfNecessary(DB, COLL, FIELD, Number.class)).thenReturn(null);
        final var cache = IocContainer.get(Cache.class);
        final var original = TestUtils.getPrivateField(cache, "userCache", UserCache.class);
        TestUtils.setPrivateField(cache, "userCache", userCache);
        try {
            final var bounds = new RangeBounds(FIELD,
                    List.of(new RangeBounds.Bound(20, FieldOperatorType.GREATER_THAN)));

            assertNull(cache.getIdsInRange(DB, COLL, bounds));
        } finally {
            TestUtils.setPrivateField(cache, "userCache", original);
        }
    }
}

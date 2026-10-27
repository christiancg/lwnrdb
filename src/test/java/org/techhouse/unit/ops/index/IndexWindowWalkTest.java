package org.techhouse.unit.ops.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.analyze.ReadObserver;
import org.techhouse.bckg_ops.PendingIndexWrites;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.index.IndexWindowWalk;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.operators.FieldOperator;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexWindowWalkTest {
    private static final String FIELD = "v";
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private FieldIndexBatchSupport support;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        support = new FieldIndexBatchSupport();
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new HashSet<>(entry.getIndexes());
        indexes.add(FIELD);
        entry.setIndexes(indexes);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void seed(int count, int value) throws Exception {
        final var ids = new ArrayList<String>();
        for (var i = 0; i < count; i++) {
            final var id = "n" + value + "-" + i;
            ids.add(id);
            support.put(id, new JsonNumber(value));
        }
        support.applyBatchHoldingTheCollection(FIELD, ids);
    }

    private static FieldOperator operator(FieldOperatorType type, JsonBaseElement value) {
        return new FieldOperator(type, FIELD, value);
    }

    private IndexWindowWalk open(FieldOperator operator, int firstPage) throws Exception {
        return IndexWindowWalk.open(operator, TestGlobals.DB, TestGlobals.COLL, firstPage, ReadObserver.NONE);
    }

    private static List<String> take(IndexWindowWalk walk, int count) {
        final var taken = new ArrayList<String>();
        while (taken.size() < count && walk.hasNext()) {
            taken.add(walk.next());
        }
        return taken;
    }

    private void move(List<String> ids, int value) throws Exception {
        for (final var id : ids) {
            support.put(id, new JsonNumber(value));
        }
        support.applyBatchHoldingTheCollection(FIELD, ids);
    }

    private static Set<String> idsOf(String prefix, int count) {
        final var ids = new TreeSet<String>();
        for (var i = 0; i < count; i++) {
            ids.add(prefix + i);
        }
        return ids;
    }

    @Test
    public void test_a_walk_resumes_after_a_concurrent_batch_without_repeating_or_losing_an_id() throws Exception {
        seed(300, 1);
        seed(20, 2);
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var walk = open(operator(FieldOperatorType.EQUALS, new JsonNumber(1)), 4);
            assertNotNull(walk);
            final var emitted = new ArrayList<>(take(walk, 4));
            final var stillMatching = new TreeSet<>(idsOf("n1-", 300));
            final var moved = new ArrayList<String>();
            for (final var id : stillMatching) {
                if (!emitted.contains(id) && moved.size() < 10) {
                    moved.add(id);
                }
            }
            move(moved, 2);
            moved.forEach(stillMatching::remove);
            move(List.of("n2-0", "n2-1"), 1);
            stillMatching.addAll(List.of("n2-0", "n2-1"));

            emitted.addAll(take(walk, Integer.MAX_VALUE));

            assertEquals(emitted.size(), Set.copyOf(emitted).size(), "no id is emitted twice");
            assertTrue(emitted.containsAll(stillMatching), "every id still matching is emitted");
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_an_entry_emptied_between_pages_resumes_at_the_next_value() throws Exception {
        seed(40, 1);
        seed(30, 2);
        seed(5, 0);
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var walk = open(operator(FieldOperatorType.GREATER_THAN_EQUALS, new JsonNumber(1)), 3);
            final var emitted = new ArrayList<>(take(walk, 3));
            final var rest = new ArrayList<>(idsOf("n1-", 40));
            rest.removeAll(emitted);
            move(rest, 0);

            emitted.addAll(take(walk, Integer.MAX_VALUE));

            assertEquals(emitted.size(), Set.copyOf(emitted).size());
            assertTrue(emitted.containsAll(idsOf("n2-", 30)));
            assertTrue(emitted.stream().noneMatch(id -> id.startsWith("n0-")));
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_a_list_that_is_not_resident_is_walked_in_one_page() throws Exception {
        seed(50, 3);
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var configuration = Configuration.getInstance();
        final long original = configuration.getMaxMemoryBytes();
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1L);
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var walk = open(operator(FieldOperatorType.SMALLER_THAN_EQUALS, new JsonNumber(3)), 2);

            assertNotNull(walk);
            assertEquals(50, TestUtils.getPrivateField(walk, "page", List.class).size());
            assertEquals(idsOf("n3-", 50), new TreeSet<>(take(walk, Integer.MAX_VALUE)));
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", original);
        }
    }

    @Test
    public void test_in_walks_each_distinct_run_once() throws Exception {
        seed(10, 1);
        seed(10, 2);
        seed(10, 3);
        final var operands = new JsonArray();
        operands.add(new JsonNumber(3));
        operands.add(new JsonNumber(1));
        operands.add(new JsonNumber(1.0));
        operands.add(new JsonNumber(7));
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var emitted = take(open(operator(FieldOperatorType.IN, operands), 3), Integer.MAX_VALUE);

            final var expected = idsOf("n1-", 10);
            expected.addAll(idsOf("n3-", 10));
            assertEquals(20, emitted.size());
            assertEquals(expected, new TreeSet<>(emitted));
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_a_pending_id_comes_first_and_only_once() throws Exception {
        seed(10, 1);
        support.put("n1-3", new JsonNumber(1));
        IocContainer.get(PendingIndexWrites.class).mark(TestGlobals.DB, TestGlobals.COLL, "n1-3");
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            final var emitted = take(open(operator(FieldOperatorType.EQUALS, new JsonNumber(1)), 2), Integer.MAX_VALUE);

            assertEquals("n1-3", emitted.getFirst());
            assertEquals(idsOf("n1-", 10), new TreeSet<>(emitted));
            assertEquals(10, emitted.size());
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
            IocContainer.get(PendingIndexWrites.class).clearCollection(TestGlobals.DB, TestGlobals.COLL);
        }
    }

    @Test
    public void test_the_walk_declines_what_the_index_cannot_window() throws Exception {
        seed(5, 1);
        final var mixed = new JsonArray();
        mixed.add(new JsonNumber(1));
        mixed.add(new JsonString("1"));
        assertNull(open(operator(FieldOperatorType.EQUALS, new JsonNumber(1)), 2), "no collection lock");
        locks.lockRead(TestGlobals.DB, TestGlobals.COLL);
        try {
            assertNull(open(operator(FieldOperatorType.NOT_EQUALS, new JsonNumber(1)), 2));
            assertNull(open(operator(FieldOperatorType.IN, mixed), 2));
            assertNull(open(operator(FieldOperatorType.IN, new JsonArray()), 2));
            assertNull(open(operator(FieldOperatorType.GREATER_THAN, new JsonString("a")), 2));
            assertNull(open(operator(FieldOperatorType.EQUALS, new JsonString("absent type")), 2));
            assertNull(open(operator(FieldOperatorType.CONTAINS, new JsonString("a")), 2));
            assertNull(IndexWindowWalk.open(new FieldOperator(FieldOperatorType.EQUALS, "unindexed", new JsonNumber(1)),
                    TestGlobals.DB, TestGlobals.COLL, 2, ReadObserver.NONE));
            assertNotNull(open(operator(FieldOperatorType.EQUALS, new JsonNumber(1)), 2));
        } finally {
            locks.releaseRead(TestGlobals.DB, TestGlobals.COLL);
        }
    }
}

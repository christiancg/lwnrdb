package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class IndexHelperNumericValueTest {
    private static final String FIELD = "bucket";
    private Cache cache;

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        cache.putAdminCollectionEntry(new AdminCollEntry(TestGlobals.DB, TestGlobals.COLL),
                new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "1", 0, 100, 0));
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private void save(String id, Number bucket) {
        final var obj = new JsonObject();
        obj.addProperty(Globals.PK_FIELD, id);
        obj.addProperty(FIELD, bucket);
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL,
                DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj));
    }

    private void indexAll(List<String> ids) throws IOException, InterruptedException {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
        for (final var id : ids) {
            IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, id);
        }
    }

    private List<FieldIndexEntry<Number>> index() throws IOException {
        return cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class);
    }

    private Set<String> idsFor(double value) throws IOException {
        return index().stream().filter(e -> e.getValue().doubleValue() == value).findFirst()
                .map(FieldIndexEntry::getIds).orElse(Set.of());
    }

    @Test
    public void test_every_document_sharing_a_numeric_value_keeps_its_id_in_the_index()
            throws IOException, InterruptedException {
        final var ids = List.of("d0", "d1", "d2", "d3", "d4", "d5");
        for (var i = 0; i < ids.size(); i++) {
            save(ids.get(i), i % 2);
        }

        indexAll(ids);

        assertEquals(2, index().size(), "two distinct bucket values must produce two index entries");
        assertEquals(Set.of("d0", "d2", "d4"), idsFor(0d));
        assertEquals(Set.of("d1", "d3", "d5"), idsFor(1d));
    }

    @Test
    public void test_a_single_low_cardinality_value_collects_every_id() throws IOException, InterruptedException {
        final var ids = List.of("a", "b", "c", "d", "e");
        for (final var id : ids) {
            save(id, 7);
        }

        indexAll(ids);

        assertEquals(1, index().size());
        assertEquals(Set.copyOf(ids), idsFor(7d));
    }

    @Test
    public void test_values_above_integer_max_share_one_entry() throws IOException, InterruptedException {
        final var ids = List.of("big1", "big2", "big3");
        for (final var id : ids) {
            save(id, 10_000_000_000d);
        }

        indexAll(ids);

        assertEquals(1, index().size(), "a value above Integer.MAX_VALUE must not fan out into several entries");
        assertEquals(Set.copyOf(ids), idsFor(10_000_000_000d));
    }

    @Test
    public void test_fractional_values_share_one_entry() throws IOException, InterruptedException {
        final var ids = List.of("f1", "f2", "f3");
        for (final var id : ids) {
            save(id, 0.25d);
        }

        indexAll(ids);

        assertEquals(1, index().size());
        assertEquals(Set.copyOf(ids), idsFor(0.25d));
    }

    @Test
    public void test_negative_values_share_one_entry() throws IOException, InterruptedException {
        final var ids = List.of("n1", "n2");
        for (final var id : ids) {
            save(id, -3);
        }

        indexAll(ids);

        assertEquals(1, index().size());
        assertEquals(Set.copyOf(ids), idsFor(-3d));
    }

    @Test
    public void test_moving_a_document_between_values_leaves_the_others_intact()
            throws IOException, InterruptedException {
        final var ids = List.of("m0", "m1", "m2");
        for (final var id : ids) {
            save(id, 5);
        }
        indexAll(ids);
        assertEquals(Set.copyOf(ids), idsFor(5d));

        save("m1", 9);
        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, "m1");

        assertEquals(Set.of("m0", "m2"), idsFor(5d));
        assertEquals(Set.of("m1"), idsFor(9d));
    }
}

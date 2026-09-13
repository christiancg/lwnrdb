package org.techhouse.unit.cache;

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
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class FieldIndexCopyTest {
    private static final String FIELD = "score";
    private Cache cache;

    @BeforeEach
    public void setUp() throws IOException, NoSuchFieldException, IllegalAccessException, InterruptedException {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cache = IocContainer.get(Cache.class);
        for (var i = 0; i < 6; i++) {
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString("d" + i));
            obj.addProperty(FIELD, i % 2);
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
            entry.set_id("d" + i);
            cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    private static Set<String> idsOf(List<org.techhouse.data.FieldIndexEntry<?>> entries, double value) {
        return entries.stream().filter(e -> ((Number) e.getValue()).doubleValue() == value).findFirst()
                .map(e -> Set.copyOf(e.getIds())).orElse(Set.of());
    }

    @Test
    public void test_a_consumer_snapshot_survives_a_concurrent_index_rewrite() throws IOException {
        final var snapshot = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, FIELD);
        assertNotNull(snapshot);
        final var before = idsOf(snapshot, 0d);
        assertEquals(Set.of("d0", "d2", "d4"), before);

        final var cached = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class);
        assertNotNull(cached);
        for (final var entry : cached) {
            entry.getIds().remove("d0");
            entry.getIds().remove("d1");
        }

        assertEquals(before, idsOf(snapshot, 0d),
                "the consumer's copy must not see the background writer's in-place removal");
    }

    @Test
    public void test_two_consumer_snapshots_do_not_share_id_sets() throws IOException {
        final var first = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, FIELD);
        final var second = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, FIELD);
        assertNotNull(first);
        assertNotNull(second);

        first.forEach(entry -> entry.getIds().clear());

        assertEquals(Set.of("d1", "d3", "d5"), idsOf(second, 1d),
                "one consumer clearing its own copy must not empty another's");
    }

    @Test
    public void test_the_cached_index_is_reloaded_after_an_in_place_mutation() throws IOException {
        final var cached = cache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD, Number.class);
        assertNotNull(cached);
        cached.forEach(entry -> entry.getIds().remove("d0"));
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);

        final var reloaded = IndexHelper.getIndexEntriesForField(TestGlobals.DB, TestGlobals.COLL, FIELD);

        assertNotNull(reloaded);
        assertTrue(idsOf(reloaded, 0d).contains("d0"), "eviction must send the next read back to the .idx files");
    }
}

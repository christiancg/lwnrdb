package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class FieldIndexAdmissionTest {
    private static final String FIELD = "status";

    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        for (var i = 0; i < 4; i++) {
            final var obj = new JsonObject();
            obj.add(Globals.PK_FIELD, new JsonString("d" + i));
            obj.add(FIELD, new JsonString(i % 2 == 0 ? "open" : "closed"));
            final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, obj);
            entry.set_id("d" + i);
            TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        }
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, FIELD);
        cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL).setIndexes(Set.of(FIELD));
        userCache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, FIELD);
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private Object cachedFieldIndexes() throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
        };
        final var map = TestUtils.getPrivateField(userCache, "fieldIndexMap", type);
        return map.get(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_lock_free_read_does_not_publish_the_field_index()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var loaded = userCache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                String.class);

        assertNotNull(loaded, "the reader must still be answered from disk");
        assertNull(cachedFieldIndexes(),
                "a lock-free reader publishing this tier let a load that started before a DROP_COLLECTION"
                        + " reinstate the pre-drop index after the eviction");
    }

    @Test
    public void test_a_locked_reader_still_publishes_the_field_index()
            throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            userCache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertNotNull(cachedFieldIndexes(),
                "a lock holder is the only caller allowed to publish, and must still do so");
    }

    @Test
    public void test_a_lock_free_read_still_answers_the_same_entries_as_a_locked_one()
            throws IOException, InterruptedException {
        final var unlocked = userCache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD,
                String.class);

        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        final List<FieldIndexEntry<String>> locked;
        try {
            locked = userCache.getFieldIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL, FIELD, String.class);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        org.junit.jupiter.api.Assertions.assertEquals(locked.size(), unlocked.size(),
                "refusing to publish must not change what the lock-free reader is answered");
    }
}

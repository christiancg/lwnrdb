package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.IndexHelper;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class IndexHelperBatchCacheFlipTest {
    private static final long TOO_SMALL_TO_ADMIT = 1L;
    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final FileSystem realFs = IocContainer.get(FileSystem.class);
    private volatile long originalMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        originalMaxMemory = configuration.getMaxMemoryBytes();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(userCache, "fs", realFs);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", originalMaxMemory);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private DbEntry document(String id, String field, String value) throws Exception {
        final var object = new JsonObject();
        object.addProperty(Globals.PK_FIELD, id);
        object.addProperty(field, value);
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, object);
        TestUtils.cacheEntry(cache, TestGlobals.DB, TestGlobals.COLL, entry);
        return entry;
    }

    private void indexField(String field) throws Exception {
        IndexHelper.createIndex(TestGlobals.DB, TestGlobals.COLL, field);
        final var entry = cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL);
        final var indexes = new HashSet<>(entry.getIndexes());
        indexes.add(field);
        entry.setIndexes(indexes);
        cache.evictFieldIndexAllTypes(TestGlobals.DB, TestGlobals.COLL, field);
    }

    private void admitFromTheNthStringLoad(String field, int admittingLoad) throws Exception {
        final var stringLoads = new AtomicInteger();
        final var spied = spy(realFs);
        doAnswer(call -> {
            final var result = call.callRealMethod();
            if (stringLoads.incrementAndGet() == admittingLoad) {
                TestUtils.setPrivateField(configuration, "maxMemoryBytes", Globals.CACHE_UNLIMITED);
            }
            return result;
        }).when(spied).readWholeFieldIndexFiles(anyString(), anyString(), eq(field), eq(String.class));
        TestUtils.setPrivateField(userCache, "fs", spied);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", TOO_SMALL_TO_ADMIT);
    }

    private Map<String, Set<String>> idsByValueOnDisk(String field) throws Exception {
        final var byValue = new java.util.HashMap<String, Set<String>>();
        for (final var entry : realFs.readWholeFieldIndexFiles(TestGlobals.DB, TestGlobals.COLL, field, String.class)) {
            byValue.put(entry.getValue(), new HashSet<>(entry.getIds()));
        }
        return byValue;
    }

    @Test
    public void test_a_batch_whose_index_becomes_cacheable_midway_keeps_every_id() throws Exception {
        for (var admittingLoad = 1; admittingLoad <= 4; admittingLoad++) {
            final var field = "moved" + admittingLoad;
            final var x = document("x" + admittingLoad, field, "old");
            final var y = document("y" + admittingLoad, field, "old");
            document("z" + admittingLoad, field, "other");
            indexField(field);
            x.getData().addProperty(field, "fresh");
            y.getData().addProperty(field, "fresh");

            admitFromTheNthStringLoad(field, admittingLoad);
            IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of(x.get_id(), y.get_id()));
            TestUtils.setPrivateField(userCache, "fs", realFs);
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", originalMaxMemory);

            final var onDisk = idsByValueOnDisk(field);
            assertEquals(Set.of(x.get_id(), y.get_id()), onDisk.get("fresh"),
                    "admitted at String load " + admittingLoad + ": " + onDisk);
            assertNull(onDisk.get("old"), "admitted at String load " + admittingLoad + ": " + onDisk);
        }
    }

    @Test
    public void test_a_moved_id_is_never_left_under_its_old_value() throws Exception {
        for (var admittingLoad = 1; admittingLoad <= 4; admittingLoad++) {
            final var field = "shifted" + admittingLoad;
            final var x = document("a" + admittingLoad, field, "first");
            final var y = document("b" + admittingLoad, field, "second");
            indexField(field);
            x.getData().addProperty(field, "second");
            y.getData().addProperty(field, "first");

            admitFromTheNthStringLoad(field, admittingLoad);
            IndexHelper.bulkUpdateIndexes(TestGlobals.DB, TestGlobals.COLL, List.of(x.get_id(), y.get_id()));
            TestUtils.setPrivateField(userCache, "fs", realFs);
            TestUtils.setPrivateField(configuration, "maxMemoryBytes", originalMaxMemory);

            final var onDisk = idsByValueOnDisk(field);
            assertEquals(Set.of(y.get_id()), onDisk.get("first"),
                    "admitted at String load " + admittingLoad + ": " + onDisk);
            assertEquals(Set.of(x.get_id()), onDisk.get("second"),
                    "admitted at String load " + admittingLoad + ": " + onDisk);
        }
    }

    @Test
    public void test_maintenance_never_admits_a_field_index() throws Exception {
        final var field = "unadmitted";
        final var x = document("solo", field, "before");
        indexField(field);
        x.getData().addProperty(field, "after");
        final var spied = spy(realFs);
        final var admittedDuringBatch = new AtomicInteger();
        doAnswer(call -> {
            final var fieldIndexes = TestUtils.getPrivateField(userCache, "fieldIndexMap",
                    new ReflectionUtils.TypeToken<Map<String, Map<String, List<FieldIndexEntry<?>>>>>() {
                    });
            final var collection = fieldIndexes.get(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
            if (collection != null && !collection.isEmpty()) {
                admittedDuringBatch.incrementAndGet();
            }
            return call.callRealMethod();
        }).when(spied).readWholeFieldIndexFiles(anyString(), anyString(), eq(field), eq(String.class));
        TestUtils.setPrivateField(userCache, "fs", spied);

        IndexHelper.updateIndexes(TestGlobals.DB, TestGlobals.COLL, x.get_id());

        assertEquals(0, admittedDuringBatch.get());
        assertEquals(Set.of(x.get_id()), idsByValueOnDisk(field).get("after"));
    }
}

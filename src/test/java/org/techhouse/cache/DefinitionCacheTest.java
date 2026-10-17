package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class DefinitionCacheTest {
    private static final String DB = "db";
    private static final String NAME = "proc";
    private static final int PUBLISH_LOOKUP = 2;

    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final BoundedLruCache<String> values = new BoundedLruCache<>(Integer.MAX_VALUE, Long.MAX_VALUE, _ -> 1L);
    private final PartitionedMissCache misses = new PartitionedMissCache(Integer.MAX_VALUE);
    private final CountDownLatch publishing = new CountDownLatch(1);
    private final CountDownLatch mayPublish = new CountDownLatch(1);
    private final AtomicInteger loaderThreadLookups = new AtomicInteger();
    private volatile Thread loaderThread;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject schema() {
        final var schema = new JsonObject();
        schema.addProperty("type", "object");
        return schema;
    }

    private void writeSchema() throws Exception {
        fs.writeCollectionSchema(TestGlobals.DB, TestGlobals.COLL, new EJson().toJson(schema()));
    }

    private BoundedLruCache<String> valuesPausingAtPublish() {
        if (Thread.currentThread() == loaderThread && loaderThreadLookups.incrementAndGet() == PUBLISH_LOOKUP) {
            publishing.countDown();
            try {
                if (!mayPublish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the test never released the paused publish");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return values;
    }

    @Test
    public void test_a_remove_between_the_generation_check_and_the_publish_is_not_undone() throws Exception {
        final var definitions = new DefinitionCache<>(this::valuesPausingAtPublish, () -> misses, "miss:",
                (_, _) -> "loaded");
        final var id = Cache.getCollectionIdentifier(DB, NAME);
        final var loaded = new String[1];
        final var loader = new Thread(() -> loaded[0] = definitions.get(DB, NAME));
        loaderThread = loader;
        loader.start();
        assertTrue(publishing.await(5, TimeUnit.SECONDS), "the load must reach its publish");

        final var remover = new Thread(() -> definitions.remove(id));
        remover.start();
        remover.join(200);
        mayPublish.countDown();
        loader.join(5000);
        remover.join(5000);

        assertEquals("loaded", loaded[0]);
        assertNull(values.get(id),
                "a delete that lands after the generation check must still win over the load's publish");
    }

    @Test
    public void test_a_removal_during_an_in_flight_load_is_not_undone() throws Exception {
        writeSchema();
        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));

        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);
        fs.deleteCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL),
                "a delete that answered OK must not keep validating writes against the schema it removed");
    }

    @Test
    public void test_a_reload_after_a_removal_sees_the_new_definition() throws Exception {
        writeSchema();
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL),
                "the generation guard must only skip the publish, never turn a present definition into a miss");
    }

    @Test
    public void test_a_miss_is_not_cached_across_a_concurrent_create() throws Exception {
        assertNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL));

        writeSchema();
        cache.removeCollectionSchema(TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(cache.getCollectionSchema(TestGlobals.DB, TestGlobals.COLL),
                "a stale negative entry would report a schema that exists on disk as absent and skip validation");
    }
}

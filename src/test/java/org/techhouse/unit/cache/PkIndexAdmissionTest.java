package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cache.UserCache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

public class PkIndexAdmissionTest {
    private final UserCache userCache = IocContainer.get(UserCache.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

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

    private Object cachedPkIndex() throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<PkIndexEntry>>>() {
        };
        final var map = TestUtils.getPrivateField(userCache, "pkIndexMap", type);
        return map.get(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_lock_free_read_does_not_publish_the_pk_index()
            throws IOException, NoSuchFieldException, IllegalAccessException {
        final var loaded = userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);

        assertNotNull(loaded);
        assertNull(cachedPkIndex(),
                "a dirty read holds no collection lock, so its snapshot can be torn; publishing it let a"
                        + " lock-free reader overwrite a concurrent writer's in-place mutated list");
    }

    @Test
    public void test_a_locked_reader_still_publishes_the_pk_index()
            throws IOException, InterruptedException, NoSuchFieldException, IllegalAccessException {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL);
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertNotNull(cachedPkIndex(), "a lock holder is the only caller allowed to publish, and must still do so");
    }

    @Test
    public void test_a_lock_free_read_still_answers_from_disk() throws IOException, InterruptedException {
        locks.lock(TestGlobals.DB, TestGlobals.COLL);
        try {
            userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL)
                    .add(new PkIndexEntry(TestGlobals.DB, TestGlobals.COLL, "x1", 0, 1, 0));
        } finally {
            locks.release(TestGlobals.DB, TestGlobals.COLL);
        }

        assertEquals(1, userCache.getPkIndexAndLoadIfNecessary(TestGlobals.DB, TestGlobals.COLL).size(),
                "a published index is still served to a lock-free reader; only republishing is refused");
    }
}

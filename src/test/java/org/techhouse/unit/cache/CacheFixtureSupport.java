package org.techhouse.unit.cache;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.techhouse.data.DbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;
import org.techhouse.utils.ReflectionUtils;

abstract class CacheFixtureSupport {
    @BeforeEach
    public void setUp() throws NoSuchFieldException, IllegalAccessException, IOException {
        TestUtils.standardInitialSetup();
    }

    @AfterEach
    public void tearDown() throws NoSuchFieldException, IllegalAccessException {
        TestUtils.standardTearDown();
    }

    protected static void injectCachedEntry(String collId, DbEntry entry)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, Map<String, DbEntry>>>() {
        };
        final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
        final var collectionMap = TestUtils.getPrivateField(userCache, "collectionMap", type);
        final var inner = collectionMap.computeIfAbsent(collId, _ -> new ConcurrentHashMap<>());
        inner.put(entry.get_id(), entry);
    }

    protected static void injectPkIndex(String collId, List<org.techhouse.data.PkIndexEntry> entries)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.PkIndexEntry>>>() {
        };
        final var userCache = IocContainer.get(org.techhouse.cache.UserCache.class);
        TestUtils.getPrivateField(userCache, "pkIndexMap", type).put(collId, entries);
    }

    protected static void injectPages(String collId, List<org.techhouse.data.admin.AdminPageEntry> pageList)
            throws NoSuchFieldException, IllegalAccessException {
        final var type = new ReflectionUtils.TypeToken<Map<String, List<org.techhouse.data.admin.AdminPageEntry>>>() {
        };
        final var adminCache = IocContainer.get(org.techhouse.cache.AdminCache.class);
        final var pages = TestUtils.getPrivateField(TestUtils.pageCacheOf(adminCache), "pages", type);
        pages.put(collId, pageList);
    }
}

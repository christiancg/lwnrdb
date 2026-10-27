package org.techhouse.unit.cache;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.techhouse.cache.CacheResidents;
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

    protected static void injectCachedEntry(String collId, DbEntry entry) {
        CacheResidents.residentDocument(IocContainer.get(org.techhouse.cache.UserCache.class), collId, entry);
    }

    protected static void injectPkIndex(String collId, List<org.techhouse.data.PkIndexEntry> entries) {
        CacheResidents.residentPk(IocContainer.get(org.techhouse.cache.UserCache.class), collId, entries);
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

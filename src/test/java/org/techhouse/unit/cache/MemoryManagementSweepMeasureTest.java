package org.techhouse.unit.cache;

import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.AccessKind;
import org.techhouse.cache.CacheableResource;
import org.techhouse.cache.Measure;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MemoryManagementSweepMeasureTest {
    private static final long MAX_BYTES = 1000L;

    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final Configuration configuration = Configuration.getInstance();
    private final UserCache userCache = mock(UserCache.class);
    private UserCache realCache;
    private long realMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        realMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        realCache = TestUtils.getPrivateField(memoryManagement, "userCache", UserCache.class);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", MAX_BYTES);
        TestUtils.setPrivateField(memoryManagement, "userCache", userCache);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(memoryManagement, "userCache", realCache);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", realMaxMemory);
        TestUtils.standardTearDown();
    }

    private void rememberedBytes(long bytes) {
        when(userCache.listCacheableResources(Measure.REMEMBERED))
                .thenReturn(List.of(new CacheableResource(AccessKind.COLLECTION, "userDb", "coll", null, bytes)));
    }

    @Test
    public void test_a_sweep_below_the_floor_never_measures_in_full() {
        rememberedBytes(MAX_BYTES / 2 - 1);

        memoryManagement.runEvictionSweep();

        verify(userCache, atLeastOnce()).listCacheableResources(Measure.REMEMBERED);
        verify(userCache, never()).listCacheableResources(Measure.FULL);
    }

    @Test
    public void test_a_sweep_at_the_floor_measures_in_full() {
        rememberedBytes(MAX_BYTES / 2);

        memoryManagement.runEvictionSweep();

        verify(userCache).listCacheableResources(Measure.FULL);
    }

    @Test
    public void test_a_sweep_above_the_cap_measures_in_full() {
        rememberedBytes(MAX_BYTES * 2);

        memoryManagement.runEvictionSweep();

        verify(userCache).listCacheableResources(Measure.FULL);
    }

    @Test
    public void test_an_empty_cache_is_not_measured_in_full() {
        memoryManagement.runEvictionSweep();

        verify(userCache, never()).listCacheableResources(Measure.FULL);
    }
}

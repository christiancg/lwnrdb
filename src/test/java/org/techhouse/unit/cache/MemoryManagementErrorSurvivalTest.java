package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.MemoryManagement;
import org.techhouse.cache.UserCache;
import org.techhouse.config.Configuration;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class MemoryManagementErrorSurvivalTest {
    private final MemoryManagement memoryManagement = IocContainer.get(MemoryManagement.class);
    private final Configuration configuration = Configuration.getInstance();
    private final UserCache failingCache = mock(UserCache.class);
    private UserCache realCache;
    private long realMaxMemory;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        realMaxMemory = TestUtils.getPrivateField(configuration, "maxMemoryBytes", Long.class);
        realCache = TestUtils.getPrivateField(memoryManagement, "userCache", UserCache.class);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", 1024L);
        TestUtils.setPrivateField(memoryManagement, "userCache", failingCache);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(memoryManagement, "userCache", realCache);
        TestUtils.setPrivateField(configuration, "maxMemoryBytes", realMaxMemory);
        resetOutOfMemoryFlag();
        TestUtils.standardTearDown();
    }

    private void resetOutOfMemoryFlag() throws Exception {
        runSweep();
    }

    private void runSweep() throws Exception {
        final Method sweep = MemoryManagement.class.getDeclaredMethod("runEvictionSweepSafely");
        sweep.setAccessible(true);
        try {
            sweep.invoke(memoryManagement);
        } catch (InvocationTargetException e) {
            throw (Exception) wrap(e.getCause());
        }
    }

    private static Throwable wrap(Throwable cause) {
        return cause instanceof Error error ? new SweepEscaped(error) : cause;
    }

    private static final class SweepEscaped extends Exception {
        SweepEscaped(Error error) {
            super(error);
        }
    }

    private void sweepThrows(Throwable failure) {
        doThrow(failure).when(failingCache).listCacheableResources(any());
    }

    private void sweepSucceeds() {
        doReturn(List.of()).when(failingCache).listCacheableResources(any());
    }

    @Test
    public void sweepSurvivesAnError() {
        sweepThrows(new StackOverflowError("deep"));
        assertDoesNotThrow(this::runSweep);
        assertDoesNotThrow(this::runSweep);
    }

    @Test
    public void aSingleOutOfMemoryErrorIsSwallowed() {
        sweepThrows(new OutOfMemoryError("once"));
        assertDoesNotThrow(this::runSweep);
        sweepSucceeds();
        assertDoesNotThrow(this::runSweep);
        sweepThrows(new OutOfMemoryError("again, not consecutive"));
        assertDoesNotThrow(this::runSweep);
        sweepSucceeds();
    }

    @Test
    public void anOutOfMemoryErrorInTwoConsecutiveRunsIsRethrown() {
        sweepThrows(new OutOfMemoryError("recurring"));
        assertDoesNotThrow(this::runSweep);
        final var escaped = assertThrows(SweepEscaped.class, this::runSweep);
        assertEquals(OutOfMemoryError.class, escaped.getCause().getClass());
        sweepSucceeds();
    }

    @Test
    public void flushSurvivesAnError() throws Exception {
        final Method flush = MemoryManagement.class.getDeclaredMethod("flushUsageProfileSafely");
        flush.setAccessible(true);
        final var tracker = TestUtils.usageTrackerOf(memoryManagement);
        final var realCounters = TestUtils.getPrivateField(tracker, "dirtyKeys", Object.class);
        TestUtils.setPrivateField(tracker, "dirtyKeys", null);
        try {
            assertDoesNotThrow(() -> flush.invoke(memoryManagement));
        } finally {
            TestUtils.setPrivateField(tracker, "dirtyKeys", realCounters);
        }
    }
}

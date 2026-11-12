package org.techhouse.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.techhouse.fault.FaultAction;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestUtils;

public class ForceMetricsTest {
    private static final String LOG_NAME = "orders.wal";
    private final FaultInjector injector = IocContainer.get(FaultInjector.class);

    @TempDir
    Path tmp;

    private RecordingForcer recorder;

    @AfterEach
    void tearDown() throws Exception {
        injector.releaseAll();
        TestUtils.setPrivateField(injector, "enabled", false);
        if (recorder != null) {
            recorder.close();
        }
        SyncedWrites.useForcer(Forcer.DEVICE);
    }

    private Path log() throws IOException {
        return Files.writeString(tmp.resolve(LOG_NAME), "x");
    }

    @Test
    public void test_every_successful_force_is_counted_and_timed() throws Exception {
        recorder = RecordingForcer.install(true);
        final var countBefore = ForceMetrics.count();
        final var timedBefore = ForceMetrics.durations().snapshot().count();
        final var failuresBefore = ForceMetrics.failures();

        SyncedWrites.content(log());
        SyncedWrites.entries(tmp);

        assertEquals(2L, ForceMetrics.count() - countBefore);
        assertEquals(2L, ForceMetrics.durations().snapshot().count() - timedBefore);
        assertEquals(0L, ForceMetrics.failures() - failuresBefore);
    }

    @Test
    public void test_a_failed_force_counts_as_a_failure_not_a_force() throws Exception {
        recorder = RecordingForcer.install(true);
        recorder.failingFor(".wal");
        final var countBefore = ForceMetrics.count();
        final var failuresBefore = ForceMetrics.failures();

        assertThrows(IOException.class, () -> SyncedWrites.content(log()));

        assertEquals(0L, ForceMetrics.count() - countBefore);
        assertEquals(1L, ForceMetrics.failures() - failuresBefore);
    }

    @Test
    public void test_an_injected_force_failure_counts_as_a_failure() throws Exception {
        recorder = RecordingForcer.install(true);
        TestUtils.setPrivateField(injector, "enabled", true);
        injector.arm(FaultPoint.FILE_FORCED, FaultAction.FAIL, LOG_NAME);
        final var failuresBefore = ForceMetrics.failures();

        assertThrows(IOException.class, () -> SyncedWrites.content(log()));

        assertEquals(1L, ForceMetrics.failures() - failuresBefore);
    }

    @Test
    public void test_unsynced_writes_force_nothing() throws Exception {
        recorder = RecordingForcer.install(false);
        final var countBefore = ForceMetrics.count();

        SyncedWrites.content(log());

        assertEquals(0L, ForceMetrics.count() - countBefore);
    }
}

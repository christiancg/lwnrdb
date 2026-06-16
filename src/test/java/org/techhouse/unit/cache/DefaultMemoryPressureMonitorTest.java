package org.techhouse.unit.cache;

import org.junit.jupiter.api.Test;
import org.techhouse.cache.DefaultMemoryPressureMonitor;

import static org.junit.jupiter.api.Assertions.*;

public class DefaultMemoryPressureMonitorTest {

    @Test
    public void test_snapshot_returns_valid_heap_ratio() {
        final var monitor = new DefaultMemoryPressureMonitor();
        final var snap = monitor.snapshot();
        assertTrue(snap.heapUsedRatio() >= 0.0, "heap ratio should be non-negative");
        assertTrue(snap.heapUsedRatio() <= 1.0 + 1e-6, "heap ratio should be <= 1");
    }

    @Test
    public void test_snapshot_reports_os_availability_on_hotspot() {
        final var monitor = new DefaultMemoryPressureMonitor();
        final var snap = monitor.snapshot();
        // CI uses Temurin (HotSpot); locally most JDKs do too. Either way, exercise both branches:
        if (snap.osMetricsAvailable()) {
            assertTrue(snap.osFreeRatio() >= 0.0);
            assertTrue(snap.osFreeRatio() <= 1.0 + 1e-6);
        } else {
            assertEquals(1.0, snap.osFreeRatio());
        }
    }

    @Test
    public void test_snapshot_multiple_calls_stable() {
        final var monitor = new DefaultMemoryPressureMonitor();
        // Repeated calls must not throw or change shape.
        for (int i = 0; i < 5; i++) {
            final var snap = monitor.snapshot();
            assertNotNull(snap);
        }
    }
}

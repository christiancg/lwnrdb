package org.techhouse.cache;

public interface MemoryPressureMonitor {
    record Snapshot(double heapUsedRatio, double osFreeRatio, boolean osMetricsAvailable) {
    }

    Snapshot snapshot();
}

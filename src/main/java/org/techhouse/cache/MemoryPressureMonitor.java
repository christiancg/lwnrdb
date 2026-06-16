package org.techhouse.cache;

public interface MemoryPressureMonitor {
    record Snapshot(double heapUsedRatio, long heapUsedBytes, double osFreeRatio,
                    boolean osMetricsAvailable) {
    }

    Snapshot snapshot();
}

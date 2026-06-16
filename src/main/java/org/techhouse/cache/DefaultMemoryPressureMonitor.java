package org.techhouse.cache;

import org.techhouse.log.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultMemoryPressureMonitor implements MemoryPressureMonitor {
    private static final Logger logger = Logger.logFor(DefaultMemoryPressureMonitor.class);
    private final MemoryMXBean memoryBean;
    private final com.sun.management.OperatingSystemMXBean osBean;
    private final AtomicBoolean osUnavailableWarned = new AtomicBoolean(false);

    public DefaultMemoryPressureMonitor() {
        this.memoryBean = ManagementFactory.getMemoryMXBean();
        final OperatingSystemMXBean rawOsBean = ManagementFactory.getOperatingSystemMXBean();
        if (rawOsBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            this.osBean = sunBean;
        } else {
            this.osBean = null;
        }
    }

    @Override
    public Snapshot snapshot() {
        final var heapUsage = memoryBean.getHeapMemoryUsage();
        final var used = heapUsage.getUsed();
        final var max = heapUsage.getMax();
        final var denom = max > 0 ? max : heapUsage.getCommitted();
        final double heapUsedRatio = denom > 0 ? (double) used / (double) denom : 0.0;
        if (osBean == null) {
            if (osUnavailableWarned.compareAndSet(false, true)) {
                logger.warning("Host OS memory metrics unavailable on this JVM; the OS-free-RAM " +
                        "pressure signal will be ignored.");
            }
            return new Snapshot(heapUsedRatio, used, 1.0, false);
        }
        final var freeOs = osBean.getFreeMemorySize();
        final var totalOs = osBean.getTotalMemorySize();
        final double osFreeRatio = totalOs > 0 ? (double) freeOs / (double) totalOs : 1.0;
        return new Snapshot(heapUsedRatio, used, osFreeRatio, true);
    }
}

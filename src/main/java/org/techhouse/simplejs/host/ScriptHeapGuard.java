package org.techhouse.simplejs.host;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.List;
import org.techhouse.simplejs.exceptions.ScriptMemoryException;

public final class ScriptHeapGuard {
    public static final String CEILING_MESSAGE = "Script stopped: the server heap is above its scripting ceiling";
    private static final long PERCENT = 100L;
    private static final List<MemoryPoolMXBean> HEAP_POOLS = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == MemoryType.HEAP).toList();
    private static final Runtime RUNTIME = Runtime.getRuntime();
    public static final int OFF = 0;
    private static volatile int ceilingPercent = OFF;

    private ScriptHeapGuard() {
    }

    public static void setCeilingPercent(int percent) {
        ceilingPercent = percent;
    }

    public static void check(long pendingBytes) {
        final var percent = ceilingPercent;
        if (percent == OFF) {
            return;
        }
        final var ceiling = RUNTIME.maxMemory() / PERCENT * percent;
        if (usedBytes() + pendingBytes <= ceiling) {
            return;
        }
        final var live = liveBytesAfterLastCollection();
        if (live >= 0 && live + pendingBytes <= ceiling / 2) {
            return;
        }
        throw new ScriptMemoryException(CEILING_MESSAGE);
    }

    static long usedBytes() {
        return RUNTIME.totalMemory() - RUNTIME.freeMemory();
    }

    static long liveBytesAfterLastCollection() {
        var live = -1L;
        for (final var pool : HEAP_POOLS) {
            final var usage = pool.getCollectionUsage();
            if (usage != null) {
                live = Math.max(live, 0L) + usage.getUsed();
            }
        }
        return live;
    }
}

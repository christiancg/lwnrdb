package org.techhouse.ops.admin;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.techhouse.cluster.ClusterConfig;
import org.techhouse.cluster.HybridClock;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class AdminTombstone {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private static final HybridClock hybridClock = IocContainer.get(HybridClock.class);

    private AdminTombstone() {
    }

    public static void record(AdminRecordKey key) throws IOException {
        if (clusterConfig.isEnabled()) {
            recordAt(key, hybridClock.next());
        }
    }

    public static void recordAt(AdminRecordKey key, long version) throws IOException {
        fs.tombstones().appendAdmin(key.id(), version);
    }

    public static Map<String, Long> all() throws IOException {
        return clusterConfig.isEnabled() ? fs.tombstones().readAdmin() : new HashMap<>();
    }
}

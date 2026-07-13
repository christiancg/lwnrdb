package org.techhouse.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

/**
 * The single cluster-wide admin epoch: one monotonic counter, bumped only by the admin coordinator on each
 * committed admin/DDL op, that decides which node's admin snapshot is authoritative. A node conforms to the
 * highest-epoch snapshot it can see, so a stale rejoining node (even if it briefly becomes coordinator) is
 * ignored until it has caught up. Persisted to {@code filePath/cluster/admin.epoch}; absent ⇒ 0.
 */
public class AdminEpoch {
    private final Logger logger = Logger.logFor(AdminEpoch.class);
    // Guarded by this monitor for both reads and writes (see current()), so the read-modify-write in bump()
    // stays atomic without a flagged volatile increment.
    private long epoch;

    public synchronized void load() {
        final var path = epochFilePath();
        try {
            if (Files.exists(path)) {
                final var stored = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (!stored.isBlank()) {
                    epoch = Long.parseLong(stored);
                }
            }
        } catch (Exception e) {
            logger.warning("Could not read admin epoch, defaulting to 0: " + e.getMessage());
        }
    }

    public synchronized long current() {
        return epoch;
    }

    public synchronized long bump() {
        epoch++;
        persist();
        return epoch;
    }

    public synchronized void adopt(long candidate) {
        if (candidate > epoch) {
            epoch = candidate;
            persist();
        }
    }

    private void persist() {
        final var path = epochFilePath();
        try {
            final var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, Long.toString(epoch), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("Could not persist admin epoch: " + e.getMessage());
        }
    }

    private Path epochFilePath() {
        return Paths.get(Configuration.getInstance().getFilePath(), Globals.CLUSTER_FOLDER,
                Globals.CLUSTER_ADMIN_EPOCH_FILE);
    }
}

package org.techhouse.cluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

public class AdminEpoch {
    private final Logger logger = Logger.logFor(AdminEpoch.class);
    // Guarded by this monitor for both reads and writes (see current()), so the read-modify-write in bump()
    // stays atomic without a flagged volatile increment.
    private long epoch;
    private boolean unreadable;

    public synchronized void load() {
        final var path = epochFilePath();
        unreadable = false;
        epoch = 0;
        if (!Files.exists(path)) {
            return;
        }
        try {
            epoch = Long.parseLong(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            unreadable = true;
            logger.error("Could not read the admin epoch at " + path + "; this node will not conform its admin"
                    + " metadata until the file is repaired or removed, because bidding 0 with real data would"
                    + " lose every comparison and unregister it", e);
        }
    }

    public synchronized boolean isUnreadable() {
        return unreadable;
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
            writeAtomically(path, Long.toString(epoch).getBytes(StandardCharsets.UTF_8));
            unreadable = false;
        } catch (IOException e) {
            logger.error("Could not persist admin epoch " + epoch + "; a restart would read a lower one and"
                    + " reuse this number for a different admin snapshot", e);
        }
    }

    private static void writeAtomically(Path path, byte[] content) throws IOException {
        final var tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path epochFilePath() {
        return Paths.get(Configuration.getInstance().getFilePath(), Globals.CLUSTER_FOLDER,
                Globals.CLUSTER_ADMIN_EPOCH_FILE);
    }
}

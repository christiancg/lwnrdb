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
    private static final String FIELD_SEPARATOR = "|";
    private long epoch;
    private boolean confirmed;
    private boolean unreadable;
    private boolean absent;

    public synchronized void load() {
        final var path = epochFilePath();
        unreadable = false;
        absent = false;
        epoch = 0;
        confirmed = true;
        if (!Files.exists(path)) {
            absent = true;
            return;
        }
        try {
            parse(Files.readString(path, StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            unreadable = true;
            logger.error("Could not read the admin epoch at " + path + "; this node will not conform its admin"
                    + " metadata until the file is repaired or removed, because bidding 0 with real data would"
                    + " lose every comparison and unregister it", e);
        }
    }

    private void parse(String content) {
        final var separator = content.indexOf(FIELD_SEPARATOR);
        if (separator < 0) {
            epoch = Long.parseLong(content);
            confirmed = true;
            return;
        }
        final var flag = content.substring(separator + 1);
        if (!Boolean.TRUE.toString().equals(flag) && !Boolean.FALSE.toString().equals(flag)) {
            throw new IllegalArgumentException("Unrecognised admin epoch confirmation flag: " + flag);
        }
        epoch = Long.parseLong(content.substring(0, separator));
        confirmed = Boolean.parseBoolean(flag);
    }

    public synchronized boolean isUnreadable() {
        return unreadable;
    }

    public synchronized boolean seedFromStandalone() {
        if (!absent || unreadable || epoch != 0) {
            return false;
        }
        epoch = 1;
        confirmed = false;
        absent = false;
        persist();
        return true;
    }

    public synchronized long current() {
        return epoch;
    }

    public synchronized boolean isConfirmed() {
        return confirmed;
    }

    public synchronized State state() {
        return new State(epoch, confirmed);
    }

    public synchronized long bump() {
        epoch++;
        confirmed = false;
        persist();
        return epoch;
    }

    public synchronized void confirm() {
        if (confirmed) {
            return;
        }
        confirmed = true;
        persist();
    }

    public synchronized void markUnconfirmed() {
        if (!confirmed) {
            return;
        }
        confirmed = false;
        persist();
    }

    public synchronized void adopt(long candidate, boolean candidateConfirmed) {
        if (candidate > epoch) {
            epoch = candidate;
            confirmed = candidateConfirmed;
            persist();
            return;
        }
        if (candidate == epoch && candidateConfirmed && !confirmed) {
            confirmed = true;
            persist();
        }
    }

    public synchronized boolean skipsAhead(long candidate) {
        return candidate > epoch + 1;
    }

    public synchronized boolean adoptNext(long candidate) {
        if (candidate != epoch + 1) {
            return false;
        }
        epoch = candidate;
        confirmed = false;
        persist();
        return true;
    }

    private void persist() {
        final var path = epochFilePath();
        try {
            final var parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writeAtomically(path, (epoch + FIELD_SEPARATOR + confirmed).getBytes(StandardCharsets.UTF_8));
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

    public record State(long epoch, boolean confirmed) {
    }
}

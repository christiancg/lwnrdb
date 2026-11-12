package org.techhouse.ops.snapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RetiredCopies;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

public class SnapshotRegistry implements RetiredCopies {
    private final Logger logger = Logger.logFor(SnapshotRegistry.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Map<String, Snapshot> byId = new ConcurrentHashMap<>();
    private final Map<String, List<Snapshot>> watched = new ConcurrentHashMap<>();

    @Override
    public boolean watches(String dbName) {
        return watched.containsKey(dbName);
    }

    @Override
    public void retiring(String dbName, String collName, List<RetiredCopy> retired, List<String> born,
            OldLines oldLines) {
        final var open = watched.get(dbName);
        if (open == null) {
            return;
        }
        for (final var snapshot : open) {
            if (snapshot.holds(collName) && !snapshot.isAborted()) {
                preserveIn(snapshot, collName, retired, born, oldLines);
            }
        }
    }

    private void preserveIn(Snapshot snapshot, String collName, List<RetiredCopy> retired, List<String> born,
            OldLines oldLines) {
        try {
            final var images = snapshot.imagesOf(collName, name -> fs.snapshotFiles().images(snapshot.id(), name));
            var bytes = 0L;
            for (final var copy : retired) {
                bytes += images.preserve(copy, oldLines);
            }
            for (final var id : born) {
                bytes += images.born(id);
            }
            final var maxBytes = configuration.sizeValue(ConfigKey.SNAPSHOT_MAX_BYTES);
            if (snapshot.charge(bytes) > maxBytes) {
                abort(snapshot, "it passed " + ConfigKey.SNAPSHOT_MAX_BYTES.key() + " (" + maxBytes
                        + " bytes) keeping the old versions of the documents written since it was opened");
            }
        } catch (IOException | RuntimeException e) {
            abort(snapshot, "the old versions of the documents written since it was opened could not be kept: "
                    + e.getMessage());
        }
    }

    public synchronized Snapshot open(String dbName, List<String> collectionNames, UUID owner) {
        if (byId.size() >= configuration.intValue(ConfigKey.SNAPSHOT_MAX_OPEN)) {
            return null;
        }
        final var snapshot = new Snapshot(UUID.randomUUID().toString(), dbName, owner, collectionNames,
                System.nanoTime());
        byId.put(snapshot.id(), snapshot);
        watched.compute(dbName, (_, open) -> {
            final var next = open == null ? new CopyOnWriteArrayList<Snapshot>() : open;
            next.add(snapshot);
            return next;
        });
        return snapshot;
    }

    public Snapshot find(String snapshotId, UUID owner) {
        final var snapshot = byId.get(snapshotId);
        if (snapshot == null || !snapshot.owner().equals(owner)) {
            return null;
        }
        snapshot.touch(System.nanoTime());
        return snapshot;
    }

    public void release(Snapshot snapshot) {
        if (byId.remove(snapshot.id(), snapshot)) {
            unwatch(snapshot);
            discardImages(snapshot);
        }
    }

    public int releaseAllOf(UUID owner) {
        return releaseWhere(snapshot -> snapshot.owner().equals(owner));
    }

    public int releaseAll() {
        return releaseWhere(_ -> true);
    }

    public int expireIdle(long nowNanos) {
        final var timeout = TimeUnit.MILLISECONDS.toNanos(configuration.longValue(ConfigKey.SNAPSHOT_IDLE_TIMEOUT_MS));
        final var expired = releaseWhere(snapshot -> nowNanos - snapshot.lastUsedNanos() > timeout);
        if (expired > 0) {
            logger.info("Released " + expired + " snapshot(s) unread for "
                    + configuration.longValue(ConfigKey.SNAPSHOT_IDLE_TIMEOUT_MS) + " ms ("
                    + ConfigKey.SNAPSHOT_IDLE_TIMEOUT_MS.key() + ")");
        }
        return expired;
    }

    public void abortCollection(String dbName, String collName, String reason) {
        abortWhere(snapshot -> snapshot.dbName().equals(dbName) && snapshot.holds(collName), reason);
    }

    public void abortDatabase(String dbName, String reason) {
        abortWhere(snapshot -> snapshot.dbName().equals(dbName), reason);
    }

    public int openCount() {
        return byId.size();
    }

    private void abortWhere(Predicate<Snapshot> matches, String reason) {
        for (final var snapshot : byId.values()) {
            if (matches.test(snapshot)) {
                abort(snapshot, reason);
            }
        }
    }

    private void abort(Snapshot snapshot, String reason) {
        if (snapshot.abort(reason)) {
            unwatch(snapshot);
            discardImages(snapshot);
            logger.warning("Aborted snapshot " + snapshot.id() + " of " + snapshot.dbName() + ": " + reason);
        }
    }

    private int releaseWhere(Predicate<Snapshot> matches) {
        final var released = new ArrayList<Snapshot>();
        for (final var snapshot : byId.values()) {
            if (matches.test(snapshot) && byId.remove(snapshot.id(), snapshot)) {
                released.add(snapshot);
            }
        }
        for (final var snapshot : released) {
            unwatch(snapshot);
            discardImages(snapshot);
        }
        return released.size();
    }

    private void unwatch(Snapshot snapshot) {
        watched.computeIfPresent(snapshot.dbName(), (_, open) -> {
            open.remove(snapshot);
            return open.isEmpty() ? null : open;
        });
    }

    private void discardImages(Snapshot snapshot) {
        try {
            snapshot.closeImages();
        } catch (IOException e) {
            logger.warning("Could not close the image files of snapshot " + snapshot.id() + ": " + e.getMessage());
        }
        if (!fs.snapshotFiles().deleteSnapshotFolder(snapshot.id())) {
            logger.warning("Could not delete the image files of snapshot " + snapshot.id()
                    + "; the next startup deletes them");
        }
    }
}

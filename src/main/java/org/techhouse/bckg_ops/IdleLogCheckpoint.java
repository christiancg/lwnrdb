package org.techhouse.bckg_ops;

import java.util.concurrent.atomic.AtomicLong;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fs.CollectionTryLock;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;

public final class IdleLogCheckpoint {
    private static final FileSystem fs = IocContainer.get(FileSystem.class);
    private static final Configuration configuration = Configuration.getInstance();
    private static final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private static final AtomicLong nextDue = new AtomicLong();
    private static final CollectionTryLock COLLECTION_WRITE_LOCKS = new CollectionTryLock() {
        @Override
        public boolean tryLock(String dbName, String collName) {
            return locks.tryLockWrite(dbName, collName);
        }

        @Override
        public void unlock(String dbName, String collName) {
            locks.releaseWrite(dbName, collName);
        }
    };

    private IdleLogCheckpoint() {
    }

    public static int runDue(long now) {
        final var idleMillis = configuration.longValue(ConfigKey.WAL_IDLE_CHECKPOINT_MS);
        if (idleMillis <= 0L) {
            return 0;
        }
        final var due = nextDue.get();
        if (now < due || !nextDue.compareAndSet(due, now + Math.min(idleMillis, Globals.WAL_IDLE_CHECKPOINT_SCAN_MS))) {
            return 0;
        }
        return fs.checkpointIdleWriteAheadLogs(idleMillis, Globals.WAL_IDLE_CHECKPOINT_MAX_LOGS,
                COLLECTION_WRITE_LOCKS);
    }
}

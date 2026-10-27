package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.techhouse.config.Globals;
import org.techhouse.log.Logger;

final class WalCheckpoints {
    private static final Logger logger = Logger.logFor(WalCheckpoints.class);

    private final FilePaths paths;
    private final WriteAheadLog writeAheadLog;

    WalCheckpoints(FilePaths paths, WriteAheadLog writeAheadLog) {
        this.paths = paths;
        this.writeAheadLog = writeAheadLog;
    }

    @SuppressWarnings({"resource", "ResultOfMethodCallIgnored"})
    void atShutdown(long deadline) {
        final var dirty = new ArrayList<WriteAheadLog.WalLogStatus>();
        for (final var status : writeAheadLog.statusesUnder(root())) {
            if (status.dirty()) {
                dirty.add(status);
            }
        }
        if (dirty.isEmpty()) {
            return;
        }
        final var next = new AtomicInteger();
        final var finished = new AtomicInteger();
        final var workers = Math.min(Globals.WAL_CHECKPOINT_PARALLELISM, dirty.size());
        final var executor = Executors.newVirtualThreadPerTaskExecutor();
        for (var worker = 0; worker < workers; worker++) {
            executor.execute(() -> checkpointUntil(dirty, next, finished, deadline));
        }
        executor.shutdown();
        try {
            executor.awaitTermination(Math.max(0L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        final var left = dirty.size() - finished.get();
        if (left > 0) {
            logger.warning("The shutdown budget ran out with " + left + " write-ahead log(s) not checkpointed; the"
                    + " next startup replays them");
        }
    }

    private void checkpointUntil(List<WriteAheadLog.WalLogStatus> logs, AtomicInteger next, AtomicInteger finished,
            long deadline) {
        while (System.currentTimeMillis() < deadline) {
            final var index = next.getAndIncrement();
            if (index >= logs.size()) {
                return;
            }
            checkpointAtShutdown(logs.get(index));
            finished.incrementAndGet();
        }
    }

    private void checkpointAtShutdown(WriteAheadLog.WalLogStatus status) {
        final var subject = WriteAheadLog.subject(status.dbName(), status.collName());
        if (WriteAheadLog.holdsIndexWork(status.dbName(), status.collName())) {
            logger.info("Leaving the write-ahead log of " + subject + " as it is: its field-index work is still"
                    + " pending, so the next startup replays it and finishes that work");
            return;
        }
        try {
            writeAheadLog.checkpoint(status.dbName(), status.collName());
        } catch (IOException e) {
            logger.error("Could not checkpoint the write-ahead log of " + subject + "; the next startup replays it", e);
        }
    }

    int idle(long idleMillis, int maxLogs, CollectionTryLock collectionLocks) {
        final var now = System.currentTimeMillis();
        var checkpointed = 0;
        for (final var status : writeAheadLog.statusesUnder(root())) {
            if (checkpointed >= maxLogs) {
                break;
            }
            if (!status.dirty() || status.grouped() || now - status.lastWriteMillis() < idleMillis
                    || WriteAheadLog.holdsIndexWork(status.dbName(), status.collName())
                    || !collectionLocks.tryLock(status.dbName(), status.collName())) {
                continue;
            }
            try {
                if (writeAheadLog.checkpointIfIdle(status.dbName(), status.collName(), idleMillis)) {
                    checkpointed++;
                }
            } catch (IOException e) {
                logger.error("Could not checkpoint the idle write-ahead log of "
                        + WriteAheadLog.subject(status.dbName(), status.collName())
                        + "; it keeps its records and a later pass retries", e);
            } finally {
                collectionLocks.unlock(status.dbName(), status.collName());
            }
        }
        return checkpointed;
    }

    private String root() {
        return new File(paths.dbPath()).getAbsolutePath() + File.separator;
    }
}

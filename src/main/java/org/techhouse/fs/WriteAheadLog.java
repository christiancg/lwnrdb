package org.techhouse.fs;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fault.FaultInjector;
import org.techhouse.fault.FaultPoint;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

final class WriteAheadLog {
    private static final Logger logger = Logger.logFor(WriteAheadLog.class);
    private static volatile CheckpointGuard guard = CheckpointGuard.NO_INDEX_WORK;
    private final Configuration configuration = Configuration.getInstance();
    private final FaultInjector faultInjector = IocContainer.get(FaultInjector.class);

    private final FilePaths paths;
    private volatile LogPath lastLog;

    private record LogPath(String root, String dbName, String collName, File file, String key) {
        private boolean names(String otherRoot, String otherDb, String otherColl) {
            return dbName.equals(otherDb) && collName.equals(otherColl) && Objects.equals(root, otherRoot);
        }
    }

    WriteAheadLog(FilePaths paths) {
        this.paths = paths;
    }

    private LogPath logOf(String dbName, String collName) {
        final var root = paths.dbPath();
        final var last = lastLog;
        if (last != null && last.names(root, dbName, collName)) {
            return last;
        }
        final var file = paths.writeAheadLog(dbName, collName);
        final var made = new LogPath(root, dbName, collName, file, file.getAbsolutePath());
        lastLog = made;
        return made;
    }

    private static WalLogState stateOf(LogPath log) throws IOException {
        return WalLogState.of(log.file(), log.key(), log.dbName(), log.collName());
    }

    private static WalLogState knownState(LogPath log) throws IOException {
        return stateOf(log);
    }

    Lock lock(String dbName, String collName) {
        return FileLocks.lockWrite(logOf(dbName, collName).file());
    }

    long append(String dbName, String collName, WalCodec.RecordBuffer record, Collection<Long> pages)
            throws IOException {
        final var log = logOf(dbName, collName);
        var state = stateOf(log);
        if (state.knownLength == 0L) {
            checkpoint(dbName, collName);
            state = stateOf(log);
        }
        if (state.group != null && state.group.failed()) {
            throw new IOException("An earlier write grouped with this one on " + subject(dbName, collName)
                    + " could not be made durable, so the group is refused");
        }
        if (state.group != null && state.group.idle()) {
            state.group.restartFrom(state.knownLength);
        }
        if (state.group != null && !state.groupBaseLogged) {
            logGroupBase(dbName, collName, log.file(), state);
        }
        final var seq = state.nextSeq;
        record.frame(seq);
        write(log.file(), state, record.bytes(), record.size(), state.group == null);
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
        state.touchedPages.addAll(pages);
        if (state.group != null) {
            state.group.recorded();
        }
        faultInjector.at(FaultPoint.WAL_APPENDED, dbName, collName);
        return seq;
    }

    void abort(String dbName, String collName, long seq) throws IOException {
        final var log = logOf(dbName, collName);
        final var state = stateOf(log);
        final var frame = WalCodec.encodeAbort(state.nextSeq, seq);
        write(log.file(), state, frame, frame.length, state.group == null);
        state.nextSeq++;
        state.recordsSinceCheckpoint++;
    }

    private void logGroupBase(String dbName, String collName, File log, WalLogState state) throws IOException {
        final var pkIndex = paths.pkIndexFile(dbName, collName);
        final var frame = WalCodec.encodeGroupBase(state.nextSeq, pkIndex.exists() ? pkIndex.length() : 0L);
        write(log, state, frame, frame.length, true);
        state.nextSeq++;
        state.groupBaseLogged = true;
        state.group.rebase(state.knownLength);
    }

    void openGroup(String dbName, String collName) throws IOException {
        final var log = logOf(dbName, collName);
        final var lock = FileLocks.lockWrite(log.file());
        try {
            final var state = knownState(log);
            if (state.group == null) {
                state.group = new WalWriteGroup(state.knownLength);
            }
        } finally {
            lock.unlock();
        }
    }

    boolean closeGroup(String dbName, String collName) throws IOException {
        final var log = logOf(dbName, collName);
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(dbName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = knownState(log);
            if (state.group == null) {
                return true;
            }
            try {
                return settle(dbName, collName, log.file(), state);
            } finally {
                state.endGroup();
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    boolean forceGroup(String dbName, String collName) throws IOException {
        final var log = logOf(dbName, collName);
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var group = knownState(log).group;
            if (group == null || group.forced() || !group.failed() && group.idle()) {
                return true;
            }
            return !group.failed() && forceOpenGroup(dbName, collName, log.file(), group);
        } finally {
            walLock.unlock();
        }
    }

    WalWriteGroup openGroupOf(String dbName, String collName) {
        final var state = WalLogState.known(logOf(dbName, collName).key());
        return state == null ? null : state.group;
    }

    private boolean settle(String dbName, String collName, File log, WalLogState state) {
        final var group = state.group;
        if (group.failed()) {
            takeBack(dbName, collName, log, state);
            return false;
        }
        if (group.idle()) {
            return true;
        }
        if (!group.forced() && !forceOpenGroup(dbName, collName, log, group)) {
            takeBack(dbName, collName, log, state);
            return false;
        }
        if (!group.applyBlanks()) {
            state.unapplied = true;
        }
        group.restartFrom(state.knownLength);
        return true;
    }

    private boolean forceOpenGroup(String dbName, String collName, File log, WalWriteGroup group) {
        faultInjector.at(FaultPoint.WAL_GROUP_APPENDED, dbName, collName);
        try {
            group.forceLog(log);
        } catch (IOException e) {
            logger.error("Could not force the write-ahead log of " + subject(dbName, collName) + " for a group of "
                    + group.records() + " write(s); the group is taken back and every one of them fails", e);
            group.fail();
            return false;
        }
        faultInjector.at(FaultPoint.WAL_GROUP_FORCED, dbName, collName);
        group.markForced();
        return true;
    }

    private void takeBack(String dbName, String collName, File log, WalLogState state) {
        final var group = state.group;
        if (group.takeBack(log, state.knownLength, paths.pkIndexFile(dbName, collName))) {
            state.recordsSinceCheckpoint = Math.max(0, state.recordsSinceCheckpoint - group.records());
            state.knownLength = group.logLengthBefore();
        } else {
            state.unapplied = true;
            logger.error("Could not take back a failed group of writes to " + subject(dbName, collName) + "; they are"
                    + " applied before the next write to the collection, or at the next startup");
        }
        group.restartFrom(state.knownLength);
    }

    private static void write(File log, WalLogState state, byte[] frame, int length, boolean force) throws IOException {
        final var at = state.knownLength;
        var written = false;
        try {
            if (state.group == null) {
                try (var channel = WalWriteGroup.openWritable(log)) {
                    state.allocatedLength = WalWriteGroup.writeFrame(channel, log, at, state.allocatedLength, frame,
                            length, force);
                }
            } else {
                state.allocatedLength = state.group.write(log, at, state.allocatedLength, frame, length, force);
            }
            written = true;
        } finally {
            if (!written) {
                if (!WalWriteGroup.erase(log, at, Math.min(at + length, log.length()),
                        "Could not erase a partial append to the write-ahead log " + log.getPath()
                                + "; the next startup cuts it")) {
                    state.reseedOnNextUse();
                }
                state.allocatedLength = Math.max(at, log.length());
            }
        }
        //noinspection NonAtomicOperationOnVolatileField
        state.knownLength += length;
        state.lastWriteMillis = System.currentTimeMillis();
    }

    void markUnapplied(String dbName, String collName) {
        final var state = WalLogState.known(logOf(dbName, collName).key());
        if (state != null) {
            state.unapplied = true;
        }
    }

    void requireApplied(String dbName, String collName) throws IOException {
        final var log = logOf(dbName, collName);
        final var known = WalLogState.known(log.key());
        if (known == null || !known.unapplied) {
            return;
        }
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(dbName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = stateOf(log);
            if (state.unapplied && !log.file().exists()) {
                state.unapplied = false;
                logger.warning("The write-ahead log of " + subject(dbName, collName) + " is gone, so the writes it"
                        + " held can no longer be applied");
            } else if (state.unapplied) {
                if (state.group != null && !settle(dbName, collName, log.file(), state)) {
                    throw new IOException("Could not make the open write group of " + subject(dbName, collName)
                            + " durable before applying its logged writes");
                }
                WalWriteGroup.forceUnheld(log.file());
                WalRedo.replay(paths, dbName, collName, WalCodec.decodeAll(Files.readAllBytes(log.file().toPath())));
                state.unapplied = false;
                logger.warning("Applied the logged writes of " + subject(dbName, collName) + " that an earlier write"
                        + " could not");
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    void settleBeforeRewrite(String dbName, String collName) throws IOException {
        final var log = logOf(dbName, collName);
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(dbName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            requireApplied(dbName, collName);
            final var state = stateOf(log);
            if (state.knownLength == 0L) {
                checkpoint(dbName, collName);
            }
            if (state.group != null) {
                if (!settle(dbName, collName, log.file(), state)) {
                    throw new IOException("Could not force the open write group of " + subject(dbName, collName)
                            + " before rewriting one of its pages; its writes were taken back");
                }
                state.endGroup();
            }
            requireApplied(dbName, collName);
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    long beginRewrite(String dbName, String collName, long page, long lengthBefore, ForceGroup alongside)
            throws IOException {
        alongside.add(paths.collectionPage(dbName, collName, page).toPath());
        alongside.force();
        return appendFrame(dbName, collName, seq -> WalCodec.encodeRewriteBegin(seq, page, lengthBefore), true);
    }

    void endRewrite(String dbName, String collName, long beginSeq, long page, long lengthAfter, boolean force)
            throws IOException {
        appendFrame(dbName, collName, seq -> WalCodec.encodeRewriteEnd(seq, beginSeq, page, lengthAfter), force);
    }

    private long appendFrame(String dbName, String collName, LongFunction<byte[]> frame, boolean force)
            throws IOException {
        final var log = logOf(dbName, collName);
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = stateOf(log);
            final var seq = state.nextSeq;
            final var bytes = frame.apply(seq);
            write(log.file(), state, bytes, bytes.length, force);
            state.nextSeq++;
            state.recordsSinceCheckpoint++;
            return seq;
        } finally {
            walLock.unlock();
        }
    }

    void track(String dbName, String collName) throws IOException {
        final var log = logOf(dbName, collName);
        final var lock = FileLocks.lockWrite(log.file());
        try {
            stateOf(log);
        } finally {
            lock.unlock();
        }
    }

    static void guardWith(CheckpointGuard checkpointGuard) {
        guard = checkpointGuard;
    }

    void checkpointIfFull(String dbName, String collName) {
        final var log = logOf(dbName, collName);
        final var state = WalLogState.known(log.key());
        final var limit = configuration.getWalCheckpointBytes();
        if (state == null || state.knownLength <= limit) {
            return;
        }
        if (state.knownLength / Globals.WAL_SYNC_CHECKPOINT_FACTOR <= limit) {
            WalCheckpointRequests.request(log.key(), dbName, collName);
            return;
        }
        try {
            checkpoint(dbName, collName);
        } catch (IOException e) {
            logger.error("Could not checkpoint the full write-ahead log of " + subject(dbName, collName)
                    + "; it keeps its records and the next write or the shutdown retries", e);
        }
    }

    void checkpoint(String dbName, String collName) throws IOException {
        if (!paths.collectionFolder(dbName, collName).isDirectory()) {
            return;
        }
        final var log = logOf(dbName, collName);
        final var pkIndex = paths.pkIndexFile(dbName, collName);
        final var pkLock = FileLocks.lockWrite(pkIndex);
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            requireApplied(dbName, collName);
            final var state = stateOf(log);
            if (state.group != null && !settle(dbName, collName, log.file(), state)) {
                throw new IOException("Could not force the open write group of " + subject(dbName, collName)
                        + " before a checkpoint; its writes were taken back");
            }
            if (!isDirty(state)) {
                return;
            }
            final var pending = List.copyOf(guard.pendingIds(dbName, collName));
            final var group = new ForceGroup();
            for (final var page : state.touchedPages) {
                final var file = paths.collectionPage(dbName, collName, page);
                if (file.exists()) {
                    group.add(file.toPath());
                }
            }
            if (pkIndex.exists()) {
                group.add(pkIndex.toPath());
            }
            FieldIndexLog.forceUnforced(paths.collectionFolder(dbName, collName), group);
            group.force();
            faultInjector.at(FaultPoint.WAL_CHECKPOINT_FORCED, dbName, collName);
            final var checkpointFrame = WalCodec
                    .encode(new WalCheckpoint(state.nextSeq, dbName, collName, pkIndex.exists() ? pkIndex.length() : 0L,
                            DocumentPageStore.pageFileLengths(paths, dbName, collName)));
            final var bytes = WalCodec.withPending(checkpointFrame, state.nextSeq + 1, pending);
            FileLocks.rewriteFileAtomically(log.file().toPath(), bytes);
            state.nextSeq += pending.isEmpty() ? 1 : 2;
            state.knownLength = bytes.length;
            state.allocatedLength = bytes.length;
            state.recordsSinceCheckpoint = 0;
            state.groupBaseLogged = false;
            state.pendingLogged = !pending.isEmpty();
            state.touchedPages.clear();
            if (state.group != null) {
                state.group.restartFrom(state.knownLength);
            }
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    record WalLogStatus(String dbName, String collName, boolean dirty, boolean grouped, long lastWriteMillis) {
    }

    List<WalLogStatus> statusesUnder(String root) {
        final var statuses = new ArrayList<WalLogStatus>();
        for (final var state : WalLogState.all()) {
            if (state.path.startsWith(root)) {
                statuses.add(new WalLogStatus(state.dbName, state.collName, isDirty(state), state.group != null,
                        state.lastWriteMillis));
            }
        }
        return statuses;
    }

    private static boolean isDirty(WalLogState state) {
        return state.recordsSinceCheckpoint > 0 || state.groupBaseLogged || state.knownLength == 0L
                || state.pendingLogged && !guard.holdsIndexWork(state.dbName, state.collName);
    }

    static boolean holdsIndexWork(String dbName, String collName) {
        return guard.holdsIndexWork(dbName, collName);
    }

    boolean checkpointIfIdle(String dbName, String collName, long idleMillis) throws IOException {
        return checkpointWhen(dbName, collName, state -> state.group == null && isDirty(state)
                && System.currentTimeMillis() - state.lastWriteMillis >= idleMillis);
    }

    boolean checkpointRequested(String dbName, String collName) throws IOException {
        return checkpointWhen(dbName, collName, state -> state.group == null && !state.unapplied
                && state.knownLength > configuration.getWalCheckpointBytes());
    }

    private boolean checkpointWhen(String dbName, String collName, Predicate<WalLogState> due) throws IOException {
        final var log = logOf(dbName, collName);
        final var pkLock = FileLocks.lockWrite(paths.pkIndexFile(dbName, collName));
        final var walLock = FileLocks.lockWrite(log.file());
        try {
            final var state = WalLogState.known(log.key());
            if (state == null || !due.test(state)) {
                return false;
            }
            checkpoint(dbName, collName);
            return true;
        } finally {
            walLock.unlock();
            pkLock.unlock();
        }
    }

    static void forgetUnder(File folder) {
        WalLogState.forgetUnder(folder);
        WalCheckpointRequests.forgetUnder(folder);
    }

    static String subject(String dbName, String collName) {
        return dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName;
    }
}
